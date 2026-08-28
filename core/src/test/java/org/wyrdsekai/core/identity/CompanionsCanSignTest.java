package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gap, stated as a test: a companion must be able to prove it owns its name.
 *
 * <p>Until 2026-08-08 it could not. {@code CompanionActor} ran
 * {@code DidKey.generate()}, put the public key in the manifest, and let the
 * private key go out of scope with the method. Live check on the household node:
 * no {@code agent_identities} table at all, {@code encryption_keys} empty. So a
 * companion held a {@code did:key:} — an identifier that <em>is</em> a public key
 * — and could not produce a single signature that key would verify. That is why
 * her rebind had to be witnessed by the steward rather than declared by her.</p>
 *
 * <p>The load-bearing assertion is {@link #the_did_actually_encodes_the_key_it_signs_with}:
 * a stored keypair that does not match its own DID would be worse than none,
 * because it fails only for a verifier who resolves the DID properly, and it
 * fails looking like tampering.</p>
 */
class CompanionsCanSignTest {

    @TempDir Path tmp;
    private String jdbc;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        AgentIdentityProvisioner.init(jdbc, () -> secret);
    }

    @AfterEach
    void tearDown() {
        AgentIdentityProvisioner.reset();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** THE case. */
    @Test
    void a_born_companion_can_sign_as_itself() {
        var minted = AgentIdentityProvisioner.mint("entity-ari");

        assertThat(minted.persisted())
            .as("the private half must be kept, not discarded at the end of the method")
            .isTrue();
        assertThat(AgentIdentityProvisioner.canSign(minted.did())).isTrue();

        var sig = AgentIdentityProvisioner.sign(minted.did(), bytes("I am who I say I am"));

        assertThat(sig).isPresent();
        assertThat(AgentIdentityProvisioner.verify(
            minted.did(), bytes("I am who I say I am"), sig.get())).isTrue();
    }

    /**
     * A did:key IS its public key. Storing a keypair that does not match makes
     * every signature fail for anyone who resolves the DID rather than trusting
     * our table — silently, and looking exactly like tampering.
     */
    @Test
    void the_did_actually_encodes_the_key_it_signs_with() throws Exception {
        var minted = AgentIdentityProvisioner.mint("entity-ari");
        var identity = AgentIdentityProvisioner.find(minted.did()).orElseThrow();

        assertThat(DidKey.fromRawPublicKey(identity.publicKey())).isEqualTo(minted.did());
        assertThat(minted.publicKeyMultibase())
            .isEqualTo(minted.did().substring("did:key:".length()));

        // And the private half really is the other half of that public key.
        var data = bytes("proof");
        var sig = identity.sign(data, secret);
        assertThat(identity.verify(data, sig)).isTrue();
    }

    /** A different companion's signature must not verify. */
    @Test
    void one_companion_cannot_speak_as_another() {
        var a = AgentIdentityProvisioner.mint("entity-one");
        var b = AgentIdentityProvisioner.mint("entity-two");

        assertThat(a.did()).isNotEqualTo(b.did());
        var sig = AgentIdentityProvisioner.sign(a.did(), bytes("it was me")).orElseThrow();

        assertThat(AgentIdentityProvisioner.verify(b.did(), bytes("it was me"), sig))
            .as("verifying against the wrong DID must fail")
            .isFalse();
    }

    /** Tampered content must not verify under a real signature. */
    @Test
    void a_signature_does_not_cover_different_bytes() {
        var minted = AgentIdentityProvisioner.mint("entity-ari");
        var sig = AgentIdentityProvisioner.sign(minted.did(), bytes("the original")).orElseThrow();

        assertThat(AgentIdentityProvisioner.verify(minted.did(), bytes("the edit"), sig))
            .isFalse();
    }

    /** The key survives a restart — it is in the database, not in a field. */
    @Test
    void the_key_survives_a_restart() {
        var minted = AgentIdentityProvisioner.mint("entity-ari");
        AgentIdentityProvisioner.reset();

        AgentIdentityProvisioner.init(jdbc, () -> secret);

        assertThat(AgentIdentityProvisioner.canSign(minted.did())).isTrue();
        var sig = AgentIdentityProvisioner.sign(minted.did(), bytes("still me"));
        assertThat(sig).isPresent();
        assertThat(AgentIdentityProvisioner.verify(minted.did(), bytes("still me"), sig.get()))
            .isTrue();
    }

    /**
     * Birth must never fail because provisioning is off — tests, offline tools,
     * and any node whose zone master is not installed yet. It degrades to the
     * old behaviour and SAYS so, rather than throwing on the birth path.
     */
    @Test
    void birth_still_works_with_provisioning_off() {
        AgentIdentityProvisioner.reset();

        var minted = AgentIdentityProvisioner.mint("entity-ari");

        assertThat(minted.did()).startsWith("did:key:z");
        assertThat(minted.persisted())
            .as("keyless, and honest about it — this is what a backfill later looks for")
            .isFalse();
        assertThat(AgentIdentityProvisioner.canSign(minted.did())).isFalse();
        assertThat(AgentIdentityProvisioner.sign(minted.did(), bytes("x"))).isEmpty();
    }

    /**
     * A wrong-sized household secret must not silently produce a keyless
     * companion that claims to be keyed.
     */
    @Test
    void a_bad_household_secret_degrades_honestly() {
        AgentIdentityProvisioner.reset();
        AgentIdentityProvisioner.init(jdbc, () -> new byte[16]);

        var minted = AgentIdentityProvisioner.mint("entity-ari");

        assertThat(minted.persisted()).isFalse();
        assertThat(AgentIdentityProvisioner.find(minted.did())).isEmpty();
    }

    /** The entityId→DID mapping is now in the database, not only in a file. */
    @Test
    void the_spawn_identity_resolves_without_the_file() {
        var minted = AgentIdentityProvisioner.mint("entity-ari");

        assertThat(AgentIdentityProvisioner.existingDidFor("entity-ari"))
            .as("a second witness to who this is — a stale file birthed a third "
                + "companion on 2026-08-08")
            .contains(minted.did());
        assertThat(AgentIdentityProvisioner.existingDidFor("entity-nobody")).isEmpty();
    }

    /** Signing as an unknown DID is empty, not an exception on a hot path. */
    @Test
    void an_unknown_did_signs_nothing() {
        assertThat(AgentIdentityProvisioner.sign("did:key:z6MkNeverSeen", bytes("x"))).isEmpty();
        assertThat(AgentIdentityProvisioner.canSign("did:key:z6MkNeverSeen")).isFalse();
        assertThat(AgentIdentityProvisioner.canSign(null)).isFalse();
    }
}
