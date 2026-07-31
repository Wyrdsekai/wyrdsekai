package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.AgentIdentity;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionSeedResolverTest {

    private static byte[] randSecret() {
        var s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @Test void resolves_real_identity_via_household_secret() throws Exception {
        var secret = randSecret();
        var identity = AgentIdentity.generate(secret);

        var resolver = CompanionSeedResolver.forTest(
            did -> did.equals(identity.did()) ? identity : null,
            secret);

        var seed = resolver.seedForDid(identity.did());
        assertThat(seed).isNotNull();
        assertThat(seed).hasSize(32);

        // The seed should be the raw Ed25519 private-key seed (32 bytes).
        // Same identity should always derive the same Nostr key.
        var k1 = NostrKey.deriveFromEd25519PrivateKey(seed);
        var k2 = NostrKey.deriveFromEd25519PrivateKey(seed);
        assertThat(k1.pubKeyHex()).isEqualTo(k2.pubKeyHex());
    }

    @Test void returns_null_for_unknown_did() throws Exception {
        var secret = randSecret();
        var identity = AgentIdentity.generate(secret);

        var resolver = CompanionSeedResolver.forTest(
            did -> did.equals(identity.did()) ? identity : null,
            secret);

        assertThat(resolver.seedForDid("did:key:zUnknown")).isNull();
        assertThat(resolver.seedForDid(null)).isNull();
        assertThat(resolver.seedForDid("")).isNull();
    }

    @Test void returns_null_when_household_secret_missing() throws Exception {
        var secret = randSecret();
        var identity = AgentIdentity.generate(secret);

        var resolver = new CompanionSeedResolver(
            did -> did.equals(identity.did()) ? identity : null,
            () -> null);   // simulates Safe not unlocked

        assertThat(resolver.seedForDid(identity.did())).isNull();
    }

    @Test void returns_null_when_household_secret_wrong_size() throws Exception {
        var secret = randSecret();
        var identity = AgentIdentity.generate(secret);

        var resolver = new CompanionSeedResolver(
            did -> identity, () -> new byte[16]);   // wrong length
        assertThat(resolver.seedForDid(identity.did())).isNull();
    }

    @Test void returns_null_when_household_secret_wrong() throws Exception {
        var realSecret = randSecret();
        var wrongSecret = randSecret();
        var identity = AgentIdentity.generate(realSecret);

        var resolver = CompanionSeedResolver.forTest(
            did -> identity, wrongSecret);
        // Decryption fails with the wrong secret → null
        assertThat(resolver.seedForDid(identity.did())).isNull();
    }

    @Test void foreign_identity_without_encrypted_key_returns_null() {
        // Foreign agents hold their own private key — we only have the public part.
        var pubOnly = new AgentIdentity(
            "did:key:zForeign",
            new byte[32],
            null,            // privateKeyEncrypted is null
            List.of(),
            Instant.now(),
            null,
            null);

        var resolver = CompanionSeedResolver.forTest(did -> pubOnly, randSecret());
        assertThat(resolver.seedForDid("did:key:zForeign")).isNull();
    }
}
