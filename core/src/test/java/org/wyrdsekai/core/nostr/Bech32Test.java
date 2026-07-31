package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Bech32Test {

    /**
     * Decode a real-world npub (jb55's, often referenced in NIP-19 docs) and
     * check the resulting 32-byte pubkey matches the published hex. Confirms
     * checksum + base32-to-base256 conversion against the wider ecosystem.
     */
    @Test void npub_decodes_to_known_pubkey() {
        var npub = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m";
        var d = Bech32.decode32(npub);
        assertThat(d.hrp()).isEqualTo("npub");
        assertThat(HexFormat.of().formatHex(d.data()))
            .isEqualTo("82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2");
    }

    @Test void npub_roundtrip_for_random_32_bytes() {
        var bytes = HexFormat.of().parseHex(
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        var encoded = Bech32.encode32("npub", bytes);
        var decoded = Bech32.decode32(encoded);
        assertThat(decoded.hrp()).isEqualTo("npub");
        assertThat(decoded.data()).isEqualTo(bytes);
    }

    @Test void encode_rejects_non_32_bytes() {
        assertThatThrownBy(() -> Bech32.encode32("npub", new byte[31]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void decode_rejects_bad_checksum() {
        // Take a valid npub and corrupt one char
        var good = "npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m";
        var bad = good.substring(0, good.length() - 1) + "x";
        assertThatThrownBy(() -> Bech32.decode32(bad))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nsec_hrp_works() {
        var bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0xab);
        var nsec = Bech32.encode32("nsec", bytes);
        assertThat(nsec).startsWith("nsec1");
        assertThat(Bech32.decode32(nsec).data()).isEqualTo(bytes);
    }
}
