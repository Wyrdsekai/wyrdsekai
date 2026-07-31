package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NostrEventTest {

    @Test void build_sign_verify_roundtrip() {
        var key = NostrKey.generate();
        var event = NostrEvent.buildAndSign(
            key, 1,
            List.of(List.of("t", "wyrdsekai")),
            "hello from a companion",
            1700000000L);

        assertThat(event.id()).hasSize(64);   // hex sha256
        assertThat(event.pubkey()).isEqualTo(key.pubKeyHex());
        assertThat(event.sig()).hasSize(128); // 64-byte sig as hex
        assertThat(event.kind()).isEqualTo(1);
        assertThat(event.verify()).isTrue();
    }

    @Test void verify_rejects_tampered_content() {
        var key = NostrKey.generate();
        var ok = NostrEvent.buildAndSign(key, 1, List.of(), "original", 1700000000L);
        var tampered = new NostrEvent(
            ok.id(), ok.pubkey(), ok.createdAt(), ok.kind(),
            ok.tags(), "tampered", ok.sig());
        assertThat(tampered.verify()).isFalse();
    }

    @Test void verify_rejects_tampered_id() {
        var key = NostrKey.generate();
        var ok = NostrEvent.buildAndSign(key, 1, List.of(), "x", 1700000000L);
        var tampered = new NostrEvent(
            "0".repeat(64), ok.pubkey(), ok.createdAt(), ok.kind(),
            ok.tags(), ok.content(), ok.sig());
        assertThat(tampered.verify()).isFalse();
    }

    @Test void verify_rejects_wrong_pubkey() {
        var alice = NostrKey.generate();
        var bob = NostrKey.generate();
        var ev = NostrEvent.buildAndSign(alice, 1, List.of(), "x", 1700000000L);
        // Claim Bob's pubkey on Alice's event — id is recomputed against Bob's
        // pubkey and will mismatch the stored id, then the sig wouldn't match
        // either. Either failure mode is acceptable; we just need verify=false.
        var forged = new NostrEvent(
            ev.id(), bob.pubKeyHex(), ev.createdAt(), ev.kind(),
            ev.tags(), ev.content(), ev.sig());
        assertThat(forged.verify()).isFalse();
    }

    @Test void relay_publish_frame_is_2_element_array() {
        var key = NostrKey.generate();
        var ev = NostrEvent.buildAndSign(key, 1, List.of(), "hi", 1700000000L);
        var frame = ev.toRelayPublishFrame();
        assertThat(frame).startsWith("[\"EVENT\",");
        assertThat(frame).endsWith("]");
    }

    @Test void event_with_empty_tags_signs_and_verifies() {
        var key = NostrKey.generate();
        var ev = NostrEvent.buildAndSign(key, 0, null, "", 1700000000L);
        assertThat(ev.tags()).isEmpty();
        assertThat(ev.verify()).isTrue();
    }

    @Test void from_json_roundtrip() {
        var key = NostrKey.generate();
        var original = NostrEvent.buildAndSign(
            key, 1, List.of(List.of("e", "abc"), List.of("p", "def")),
            "content here", 1700000000L);
        var json = original.toJson();
        var parsed = NostrEvent.fromJson(json).orElseThrow();
        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.pubkey()).isEqualTo(original.pubkey());
        assertThat(parsed.tags()).isEqualTo(original.tags());
        assertThat(parsed.verify()).isTrue();
    }

    @Test void deterministic_key_produces_verifiable_event() {
        var seed = new byte[32];
        Arrays.fill(seed, (byte) 0x77);
        var key = NostrKey.deriveFromEd25519PrivateKey(seed);
        var ev = NostrEvent.buildAndSign(key, 1, List.of(), "deterministic", 1700000000L);
        assertThat(ev.verify()).isTrue();
        // Same seed → same pubkey → same npub
        var rebuilt = NostrKey.deriveFromEd25519PrivateKey(seed);
        assertThat(ev.pubkey()).isEqualTo(rebuilt.pubKeyHex());
    }
}
