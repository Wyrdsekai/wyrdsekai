package org.wyrdsekai.core.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sealed tunnel between phone and home (, W3): the primitives against
 * their RFC vectors, the protocol's refusals, and the fixed interop vector the phone apps match.
 */
class SealedTunnelTest {

    private static final HexFormat HEX = HexFormat.of();
    private static byte[] h(String s) { return HEX.parseHex(s.replace(" ", "")); }
    private static byte[] u(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    // RFC 7748 §6.1
    static final byte[] ALICE_PRIV = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
    static final byte[] ALICE_PUB = h("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
    static final byte[] BOB_PRIV = h("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
    static final byte[] BOB_PUB = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");

    @Test
    void x25519MatchesRfc7748() throws Exception {
        var shared = h("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
        assertThat(SealedTunnel.x25519(ALICE_PRIV, BOB_PUB)).isEqualTo(shared);
        assertThat(SealedTunnel.x25519(BOB_PRIV, ALICE_PUB)).isEqualTo(shared);
    }

    @Test
    void hkdfMatchesRfc5869() throws Exception {
        var prk = SealedTunnel.hkdfExtract(h("000102030405060708090a0b0c"), h("0b".repeat(22)));
        assertThat(prk).isEqualTo(h("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"));
        assertThat(SealedTunnel.hkdfExpand(prk, h("f0f1f2f3f4f5f6f7f8f9"), 42))
            .isEqualTo(h("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"));
    }

    @Test
    void chacha20Poly1305MatchesRfc8439() throws Exception {
        var key = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        var c = Cipher.getInstance("ChaCha20-Poly1305");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(h("070000004041424344454647")));
        c.updateAAD(h("50515253c0c1c2c3c4c5c6c7"));
        var out = c.doFinal(u("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."));
        assertThat(HEX.formatHex(out)).isEqualTo(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116"
            + "1ae10b594f09e26a7e902ecbd0600691");
    }

    @Test
    void phoneAndHomeTalkBothWays() throws Exception {
        var home = SealedTunnel.generate();
        var phone = SealedTunnel.generate();
        var accepted = SealedTunnel.accept(home, phone.pub(), "session-0123456789abcdef");
        var opened = SealedTunnel.complete(phone, home.pub(), accepted.zoneEphemeralPub(), "session-0123456789abcdef");
        for (int i = 0; i < 3; i++) {
            var up = opened.up().seal(u("{\"type\":\"say\",\"n\":" + i + "}"), u("s.up"));
            assertThat(new String(accepted.up().open(up, u("s.up")), StandardCharsets.UTF_8)).isEqualTo("{\"type\":\"say\",\"n\":" + i + "}");
            var down = accepted.down().seal(u("reply " + i), u("s.down"));
            assertThat(new String(opened.down().open(down, u("s.down")), StandardCharsets.UTF_8)).isEqualTo("reply " + i);
        }
    }

    @Test
    void framesOutOfOrderReplayedAlteredOrMovedAreRefused() throws Exception {
        var home = SealedTunnel.generate(); var phone = SealedTunnel.generate();
        var a = SealedTunnel.accept(home, phone.pub(), "session-0123456789abcdef");
        var o = SealedTunnel.complete(phone, home.pub(), a.zoneEphemeralPub(), "session-0123456789abcdef");
        var f0 = o.up().seal(u("zero"), u("s.up"));
        var f1 = o.up().seal(u("one"), u("s.up"));
        assertThatThrownBy(() -> a.up().open(f1, u("s.up"))).hasMessageContaining("out of order");
        var altered = f0.clone(); altered[20] ^= 1;
        assertThatThrownBy(() -> a.up().open(altered, u("s.up"))).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> a.up().open(f0, u("other.up"))).isInstanceOf(GeneralSecurityException.class);
        assertThat(a.up().open(f0, u("s.up"))).isEqualTo(u("zero"));
        assertThatThrownBy(() -> a.up().open(f0, u("s.up"))).hasMessageContaining("out of order");   // replay
        assertThat(a.up().open(f1, u("s.up"))).isEqualTo(u("one"));
    }

    @Test
    void aRelayWithoutTheHomesKeyCannotStandInForIt() throws Exception {
        var home = SealedTunnel.generate(); var relay = SealedTunnel.generate(); var phone = SealedTunnel.generate();
        // The relay answers the phone's opening key itself, with its own keys.
        var fake = SealedTunnel.accept(relay, phone.pub(), "session-0123456789abcdef");
        // The phone derives with the home's key it got at pairing, so the relay's frames do not open.
        var o = SealedTunnel.complete(phone, home.pub(), fake.zoneEphemeralPub(), "session-0123456789abcdef");
        var forged = fake.down().seal(u("{\"type\":\"welcome\"}"), u("s.down"));
        assertThatThrownBy(() -> o.down().open(forged, u("s.down"))).isInstanceOf(GeneralSecurityException.class);
        var fromPhone = o.up().seal(u("{\"token\":\"secret\"}"), u("s.up"));
        assertThatThrownBy(() -> fake.up().open(fromPhone, u("s.up"))).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void aLowOrderKeyIsRefused() {
        assertThatThrownBy(() -> SealedTunnel.x25519(ALICE_PRIV, new byte[32])).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void theInteropVectorHolds() throws Exception {
        var v = new ObjectMapper().readTree(getClass().getResourceAsStream("/crypto/tunnel-v2-vectors.json"));
        var zoneStatic = new SealedTunnel.KeyPair(h(v.get("zone_static_priv").asText()), h(v.get("zone_static_pub").asText()));
        var zoneEph = new SealedTunnel.KeyPair(h(v.get("zone_ephemeral_priv").asText()), h(v.get("zone_ephemeral_pub").asText()));
        var phoneEph = new SealedTunnel.KeyPair(h(v.get("phone_ephemeral_priv").asText()), h(v.get("phone_ephemeral_pub").asText()));
        var session = v.get("session").asText();
        var keys = SealedTunnel.derive(SealedTunnel.x25519(phoneEph.priv(), zoneStatic.pub()),
            SealedTunnel.x25519(phoneEph.priv(), zoneEph.pub()), session, phoneEph.pub(), zoneEph.pub());
        assertThat(HEX.formatHex(keys[0])).isEqualTo(v.get("k_up").asText());
        assertThat(HEX.formatHex(keys[1])).isEqualTo(v.get("k_down").asText());
        var a = SealedTunnel.accept(zoneStatic, zoneEph, phoneEph.pub(), session);
        var o = SealedTunnel.complete(phoneEph, zoneStatic.pub(), zoneEph.pub(), session);
        assertThat(HEX.formatHex(o.up().seal(u(v.get("up_plain").asText()), u(v.get("up_subject").asText())))).isEqualTo(v.get("up_sealed").asText());
        assertThat(HEX.formatHex(a.down().seal(u(v.get("down_plain").asText()), u(v.get("down_subject").asText())))).isEqualTo(v.get("down_sealed").asText());
        assertThat(a.up().open(h(v.get("up_sealed").asText()), u(v.get("up_subject").asText()))).isEqualTo(u(v.get("up_plain").asText()));
    }
}
