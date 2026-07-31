package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class NostrKeyTest {

    @Test void derive_is_deterministic_for_same_seed() {
        var seed = new byte[32];
        Arrays.fill(seed, (byte) 0x42);
        var a = NostrKey.deriveFromEd25519PrivateKey(seed);
        var b = NostrKey.deriveFromEd25519PrivateKey(seed);
        assertThat(a.pubKeyHex()).isEqualTo(b.pubKeyHex());
        assertThat(a.npub()).isEqualTo(b.npub());
    }

    @Test void derive_differs_for_different_seeds() {
        var s1 = new byte[32];
        var s2 = new byte[32];
        Arrays.fill(s1, (byte) 0x01);
        Arrays.fill(s2, (byte) 0x02);
        var k1 = NostrKey.deriveFromEd25519PrivateKey(s1);
        var k2 = NostrKey.deriveFromEd25519PrivateKey(s2);
        assertThat(k1.pubKeyHex()).isNotEqualTo(k2.pubKeyHex());
    }

    @Test void pubkey_is_32_bytes() {
        var k = NostrKey.generate();
        assertThat(k.xOnlyPubKeyBytes()).hasSize(32);
        assertThat(k.pubKeyHex()).hasSize(64);
    }

    @Test void npub_and_nsec_decode_back() {
        var k = NostrKey.generate();
        var npub = k.npub();
        assertThat(npub).startsWith("npub1");
        assertThat(NostrKey.decodeNpub(npub)).isEqualTo(k.xOnlyPubKeyBytes());

        var nsec = k.nsec();
        assertThat(nsec).startsWith("nsec1");
        var decodedNsec = Bech32.decode32(nsec);
        assertThat(decodedNsec.hrp()).isEqualTo("nsec");
    }

    @Test void fromHexPrivateKey_roundtrip() {
        var original = NostrKey.generate();
        // Reconstruct via hex of the scalar (private key bytes)
        var hex = HexFormat.of().formatHex(
            NostrKey.padTo32(original.privateScalar()));
        var rebuilt = NostrKey.fromHexPrivateKey(hex);
        assertThat(rebuilt.pubKeyHex()).isEqualTo(original.pubKeyHex());
    }

    @Test void derive_rejects_wrong_size_seed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> NostrKey.deriveFromEd25519PrivateKey(new byte[16]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
