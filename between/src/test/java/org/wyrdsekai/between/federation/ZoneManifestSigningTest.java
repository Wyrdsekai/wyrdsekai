package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneManifestSigningTest {

    private static KeyPair keyPair;
    private static KeyPair wrongKeyPair;

    @BeforeAll
    static void generateKeys() throws NoSuchAlgorithmException {
        var gen = KeyPairGenerator.getInstance("Ed25519");
        keyPair = gen.generateKeyPair();
        wrongKeyPair = gen.generateKeyPair();
    }

    @Test void sign_and_verify_round_trip() {
        var unsigned = new ZoneManifest(
            "zone-a", "Alpha Zone", "pubkey-a",
            "nats://host:4222", "http://host:8080", 25520,
            List.of("rooms", "agents"), Instant.parse("2026-03-01T00:00:00Z"));

        var signed = ZoneManifest.sign(unsigned, keyPair.getPrivate());

        assertThat(signed.signature()).isNotNull();
        assertThat(signed.verify(keyPair.getPublic())).isTrue();
        // All fields preserved
        assertThat(signed.zoneId()).isEqualTo("zone-a");
        assertThat(signed.zoneName()).isEqualTo("Alpha Zone");
        assertThat(signed.capabilities()).containsExactly("rooms", "agents");
    }

    @Test void verify_rejects_wrong_key() {
        var unsigned = new ZoneManifest(
            "zone-a", "Alpha Zone", "pubkey-a",
            "nats://host:4222", null, 25520,
            List.of(), Instant.parse("2026-03-01T00:00:00Z"));

        var signed = ZoneManifest.sign(unsigned, keyPair.getPrivate());
        assertThat(signed.verify(wrongKeyPair.getPublic())).isFalse();
    }

    @Test void verify_rejects_tampered_manifest() {
        var unsigned = new ZoneManifest(
            "zone-a", "Alpha Zone", "pubkey-a",
            "nats://host:4222", null, 25520,
            List.of("rooms"), Instant.parse("2026-03-01T00:00:00Z"));

        var signed = ZoneManifest.sign(unsigned, keyPair.getPrivate());

        // Tamper: change zoneName but keep the original signature
        var tampered = new ZoneManifest(
            signed.zoneId(), "Tampered Zone", signed.publicKey(),
            signed.natsUrl(), signed.httpUrl(), signed.arteryPort(),
            signed.capabilities(), signed.createdAt(), signed.signature());

        assertThat(tampered.verify(keyPair.getPublic())).isFalse();
    }

    @Test void unsigned_manifest_still_works() {
        var manifest = new ZoneManifest(
            "zone-b", "Beta Zone", "pubkey-b",
            null, null, 0, List.of(), Instant.now());

        assertThat(manifest.signature()).isNull();
        // verify returns false for unsigned (no signature to verify)
        assertThat(manifest.verify(keyPair.getPublic())).isFalse();
    }

    @Test void unsigned_manifest_backward_compatible_constructor() {
        // The 8-arg constructor should still work without signature
        var manifest = new ZoneManifest(
            "zone-c", "Charlie Zone", "pubkey-c",
            "nats://host:4222", "http://host:8080", 25520,
            List.of("rooms"), Instant.now());

        assertThat(manifest.zoneId()).isEqualTo("zone-c");
        assertThat(manifest.signature()).isNull();
    }
}
