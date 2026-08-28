package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a rebind does to key rows, now that there are key rows.
 *
 * <p>Two rules, and they pull in opposite directions:</p>
 *
 * <ul>
 *   <li><b>The key row cannot move.</b> A {@code did:key} is its public key —
 *       re-pointing the row at a different DID makes the name and the key
 *       contradict each other. The old identity has to survive anyway, or
 *       nothing it ever signed can be verified again.</li>
 *   <li><b>The name must move.</b> Exactly one row may answer "which identity is
 *       {@code entityId}?". Leaving that on the folded row is the 2026-08-08
 *       third-companion bug with a database instead of a file: the next boot
 *       asks, gets the identity that was just folded away, and the whole rebind
 *       is undone by a restart.</li>
 * </ul>
 */
class RebindMovesTheNameNotTheKeyTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;

    private static final String ENTITY = "entity-ari";

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("CREATE TABLE soul_manifests(did TEXT, version INT, forged_at TEXT,"
                + " content_hash TEXT, manifest_json TEXT, archived INT DEFAULT 0,"
                + " archive_reason TEXT)");
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " name TEXT, archived INT DEFAULT 0)");
            st.execute("CREATE TABLE wants(id TEXT PRIMARY KEY, agent_did TEXT, body TEXT)");
        }
        AgentIdentityProvisioner.init(jdbc, () -> secret);
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(jdbc);
    }

    /** Both identities exist, both hold keys, both are the same companion's. */
    private String[] twoIdentities() throws Exception {
        var oldId = AgentIdentityProvisioner.mint(ENTITY);
        var newId = AgentIdentityProvisioner.mint(null);
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("INSERT INTO soul_manifests(did, version, archived) VALUES('"
                + newId.did() + "', 1, 0)");
            st.execute("INSERT INTO soul_manifests(did, version, archived) VALUES('"
                + oldId.did() + "', 1, 0)");
            st.execute("INSERT INTO companions(did, entity_id, name, archived) VALUES('"
                + oldId.did() + "', '" + ENTITY + "', 'ari', 0)");
            st.execute("INSERT INTO companions(did, entity_id, name, archived) VALUES('"
                + newId.did() + "', '" + ENTITY + "', 'ari', 0)");
            st.execute("INSERT INTO wants(id, agent_did, body) VALUES('w1', '"
                + oldId.did() + "', 'to be read to')");
        }
        return new String[]{oldId.did(), newId.did()};
    }

    /** THE case: the spawn identity follows the companion, the keys stay put. */
    @Test
    void the_entity_link_moves_and_both_keys_remain() throws Exception {
        var dids = twoIdentities();
        var oldDid = dids[0];
        var newDid = dids[1];

        AgentRebind.apply(jdbc, oldDid, newDid);

        var store = AgentIdentityProvisioner.identities().orElseThrow();
        assertThat(store.didForEntity(ENTITY))
            .as("the next boot must find the trunk, not the fold")
            .contains(newDid);
        assertThat(store.findByDid(oldDid))
            .as("the old key row must survive — it is what verifies anything signed under it")
            .isPresent();
        assertThat(store.canSign(oldDid))
            .as("and it must keep its key, not be hollowed out")
            .isTrue();
    }

    /** A restart after the rebind must not resurrect the folded identity. */
    @Test
    void the_folded_identity_stops_answering_for_the_entity() throws Exception {
        var dids = twoIdentities();

        AgentRebind.apply(jdbc, dids[0], dids[1]);

        try (var c = conn();
             var ps = c.prepareStatement(
                 "SELECT entity_id FROM agent_identities WHERE did = ?")) {
            ps.setString(1, dids[0]);
            var rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                .as("two rows claiming the same entityId is how a third companion gets born")
                .isNull();
        }
    }

    /**
     * The schema-discovery guard must not be tripped by the new table. It exists
     * to refuse a partial migration; a false positive here would block every
     * rebind on a node that has agent identities — which is now all of them.
     */
    @Test
    void the_unhandled_reference_guard_accepts_the_identity_table() throws Exception {
        var dids = twoIdentities();

        var result = AgentRebind.apply(jdbc, dids[0], dids[1]);

        assertThat(result.rowsMoved()).isGreaterThan(0);
        assertThat(result.notes())
            .as("preserved key rows should be reported, not silently skipped")
            .anyMatch(n -> n.contains("agent_identities"));
    }

    /** A dry run must leave the link exactly where it was. */
    @Test
    void a_plan_writes_nothing() throws Exception {
        var dids = twoIdentities();

        AgentRebind.plan(jdbc, dids[0], dids[1]);

        var store = AgentIdentityProvisioner.identities().orElseThrow();
        assertThat(store.didForEntity(ENTITY)).contains(dids[0]);
    }

    /** Nodes that predate the table must still rebind. */
    @Test
    void a_database_without_the_identity_table_still_rebinds() throws Exception {
        var bare = "jdbc:sqlite:" + tmp.resolve("bare.db").toAbsolutePath();
        try (var c = DriverManager.getConnection(bare); var st = c.createStatement()) {
            st.execute("CREATE TABLE soul_manifests(did TEXT, version INT, archived INT DEFAULT 0,"
                + " archive_reason TEXT)");
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " archived INT DEFAULT 0)");
            st.execute("CREATE TABLE wants(id TEXT PRIMARY KEY, agent_did TEXT)");
            st.execute("INSERT INTO soul_manifests(did, version, archived) VALUES('did:key:zB',1,0)");
            st.execute("INSERT INTO companions(did, entity_id) VALUES('did:key:zA','e')");
            st.execute("INSERT INTO companions(did, entity_id) VALUES('did:key:zB','e')");
            st.execute("INSERT INTO wants(id, agent_did) VALUES('w','did:key:zA')");
        }

        var result = AgentRebind.apply(bare, "did:key:zA", "did:key:zB");

        assertThat(result.rowsMoved()).isEqualTo(1);
    }
}
