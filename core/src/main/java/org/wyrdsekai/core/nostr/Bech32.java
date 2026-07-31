package org.wyrdsekai.core.nostr;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Bech32 encoder/decoder (BIP-173) — for Nostr {@code npub} / {@code nsec}
 * identifier strings.
 *
 * <p>This implementation uses the original bech32 constant (1), not bech32m.
 * Nostr identifiers use plain bech32.
 *
 * <p>Reference: <a href="https://github.com/bitcoin/bips/blob/master/bip-0173.mediawiki">BIP-173</a>.
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    private Bech32() {}

    /**
     * Encode a 32-byte pubkey/seckey for Nostr.
     * @param hrp human-readable prefix, e.g. "npub" or "nsec"
     * @param data32 the 32 raw bytes to encode
     * @return bech32 string like "npub1xxxxx..."
     */
    public static String encode32(String hrp, byte[] data32) {
        if (data32 == null || data32.length != 32) {
            throw new IllegalArgumentException("expected 32 bytes, got " + (data32 == null ? 0 : data32.length));
        }
        var data5 = convertBits(data32, 8, 5, true);
        return encodeRaw(hrp, data5);
    }

    /** Decode a Nostr bech32 string back to its 32 raw bytes + hrp. */
    public static Decoded decode32(String bech) {
        var d = decodeRaw(bech);
        var data8 = convertBits(d.data, 5, 8, false);
        if (data8.length != 32) {
            throw new IllegalArgumentException("expected 32 bytes after decode, got " + data8.length);
        }
        return new Decoded(d.hrp, data8);
    }

    public record Decoded(String hrp, byte[] data) {}

    // ───────────── core encode/decode ─────────────

    private static String encodeRaw(String hrp, byte[] data5) {
        var combined = new byte[data5.length + 6];
        System.arraycopy(data5, 0, combined, 0, data5.length);
        var checksum = createChecksum(hrp, data5);
        System.arraycopy(checksum, 0, combined, data5.length, 6);
        var sb = new StringBuilder(hrp.length() + 1 + combined.length);
        sb.append(hrp).append('1');
        for (var b : combined) sb.append(CHARSET.charAt(b));
        return sb.toString();
    }

    private static RawDecoded decodeRaw(String bech) {
        if (bech == null || bech.length() < 8 || bech.length() > 1023) {
            throw new IllegalArgumentException("bech32 length out of bounds");
        }
        var lower = bech.toLowerCase(Locale.ROOT);
        if (!lower.equals(bech) && !bech.toUpperCase(Locale.ROOT).equals(bech)) {
            throw new IllegalArgumentException("mixed case");
        }
        bech = lower;
        var sep = bech.lastIndexOf('1');
        if (sep < 1 || sep + 7 > bech.length()) {
            throw new IllegalArgumentException("invalid separator position");
        }
        var hrp = bech.substring(0, sep);
        var dataPart = bech.substring(sep + 1);
        var data5 = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int idx = CHARSET.indexOf(dataPart.charAt(i));
            if (idx < 0) throw new IllegalArgumentException("invalid char: " + dataPart.charAt(i));
            data5[i] = (byte) idx;
        }
        if (!verifyChecksum(hrp, data5)) {
            throw new IllegalArgumentException("checksum invalid");
        }
        var payload = new byte[data5.length - 6];
        System.arraycopy(data5, 0, payload, 0, payload.length);
        return new RawDecoded(hrp, payload);
    }

    private record RawDecoded(String hrp, byte[] data) {}

    private static int polymod(byte[] values) {
        int chk = 1;
        for (var v : values) {
            int b = (chk >>> 25) & 0xff;
            chk = ((chk & 0x1ffffff) << 5) ^ (v & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((b >>> i) & 1) == 1) chk ^= GEN[i];
            }
        }
        return chk;
    }

    private static byte[] hrpExpand(String hrp) {
        var ret = new byte[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            ret[i] = (byte) (hrp.charAt(i) >>> 5);
            ret[i + hrp.length() + 1] = (byte) (hrp.charAt(i) & 0x1f);
        }
        ret[hrp.length()] = 0;
        return ret;
    }

    private static boolean verifyChecksum(String hrp, byte[] data) {
        var combined = concat(hrpExpand(hrp), data);
        return polymod(combined) == 1;
    }

    private static byte[] createChecksum(String hrp, byte[] data) {
        var values = concat(hrpExpand(hrp), data, new byte[6]);
        int mod = polymod(values) ^ 1;
        var ret = new byte[6];
        for (int i = 0; i < 6; i++) {
            ret[i] = (byte) ((mod >>> (5 * (5 - i))) & 0x1f);
        }
        return ret;
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        var out = new ByteArrayOutputStream();
        int maxv = (1 << toBits) - 1;
        for (var b : data) {
            int v = b & 0xff;
            if ((v >>> fromBits) != 0) throw new IllegalArgumentException("input out of range");
            acc = (acc << fromBits) | v;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.write((acc >>> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc << (toBits - bits)) & maxv);
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("invalid padding");
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrs) {
        int total = 0;
        for (var a : arrs) total += a.length;
        var out = new byte[total];
        int p = 0;
        for (var a : arrs) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }
}
