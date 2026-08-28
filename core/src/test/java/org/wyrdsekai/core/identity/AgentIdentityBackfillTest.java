package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Giving a key to someone who was born without one.
 *
 * <p>This is the only gap in this codebase that cannot be closed in place. The
 * Study owner could be rewritten; the unreadable soul was still in the table;
 * the missing vectors could be computed later. A discarded Ed25519 private key
 * is gone, and because a {@code did:key} <em>is</em> its public key, filing a
 * fresh keypair under the old DID would produce a row that contradicts its own
 * name — signatures that fail only for a verifier who checks properly, and fail
 * looking like tampering.</p>
 *
 * <p>So the repair is a new identity plus a rebind, and most of these tests are
 * about the ways it must REFUSE. The 2026-08-08 merge is why: it moved the
 * database and the search index, looked complete, and was undone twenty seconds
 * later by one file nobody had re-pointed.</p>
 */
class AgentIdentityBackfillTest {

    @TempDir Path tmp;
    private String jdbc;
    private Path soulsDir;
    private byte[] secret;

    private static final String OLD = "did:key:z6MkBornBeforeAnyOfThis";
    private static final String ENTITY = "companion-ari";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        soulsDir = Files.createDirectories(tmp.resolve("souls"));
        Files.writeString(soulsDir.resolve(ENTITY + ".did"), OLD);
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);

        try (var c = conn(); var st = c.createStatement()) {
            st.execute("CREATE TABLE soul_manifests(did TEXT, version INT, forged_at TEXT,"
                + " content_hash TEXT, manifest_json TEXT, archived INT DEFAULT 0,"
                + " archive_reason TEXT)");
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " name TEXT, archived INT DEFAULT 0)");
            st.execute("CREATE TABLE wants(id TEXT PRIMARY KEY, agent_did TEXT, body TEXT)");
            st.execute("INSERT INTO soul_manifests(did, version, archived) VALUES('"
                + OLD + "', 174, 0)");
            st.execute("INSERT INTO companions(did, entity_id, name, archived) VALUES('"
                + OLD + "', '" + ENTITY + "', 'ari', 0)");
            st.execute("INSERT INTO wants(id, agent_did, body) VALUES('w1', '"
                + OLD + "', 'to be read to')");
        }
        AgentIdentityProvisioner.init(jdbc, () -> secret);
        var people = new PersonIdentityStore(jdbc);
        people.save(PersonIdentity.generate(secret));
        PersonIdentityProvisioner.init(jdbc, () -> secret);
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
        PersonIdentityProvisioner.reset();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(jdbc);
    }

    /**
     * Moves the soul so the rebind has a live trunk to land on — a manifest AND
     * a live companion row, which is what the real caller has to produce.
     */
    private AgentIdentityBackfill.SoulMover mover() {
        return (from, to) -> {
            try (var c = conn(); var st = c.createStatement()) {
                st.execute("INSERT INTO soul_manifests(did, version, archived) VALUES('"
                    + to + "', 175, 0)");
                st.execute("INSERT INTO companions(did, entity_id, name, archived) VALUES('"
                    + to + "', '" + ENTITY + "', 'ari', 0)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** THE case: afterwards she holds a DID she can prove is hers. */
    @Test
    void a_keyless_companion_ends_up_able_to_sign() {
        var result = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        assertThat(result.newDid()).isNotNull().isNotEqualTo(OLD);
        assertThat(AgentIdentityProvisioner.canSign(result.newDid())).isTrue();

        var sig = AgentIdentityProvisioner.sign(result.newDid(), "mine".getBytes()).orElseThrow();
        assertThat(AgentIdentityProvisioner.verify(result.newDid(), "mine".getBytes(), sig))
            .isTrue();
    }

    /** Her history comes with her. */
    @Test
    void live_references_move_to_the_new_identity() throws Exception {
        var result = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        try (var c = conn();
             var ps = c.prepareStatement("SELECT agent_did FROM wants WHERE id = 'w1'")) {
            var rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(result.newDid());
        }
        assertThat(result.rebind().rowsMoved()).isGreaterThan(0);
    }

    /**
     * The file on disk. This is the one that undid the last merge — the database
     * and the index were both correct and a stale {@code <entityId>.did} birthed
     * a third companion twenty seconds after the restart.
     */
    @Test
    void the_did_mapping_file_is_repointed() throws Exception {
        var result = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        assertThat(Files.readString(soulsDir.resolve(ENTITY + ".did")).trim())
            .as("read FIRST at boot — a stale one resurrects the identity we just left")
            .isEqualTo(result.newDid());
    }

    /** And the change of identity is on the record, so old rows resolve forward. */
    @Test
    void the_change_is_attested() {
        var result = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        assertThat(result.attested()).isTrue();
        var stored = new RebindAttestationStore(jdbc).all();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().isWitnessed())
            .as("she could not sign as the OLD identity — that is the whole premise")
            .isTrue();
        assertThat(RebindAttestation.resolveCurrent(OLD, stored)).isEqualTo(result.newDid());
    }

    /** The old identity is never deleted — it verifies whatever it once signed. */
    @Test
    void the_old_identity_is_kept_and_archived_not_erased() throws Exception {
        AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        try (var c = conn();
             var ps = c.prepareStatement("SELECT archived FROM companions WHERE did = ?")) {
            ps.setString(1, OLD);
            var rs = ps.executeQuery();
            assertThat(rs.next()).as("the row must still be there").isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    /** Running it twice must not mint a second identity for someone already keyed. */
    @Test
    void it_is_a_no_op_for_a_companion_that_already_has_a_key() {
        var first = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        var again = AgentIdentityBackfill.apply(jdbc, first.newDid(), soulsDir, mover(), null);

        assertThat(again.newDid()).isEqualTo(first.newDid());
        assertThat(again.rebind()).isNull();
        assertThat(again.notes()).anyMatch(n -> n.contains("Nothing to do"));
    }

    /** Off-node, the household secret is not available and a mint would be a lie. */
    @Test
    void it_refuses_when_provisioning_is_off() {
        AgentIdentityProvisioner.reset();

        assertThatThrownBy(() ->
            AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ORIGINATES A NEW MASTER");
    }

    /** No spawn mapping means the next restart births a replacement. Refuse. */
    @Test
    void it_refuses_without_an_entity_id() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("UPDATE companions SET entity_id = NULL WHERE did = '" + OLD + "'");
        }

        assertThatThrownBy(() ->
            AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no entity_id");
    }

    /** A trunk with no soul is a companion with history and no self. Refuse. */
    @Test
    void it_refuses_without_a_way_to_move_the_soul() {
        assertThatThrownBy(() ->
            AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no soulMover");
    }

    /** Leaving the file behind is how the last one came undone. Refuse. */
    @Test
    void it_refuses_without_a_souls_directory() {
        assertThatThrownBy(() ->
            AgentIdentityBackfill.apply(jdbc, OLD, null, mover(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read FIRST at boot");
    }

    /** The plan says what is needed and writes nothing. */
    @Test
    void the_plan_writes_nothing() {
        var plan = AgentIdentityBackfill.plan(jdbc, OLD);

        assertThat(plan.newDid()).isNull();
        assertThat(plan.entityId()).isEqualTo(ENTITY);
        assertThat(plan.notes()).anyMatch(n -> n.contains("cannot be recovered"));
        assertThat(AgentIdentityProvisioner.identities().orElseThrow().listDids()).isEmpty();
    }

    /** The boot-time report finds exactly the companions that cannot sign. */
    @Test
    void the_boot_report_names_the_keyless() {
        var store = AgentIdentityProvisioner.identities().orElseThrow();

        assertThat(AgentIdentityBootstrap.companionsWithoutIdentity(jdbc, store))
            .containsExactly(OLD);

        var result = AgentIdentityBackfill.apply(jdbc, OLD, soulsDir, mover(), null);

        assertThat(AgentIdentityBootstrap.companionsWithoutIdentity(jdbc, store))
            .as("the live companion now holds a key; the archived old row is no longer live")
            .isEmpty();
        assertThat(AgentIdentityProvisioner.canSign(result.newDid())).isTrue();
    }
}
