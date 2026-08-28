package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table that did not exist.
 *
 * <p>Round-trip is the whole point, and the person-identity work of 2026-08-07
 * is why it gets this much attention: a Lucene document that survived a copy but
 * could not be searched afterwards was present-but-useless in a way nothing
 * reported. A key row that loses its KERI log, its delegation or — worst — its
 * encrypted private key would be the same failure with worse consequences: the
 * identity still resolves, still looks fine, and can no longer sign.</p>
 */
class AgentIdentityStoreTest {

    @TempDir Path tmp;
    private AgentIdentityStore store;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        store = new AgentIdentityStore(
            "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath());
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
    }

    private AgentIdentity generate() throws Exception {
        return AgentIdentity.generate(secret);
    }

    /** THE case: everything needed to sign again comes back. */
    @Test
    void a_stored_identity_round_trips() throws Exception {
        var original = generate();
        store.save(original, "entity-ari");

        var loaded = store.findByDid(original.did()).orElseThrow();

        assertThat(loaded.did()).isEqualTo(original.did());
        assertThat(loaded.publicKey()).isEqualTo(original.publicKey());
        assertThat(loaded.privateKeyEncrypted())
            .as("without this the row is a name, which is what we already had")
            .isEqualTo(original.privateKeyEncrypted());
        assertThat(loaded.keyLog())
            .as("KERI pre-rotation is what makes a future key change verifiable")
            .hasSameSizeAs(original.keyLog());
        assertThat(loaded.created().getEpochSecond())
            .isEqualTo(original.created().getEpochSecond());
    }

    /** And the round-tripped identity can actually sign — not just look intact. */
    @Test
    void the_loaded_identity_still_signs() throws Exception {
        var original = generate();
        store.save(original, "entity-ari");

        var loaded = store.findByDid(original.did()).orElseThrow();
        var data = "who I am".getBytes();

        assertThat(loaded.verify(data, loaded.sign(data, secret))).isTrue();
        assertThat(original.verify(data, loaded.sign(data, secret)))
            .as("same key, either way round")
            .isTrue();
    }

    /** Delegation is authority — losing it silently would widen or narrow it. */
    @Test
    void delegation_and_lineage_survive() throws Exception {
        var parent = generate();
        var child = parent.fork(secret,
            AgentIdentity.IdentityDelegation.of(DelegationLevel.READ_ONLY));
        store.save(child, "entity-child");

        var loaded = store.findByDid(child.did()).orElseThrow();

        assertThat(loaded.parentDid()).isEqualTo(parent.did());
        assertThat(loaded.delegation()).isNotNull();
        assertThat(loaded.delegation().level()).isEqualTo(DelegationLevel.READ_ONLY);
    }

    /**
     * A foreign agent holds its own key; we keep the public half so we can
     * verify what they sign. Keyless is a distinction, not a defect — and it
     * must be visible, because it is the difference between "we can act as this"
     * and "we can only check this".
     */
    @Test
    void a_keyless_identity_is_stored_and_marked() throws Exception {
        var ours = generate();
        var theirs = new AgentIdentity("did:key:z6MkAVisitorFromAnotherZone",
            new byte[32], null, List.of(), Instant.now(), null, null);
        store.save(ours, "entity-ours");
        store.save(theirs, null);

        assertThat(store.listDids()).hasSize(2);
        assertThat(store.listKeyless()).containsExactly(theirs.did());
        assertThat(store.canSign(ours.did())).isTrue();
        assertThat(store.canSign(theirs.did())).isFalse();
    }

    /** Saving twice must not duplicate or clobber. */
    @Test
    void save_is_idempotent_on_did() throws Exception {
        var identity = generate();
        store.save(identity, "entity-ari");
        store.save(identity, "entity-ari");

        assertThat(store.listDids()).containsExactly(identity.did());
    }

    /** The spawn-identity mapping, the second witness to who someone is. */
    @Test
    void an_identity_is_findable_by_entity_id() throws Exception {
        var identity = generate();
        store.save(identity, "entity-ari");

        assertThat(store.didForEntity("entity-ari")).contains(identity.did());
        assertThat(store.didForEntity("entity-someone-else")).isEmpty();
        assertThat(store.didForEntity(null)).isEmpty();
    }

    /** The promotion ceremony derives its entityId from the DID, so it links late. */
    @Test
    void an_entity_can_be_linked_after_minting() throws Exception {
        var identity = generate();
        store.save(identity, null);

        assertThat(store.linkEntity(identity.did(), "entity-promoted")).isTrue();
        assertThat(store.didForEntity("entity-promoted")).contains(identity.did());
    }

    /** An existing link is a fact about who this is — never overwritten silently. */
    @Test
    void linking_does_not_overwrite_an_existing_answer() throws Exception {
        var identity = generate();
        store.save(identity, "entity-original");

        assertThat(store.linkEntity(identity.did(), "entity-hijack")).isFalse();
        assertThat(store.didForEntity("entity-original")).contains(identity.did());
        assertThat(store.didForEntity("entity-hijack")).isEmpty();
    }

    /** Degenerate lookups must not throw on a boot path. */
    @Test
    void unknown_and_blank_lookups_are_empty() {
        assertThat(store.findByDid("did:key:z6MkNeverSeen")).isEmpty();
        assertThat(store.findByDid(null)).isEmpty();
        assertThat(store.findByDid("")).isEmpty();
        assertThat(store.exists("did:key:z6MkNeverSeen")).isFalse();
        assertThat(store.canSign("did:key:z6MkNeverSeen")).isFalse();
    }

    /** Re-opening the store must not wipe or fail on the existing table. */
    @Test
    void schema_init_is_idempotent() throws Exception {
        var identity = generate();
        store.save(identity, "entity-ari");

        var reopened = new AgentIdentityStore(
            "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath());

        assertThat(reopened.findByDid(identity.did())).isPresent();
    }
}
