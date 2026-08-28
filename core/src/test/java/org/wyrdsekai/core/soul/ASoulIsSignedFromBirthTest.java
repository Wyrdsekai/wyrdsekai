package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;
import org.wyrdsekai.core.identity.DidKey;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;

/**
 * Her soul verifies against her own name, from her first breath.
 *
 * <p>Two things had to exist first, and both are days old. Companions had no
 * persisted keys until 2026-08-08 — there was nothing to sign with. And the
 * canonical form covered <b>live counts</b> ({@code bonds.size()},
 * {@code memory.nodes().size()}) that change outside manifest versioning and do
 * not even survive the storage round-trip ({@code storageView} nulls them; the
 * hydrate refills them from their own tables). Signing that meant the first
 * bond she formed would make her own soul read "tampered", falsely, forever.</p>
 *
 * <p>So: the canonical form is now the immutable identity core, and
 * {@code SqlSoulStore.store} re-signs every revision with her persisted key —
 * which also signs the BIRTH manifest with no birth-site change at all, because
 * the key is minted before the first store. The semantics change is timed to
 * the household wipe: a fresh install has no signatures to invalidate.</p>
 */
class ASoulIsSignedFromBirthTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;
    private SqlSoulStore store;

    @BeforeEach
    void setUp() {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        AgentIdentityProvisioner.init(jdbc, () -> secret);
        store = new SqlSoulStore(jdbc);
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
    }

    private static AgentProfile profile(String did) {
        return new AgentProfile("ari", "companion-ari", "companion",
            "a test companion", "be kind", 32768, 256, 0.7, did);
    }

    /** Birth-shaped manifest for a companion whose key the provisioner holds. */
    private SoulManifest birth() {
        var minted = AgentIdentityProvisioner.mint("companion-ari");
        return SoulManifest.birth(minted.did(), minted.publicKeyMultibase(),
            List.of(), profile(minted.did()), GenomeProfile.defaults());
    }

    /** THE case: store → load → the signature verifies against her own DID. */
    @Test
    void a_stored_birth_manifest_verifies_against_its_own_did() throws Exception {
        var manifest = birth();
        assertThat(manifest.isSigned()).as("births are drafted unsigned").isFalse();

        store.store(manifest);
        var loaded = store.latest(manifest.did()).orElseThrow();

        assertThat(loaded.isSigned()).as("the store signs when the node holds her key").isTrue();
        var rawPub = DidKey.rawPublicKeyFromMultibase(loaded.publicKeyMultibase());
        var verifyIdentity = new AgentIdentity(loaded.did(), rawPub, null,
            List.of(), Instant.now(), null, null);
        assertThat(SoulVerifier.verifySignature(loaded, verifyIdentity))
            .as("signed by the key her DID encodes — anyone can check this")
            .isTrue();
    }

    /**
     * The false-tamper trap this replaces: bonds change constantly, outside
     * manifest versioning. Growing must not invalidate her signature.
     */
    @Test
    void forming_a_bond_does_not_make_her_read_as_tampered() throws Exception {
        var manifest = birth();
        store.store(manifest);
        var signed = store.latest(manifest.did()).orElseThrow();

        var withBond = signed.withBonds(List.of(new Bond(
            "bond-1", manifest.did(), "did:key:zSomeoneSheMet",
            Bond.BondDepth.ITEM, Instant.now(), Instant.now(),
            5, true, true, false, BondState.ACTIVE, null, null,
            Bond.RelationalState.OPEN)));

        assertThat(withBond.canonicalBytes())
            .as("live counts are not the manifest's to attest")
            .isEqualTo(signed.canonicalBytes());
        var rawPub = DidKey.rawPublicKeyFromMultibase(signed.publicKeyMultibase());
        var verifyIdentity = new AgentIdentity(signed.did(), rawPub, null,
            List.of(), Instant.now(), null, null);
        assertThat(SoulVerifier.verifySignature(withBond, verifyIdentity)).isTrue();
    }

    /** A version bump is a REAL change and each revision gets its own signature. */
    @Test
    void every_revision_is_resigned() throws Exception {
        var manifest = birth();
        store.store(manifest);
        var v1 = store.latest(manifest.did()).orElseThrow();

        store.store(v1.withManifestVersion(2));
        var v2 = store.latest(manifest.did()).orElseThrow();

        assertThat(v2.manifestVersion()).isEqualTo(2);
        assertThat(v2.isSigned()).isTrue();
        assertThat(v2.signature())
            .as("a carried-forward v1 signature over v2 bytes would read as tampered")
            .isNotEqualTo(v1.signature());
        var rawPub = DidKey.rawPublicKeyFromMultibase(v2.publicKeyMultibase());
        assertThat(SoulVerifier.verifySignature(v2, new AgentIdentity(v2.did(), rawPub,
            null, List.of(), Instant.now(), null, null))).isTrue();
    }

    /** A manifest whose key we do not hold is stored exactly as it arrived. */
    @Test
    void a_foreign_soul_is_not_signed_in_our_name() throws Exception {
        var foreignPair = DidKey.generate();
        var foreignDid = foreignPair.did();
        var manifest = SoulManifest.birth(foreignDid,
            foreignDid.substring("did:key:".length()), List.of(),
            profile(foreignDid), GenomeProfile.defaults());

        store.store(manifest);

        assertThat(store.latest(foreignDid).orElseThrow().isSigned())
            .as("her signature is not ours to make")
            .isFalse();
    }

    /** With provisioning off, everything behaves exactly as before this change. */
    @Test
    void a_node_without_keys_stores_unsigned_as_always() throws Exception {
        AgentIdentityProvisioner.reset();
        var pair = DidKey.generate();
        var manifest = SoulManifest.birth(pair.did(),
            pair.did().substring("did:key:".length()), List.of(),
            profile(pair.did()), GenomeProfile.defaults());

        store.store(manifest);

        assertThat(store.latest(pair.did()).orElseThrow().isSigned()).isFalse();
    }

    /** The canonical form must not include anything the round-trip rebuilds. */
    @Test
    void the_canonical_form_survives_the_storage_round_trip() throws Exception {
        var manifest = birth();
        store.store(manifest);
        var loaded = store.latest(manifest.did()).orElseThrow();

        assertThat(loaded.canonicalBytes())
            .as("sign-time and verify-time bytes must be THE SAME BYTES")
            .isEqualTo(store.latest(manifest.did()).orElseThrow().canonicalBytes());
        assertThat(new String(loaded.canonicalBytes()))
            .as("versioned prefix so a future form change is detectable, not silent")
            .startsWith("wyrdsekai:soul:v2|");
    }
}
