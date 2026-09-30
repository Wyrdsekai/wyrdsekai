package org.wyrdsekai.core.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sealed requests between phone and home (, W3): the fixed interop vector the
 * phone apps match byte for byte, and the home's refusals.
 */
class SealedRequestTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUBJECT = "wyrd.zone.zone-example.mcp.login";
    private static final byte[] LOGIN = u("{\"username\":\"alice\",\"password\":\"correct horse battery\"}");

    private static byte[] h(String s) { return HEX.parseHex(s); }
    private static byte[] u(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String s(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    @Test
    void theInteropVectorHoldsByteForByte() throws Exception {
        var v = MAPPER.readTree(getClass().getResourceAsStream("/crypto/request-v2-vectors.json"));
        var zone = new SealedTunnel.KeyPair(h(v.get("zone_static_priv").asText()), h(v.get("zone_static_pub").asText()));
        var phone = new SealedTunnel.KeyPair(h(v.get("phone_ephemeral_priv").asText()), h(v.get("phone_ephemeral_pub").asText()));
        var n = h(v.get("n").asText());
        var subject = v.get("subject").asText();

        var dh = SealedTunnel.x25519(phone.priv(), zone.pub());
        assertThat(HEX.formatHex(dh)).isEqualTo(v.get("dh").asText());
        assertThat(HEX.formatHex(SealedTunnel.hkdfExtract(n, dh))).isEqualTo(v.get("prk").asText());
        var keys = SealedRequest.derive(dh, n, phone.pub());
        assertThat(HEX.formatHex(keys.req())).isEqualTo(v.get("k_req").asText());
        assertThat(HEX.formatHex(keys.rep())).isEqualTo(v.get("k_rep").asText());

        var inner = MAPPER.readTree(v.get("request_plain").asText());
        long ts = inner.get("ts").asLong();
        var body = MAPPER.writeValueAsBytes(inner.get("body"));
        var out = SealedRequest.seal(phone, n, zone.pub(), subject, body, ts);
        assertThat(s(out.wire())).isEqualTo(v.get("request_wire").asText());
        assertThat(HEX.formatHex(out.replyKey())).isEqualTo(v.get("k_rep").asText());
        assertThat(HEX.formatHex(TunnelKey.decodeKey(MAPPER.readTree(out.wire()).get("c").asText())))
            .isEqualTo(v.get("request_ct").asText());

        var opened = new SealedRequest.Home(zone, () -> ts).open(subject, u(v.get("request_wire").asText()));
        assertThat(s(opened.body())).isEqualTo(s(body));
        assertThat(opened.ts()).isEqualTo(ts);
        assertThat(HEX.formatHex(opened.replyKey())).isEqualTo(v.get("k_rep").asText());

        var reply = SealedRequest.sealReply(opened.replyKey(), subject, u(v.get("reply_plain").asText()));
        assertThat(s(reply)).isEqualTo(v.get("reply_wire").asText());
        assertThat(HEX.formatHex(TunnelKey.decodeKey(MAPPER.readTree(reply).get("c").asText())))
            .isEqualTo(v.get("reply_ct").asText());
        assertThat(s(out.openReply(u(v.get("reply_wire").asText())))).isEqualTo(v.get("reply_plain").asText());
    }

    @Test
    void phoneAndHomeRoundTrip() throws Exception {
        var zone = SealedTunnel.generate();
        var home = new SealedRequest.Home(zone);
        var out = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        assertThat(s(out.wire())).doesNotContain("alice").doesNotContain("correct horse");
        assertThat(SealedRequest.looksSealed(out.wire())).isTrue();

        var opened = home.open(SUBJECT, out.wire());
        assertThat(MAPPER.readTree(opened.body())).isEqualTo(MAPPER.readTree(LOGIN));
        var reply = SealedRequest.sealReply(opened.replyKey(), SUBJECT, u("{\"ok\":true,\"token\":\"secret-token\"}"));
        assertThat(s(reply)).doesNotContain("secret-token");
        assertThat(s(out.openReply(reply))).isEqualTo("{\"ok\":true,\"token\":\"secret-token\"}");
    }

    @Test
    void anEmptyRequestTravelsAsAnEmptyObject() throws Exception {
        var zone = SealedTunnel.generate();
        var out = SealedRequest.seal(zone.pub(), "wyrd.zone.z.auth.status", new byte[0]);
        assertThat(s(new SealedRequest.Home(zone).open("wyrd.zone.z.auth.status", out.wire()).body())).isEqualTo("{}");
    }

    @Test
    void plaintextRequestsAreNotMistakenForSealedOnes() {
        assertThat(SealedRequest.looksSealed(LOGIN)).isFalse();
        assertThat(SealedRequest.looksSealed(new byte[0])).isFalse();
        assertThat(SealedRequest.looksSealed(null)).isFalse();
        assertThat(SealedRequest.looksSealed(u("not json"))).isFalse();
    }

    @Test
    void aTamperedCiphertextIsRefused() throws Exception {
        var zone = SealedTunnel.generate();
        var out = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        var w = (ObjectNode) MAPPER.readTree(out.wire());
        var c = TunnelKey.decodeKey(w.get("c").asText());
        c[5] ^= 1;
        w.put("c", Base64.getUrlEncoder().withoutPadding().encodeToString(c));
        assertThatThrownBy(() -> new SealedRequest.Home(zone).open(SUBJECT, MAPPER.writeValueAsBytes(w)))
            .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void aRequestMovedToAnotherSubjectIsRefused() throws Exception {
        var zone = SealedTunnel.generate();
        var out = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        assertThatThrownBy(() -> new SealedRequest.Home(zone).open("wyrd.zone.zone-example.mcp.tell", out.wire()))
            .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void aStaleOrFutureRequestIsRefused() throws Exception {
        var zone = SealedTunnel.generate();
        var now = System.currentTimeMillis();
        var clock = new AtomicLong(now + SealedRequest.MAX_SKEW_MS + 1_000);
        var home = new SealedRequest.Home(zone, clock::get);
        var old = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        assertThatThrownBy(() -> home.open(SUBJECT, old.wire())).hasMessageContaining("stale");
        clock.set(now - SealedRequest.MAX_SKEW_MS - 1_000);
        var ahead = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        assertThatThrownBy(() -> home.open(SUBJECT, ahead.wire())).hasMessageContaining("stale");
        clock.set(now + 60_000);
        assertThat(home.open(SUBJECT, SealedRequest.seal(zone.pub(), SUBJECT, LOGIN).wire()).body()).isNotEmpty();
    }

    @Test
    void aReplayedRequestIsRefused() throws Exception {
        var zone = SealedTunnel.generate();
        var home = new SealedRequest.Home(zone);
        var out = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        home.open(SUBJECT, out.wire());
        assertThatThrownBy(() -> home.open(SUBJECT, out.wire())).hasMessageContaining("replayed");
        assertThat(home.open(SUBJECT, SealedRequest.seal(zone.pub(), SUBJECT, LOGIN).wire()).body()).isNotEmpty();
    }

    @Test
    void theReplayCacheForgetsAfterTenMinutes() throws Exception {
        var zone = SealedTunnel.generate();
        var n = new byte[SealedRequest.N_LEN];
        var start = 1_790_000_000_000L;
        var clock = new AtomicLong(start);
        var home = new SealedRequest.Home(zone, clock::get);
        home.open(SUBJECT, SealedRequest.seal(SealedTunnel.generate(), n, zone.pub(), SUBJECT, LOGIN, start).wire());
        clock.set(start + SealedRequest.REPLAY_WINDOW_MS / 2);
        var sameN = SealedRequest.seal(SealedTunnel.generate(), n, zone.pub(), SUBJECT, LOGIN, clock.get());
        assertThatThrownBy(() -> home.open(SUBJECT, sameN.wire())).hasMessageContaining("replayed");
        clock.set(start + SealedRequest.REPLAY_WINDOW_MS + 1);
        var later = SealedRequest.seal(SealedTunnel.generate(), n, zone.pub(), SUBJECT, LOGIN, clock.get());
        assertThat(home.open(SUBJECT, later.wire()).body()).isNotEmpty();
    }

    @Test
    void aRelayWithoutTheHomesKeyCanNeitherReadNorAnswer() throws Exception {
        var zone = SealedTunnel.generate();
        var relay = SealedTunnel.generate();
        var out = SealedRequest.seal(zone.pub(), SUBJECT, LOGIN);
        assertThatThrownBy(() -> new SealedRequest.Home(relay).open(SUBJECT, out.wire())).isInstanceOf(GeneralSecurityException.class);
        // A reply it makes up, sealed or not, does not open on the phone.
        var forged = SealedRequest.sealReply(SealedTunnel.generate().pub(), SUBJECT, u("{\"ok\":true,\"token\":\"x\"}"));
        assertThatThrownBy(() -> out.openReply(forged)).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> out.openReply(u("{\"ok\":true,\"token\":\"x\"}"))).hasMessageContaining("not sealed");
        assertThatThrownBy(() -> out.openReply(u("{\"ok\":false,\"error\":\"sealed_refused\"}"))).hasMessageContaining("sealed_refused");
    }

    @Test
    void malformedSealedRequestsAreRefused() {
        var home = new SealedRequest.Home(new SealedTunnel.KeyPair(new byte[32], new byte[32]));
        assertThatThrownBy(() -> home.open(SUBJECT, u("{\"v\":2}"))).hasMessageContaining("malformed");
        assertThatThrownBy(() -> home.open(SUBJECT, u("{\"v\":2,\"e\":\"AAAA\",\"n\":\"AAAA\",\"c\":\"AAAA\"}"))).hasMessageContaining("malformed");
        assertThatThrownBy(() -> home.open(SUBJECT, LOGIN)).hasMessageContaining("not a sealed request");
    }
}
