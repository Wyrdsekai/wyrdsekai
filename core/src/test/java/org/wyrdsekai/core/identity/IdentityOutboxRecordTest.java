package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IdentityOutboxRecord} — sign/verify roundtrip, canonical bytes,
 * wire-format JSON, and tamper detection.
 */
class IdentityOutboxRecordTest {

    @Test void sign_then_verify_roundtrip() throws Exception {
        var kp = DidKey.generate();
        var record = IdentityOutboxRecord.sign(
            kp.did(),
            "alice",
            "alpha",
            List.of("alpha", "beta"),
            List.of("alpha"),
            List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1xxx")),
            1700000000_000L,
            kp.keyPair().getPrivate());

        assertThat(record.did()).isEqualTo(kp.did());
        assertThat(record.sig()).isNotBlank();
        assertThat(record.verify()).isTrue();
    }

    @Test void verify_rejects_tampered_displayname() throws Exception {
        var kp = DidKey.generate();
        var original = IdentityOutboxRecord.sign(
            kp.did(), "alice", "alpha", List.of("alpha"), List.of("alpha"),
            List.of(), 1700000000_000L, kp.keyPair().getPrivate());

        // Same sig, different displayName → must fail verification
        var tampered = new IdentityOutboxRecord(
            original.did(), "mallory", original.primaryZone(),
            original.writeZones(), original.readZones(),
            original.channels(), original.updatedAt(),
            original.sig());

        assertThat(tampered.verify()).isFalse();
    }

    @Test void verify_rejects_tampered_updatedAt() throws Exception {
        var kp = DidKey.generate();
        var original = IdentityOutboxRecord.sign(
            kp.did(), "alice", "alpha", List.of("alpha"), List.of("alpha"),
            List.of(), 1700000000_000L, kp.keyPair().getPrivate());

        var tampered = new IdentityOutboxRecord(
            original.did(), original.displayName(), original.primaryZone(),
            original.writeZones(), original.readZones(),
            original.channels(), original.updatedAt() + 1,
            original.sig());

        assertThat(tampered.verify()).isFalse();
    }

    @Test void verify_rejects_wrong_did() throws Exception {
        var kpAlice = DidKey.generate();
        var kpMallory = DidKey.generate();
        // Sign with Mallory's key but claim Alice's DID
        var forged = IdentityOutboxRecord.sign(
            kpAlice.did(),
            "alice", "alpha", List.of("alpha"), List.of("alpha"),
            List.of(), 1700000000_000L, kpMallory.keyPair().getPrivate());

        // verify() resolves the pubkey from the DID (Alice's), so Mallory's
        // signature won't validate.
        assertThat(forged.verify()).isFalse();
    }

    @Test void verify_rejects_blank_signature() throws Exception {
        var kp = DidKey.generate();
        var record = new IdentityOutboxRecord(
            kp.did(), "alice", "alpha", List.of("alpha"), List.of("alpha"),
            List.of(), 1700000000_000L, "");
        assertThat(record.verify()).isFalse();
    }

    @Test void wire_json_roundtrip_preserves_record() throws Exception {
        var kp = DidKey.generate();
        var record = IdentityOutboxRecord.sign(
            kp.did(), "alice", "alpha",
            List.of("alpha", "beta"),
            List.of("alpha"),
            List.of(new IdentityOutboxRecord.ChannelRef("nostr", "npub1xxx"),
                    new IdentityOutboxRecord.ChannelRef("matrix", "@alice:example.com")),
            1700000000_000L, kp.keyPair().getPrivate());

        var json = record.toWireJson();
        var parsed = IdentityOutboxRecord.fromWireJson(json);
        assertThat(parsed).isPresent();
        var p = parsed.get();
        assertThat(p.did()).isEqualTo(record.did());
        assertThat(p.displayName()).isEqualTo(record.displayName());
        assertThat(p.writeZones()).isEqualTo(record.writeZones());
        assertThat(p.readZones()).isEqualTo(record.readZones());
        assertThat(p.channels()).isEqualTo(record.channels());
        assertThat(p.updatedAt()).isEqualTo(record.updatedAt());
        assertThat(p.sig()).isEqualTo(record.sig());
        assertThat(p.verify()).isTrue();
    }

    @Test void fromWireJson_handles_malformed() {
        assertThat(IdentityOutboxRecord.fromWireJson(null)).isEmpty();
        assertThat(IdentityOutboxRecord.fromWireJson("")).isEmpty();
        assertThat(IdentityOutboxRecord.fromWireJson("not json {{")).isEmpty();
    }

    @Test void empty_channels_signs_and_verifies() throws Exception {
        var kp = DidKey.generate();
        var record = IdentityOutboxRecord.sign(
            kp.did(), "alice", "alpha",
            List.of("alpha"), List.of("alpha"),
            null,   // null channels → coerced to empty list
            1700000000_000L, kp.keyPair().getPrivate());

        assertThat(record.channels()).isEmpty();
        assertThat(record.verify()).isTrue();
    }
}
