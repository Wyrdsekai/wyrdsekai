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
 * Writing down the half of an identity that survived.
 *
 * <p>A companion born before {@link AgentIdentityStore} lost her private key for
 * good — but not her public one. A {@code did:key} is a multibase encoding
 * <em>of the public key</em>, so it comes straight back out of the identifier
 * she is already known by. Recording it costs nothing, changes no DID, and buys
 * the {@code entityId → DID} second witness that a file-only mapping did not
 * have when a stale copy birthed a third companion on 2026-08-08.</p>
 *
 * <p>The line this must not cross: the row has to keep saying she cannot sign.
 * A public-key row that reported itself as keyed would be a worse lie than the
 * gap it replaced.</p>
 */
class ExistingCompanionsAreRecordedTest {

    @TempDir Path tmp;
    private String jdbc;
    private AgentIdentityStore store;

    /** A real did:key — the public key must be recoverable from it. */
    private static String realDid;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        realDid = DidKey.generate().did();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("CREATE TABLE companions(did TEXT PRIMARY KEY, entity_id TEXT,"
                + " name TEXT, archived INT DEFAULT 0)");
        }
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        AgentIdentityProvisioner.init(jdbc, () -> secret);
        store = AgentIdentityProvisioner.identities().orElseThrow();
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(jdbc);
    }

    private void companion(String did, String entityId, int archived) throws Exception {
        try (var c = conn();
             var ps = c.prepareStatement("INSERT INTO companions(did, entity_id, name, archived)"
                 + " VALUES(?,?,?,?)")) {
            ps.setString(1, did);
            ps.setString(2, entityId);
            ps.setString(3, "ari");
            ps.setInt(4, archived);
            ps.executeUpdate();
        }
    }

    /** THE case: the public key comes back out of the DID it is encoded in. */
    @Test
    void the_public_key_is_recovered_from_the_did() throws Exception {
        companion(realDid, "companion-ari", 0);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isEqualTo(1);

        var recovered = store.findByDid(realDid).orElseThrow();
        assertThat(DidKey.fromRawPublicKey(recovered.publicKey()))
            .as("a key that does not re-derive its own DID would be worse than none")
            .isEqualTo(realDid);
    }

    /** And it must still say, plainly, that she cannot sign. */
    @Test
    void the_row_does_not_claim_a_key_it_does_not_have() throws Exception {
        companion(realDid, "companion-ari", 0);

        AgentIdentityBootstrap.recordExistingCompanions(jdbc, store);

        assertThat(store.findByDid(realDid).orElseThrow().privateKeyEncrypted()).isNull();
        assertThat(store.canSign(realDid)).isFalse();
        assertThat(store.listKeyless()).containsExactly(realDid);
        assertThat(AgentIdentityProvisioner.sign(realDid, "x".getBytes())).isEmpty();
        assertThat(AgentIdentityBootstrap.companionsWithoutIdentity(jdbc, store))
            .as("the boot report must keep naming her — the gap is recorded, not closed")
            .containsExactly(realDid);
    }

    /** The point of the exercise: a second witness to who this entityId is. */
    @Test
    void the_entity_mapping_gains_a_second_witness() throws Exception {
        companion(realDid, "companion-ari", 0);

        AgentIdentityBootstrap.recordExistingCompanions(jdbc, store);

        assertThat(AgentIdentityProvisioner.existingDidFor("companion-ari"))
            .as("a stale souls/<entityId>.did was enough to birth a third companion")
            .contains(realDid);
    }

    /** Verification is the half of an identity that survives losing the key. */
    @Test
    void anything_signed_under_that_did_stays_verifiable() throws Exception {
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var original = AgentIdentity.generate(secret);
        var signature = original.sign("said long ago".getBytes(), secret);
        companion(original.did(), "companion-ari", 0);

        AgentIdentityBootstrap.recordExistingCompanions(jdbc, store);

        assertThat(AgentIdentityProvisioner.verify(
            original.did(), "said long ago".getBytes(), signature))
            .as("public-key-only is still enough to check what she once said")
            .isTrue();
    }

    /** Running it every boot must not duplicate or disturb anything. */
    @Test
    void it_is_idempotent_across_boots() throws Exception {
        companion(realDid, "companion-ari", 0);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isEqualTo(1);
        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isZero();
        assertThat(store.listDids()).containsExactly(realDid);
    }

    /** A companion that already holds a real key must never be overwritten. */
    @Test
    void a_keyed_companion_is_left_alone() throws Exception {
        var minted = AgentIdentityProvisioner.mint("companion-ari");
        companion(minted.did(), "companion-ari", 0);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isZero();
        assertThat(store.canSign(minted.did()))
            .as("clobbering a live key with a public-only row would disarm her")
            .isTrue();
    }

    /** Archived identities are history, not residents. */
    @Test
    void archived_companions_are_not_recorded() throws Exception {
        companion(realDid, "companion-ari", 1);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isZero();
        assertThat(store.listDids()).isEmpty();
    }

    /** A DID that carries no key must be skipped, not invented. */
    @Test
    void a_non_key_did_is_skipped_rather_than_faked() throws Exception {
        companion("did:wyrd:zone:some-companion", "companion-ari", 0);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store)).isZero();
        assertThat(store.listDids()).isEmpty();
    }

    /** A malformed did:key must not take down the boot. */
    @Test
    void a_malformed_did_does_not_break_startup() throws Exception {
        companion("did:key:zNotRealBase58!!", "companion-ari", 0);
        companion(realDid, "companion-two", 0);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(jdbc, store))
            .as("the good one must still be recorded")
            .isEqualTo(1);
    }

    /** No companions table at all (fresh node) is not an error. */
    @Test
    void a_node_with_no_roster_is_fine() {
        var bare = "jdbc:sqlite:" + tmp.resolve("bare.db").toAbsolutePath();
        var bareStore = new AgentIdentityStore(bare);

        assertThat(AgentIdentityBootstrap.recordExistingCompanions(bare, bareStore)).isZero();
    }

    /**
     * The backfill must still see her as needing one — recording the public key
     * is a smaller thing than giving her a provable identity, and must not be
     * mistaken for it.
     */
    @Test
    void recording_does_not_satisfy_the_backfill() throws Exception {
        companion(realDid, "companion-ari", 0);
        AgentIdentityBootstrap.recordExistingCompanions(jdbc, store);

        var plan = AgentIdentityBackfill.plan(jdbc, realDid);

        assertThat(plan.notes()).anyMatch(n -> n.contains("cannot be recovered"));
        assertThat(plan.newDid()).isNull();
    }
}
