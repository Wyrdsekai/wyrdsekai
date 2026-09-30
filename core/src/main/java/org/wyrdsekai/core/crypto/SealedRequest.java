package org.wyrdsekai.core.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/**
 * Sealed requests (v2): the phone's one-shot request/reply subjects ({@code mcp.login}, {@code mcp.tell},
 * {@code study.journal}, ...) encrypted end to end between phone and home, so a relay in between only
 * routes (, W3, "sealed requests").
 *
 * <p>Noise N in spirit: the phone knows the home's static X25519 key {@code zk} from pairing (the same
 * key as the sealed tunnel, {@link TunnelKey}). Per request it makes an ephemeral key {@code e} and 16
 * random bytes {@code n};
 * {@code HKDF-SHA256(salt = n, ikm = X25519(e, zk), info = "wyrd-request-v2" || e.pub)} gives
 * {@code k_req || k_rep}. The request {@code {"ts":..,"body":..}} is ChaCha20-Poly1305 under
 * {@code k_req}, the reply under {@code k_rep}, both with an all-zero nonce (each key seals exactly one
 * message) and the request subject as associated data. The home refuses a request it cannot open, one
 * whose {@code ts} is more than 120 s from its clock, and one whose {@code n} it has seen in the last
 * 10 minutes.</p>
 */
public final class SealedRequest {

    public static final int VERSION = 2;
    /** The home's plain answer to a sealed request it would not open. No detail, on purpose. */
    public static final String REFUSED = "sealed_refused";
    /** The home's plain answer to an unsealed request where sealing is required (the relay). */
    public static final String REQUIRED = "sealed_required";

    static final byte[] INFO = "wyrd-request-v2".getBytes(StandardCharsets.UTF_8);
    static final int N_LEN = 16;
    public static final long MAX_SKEW_MS = 120_000;
    public static final long REPLAY_WINDOW_MS = 600_000;
    /** Past this many answered requests in ten minutes the home refuses more rather than forget one. */
    static final int MAX_REMEMBERED = 100_000;

    private static final byte[] ZERO_NONCE = new byte[SealedTunnel.NONCE_LEN];
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SealedRequest() {}

    record Keys(byte[] req, byte[] rep) {}

    static Keys derive(byte[] dh, byte[] n, byte[] ephemeralPub) throws GeneralSecurityException {
        var prk = SealedTunnel.hkdfExtract(n, dh);
        var info = ByteBuffer.allocate(INFO.length + ephemeralPub.length).put(INFO).put(ephemeralPub).array();
        var okm = SealedTunnel.hkdfExpand(prk, info, 2 * SealedTunnel.KEY_LEN);
        return new Keys(Arrays.copyOfRange(okm, 0, SealedTunnel.KEY_LEN),
            Arrays.copyOfRange(okm, SealedTunnel.KEY_LEN, 2 * SealedTunnel.KEY_LEN));
    }

    // ── the phone's side (the phone apps do the same; here for the CLI and the tests) ──

    /** A sealed request ready to send, and what its reply opens with. */
    public record Outgoing(String subject, byte[] wire, byte[] replyKey) {
        public byte[] openReply(byte[] replyWire) throws GeneralSecurityException {
            return SealedRequest.openReply(replyKey, subject, replyWire);
        }
    }

    /** Seals {@code bodyJson} (the old plaintext request object) for {@code subject} to the home's key {@code zk}. */
    public static Outgoing seal(byte[] zoneStaticPub, String subject, byte[] bodyJson) throws GeneralSecurityException {
        var n = new byte[N_LEN];
        RANDOM.nextBytes(n);
        return seal(SealedTunnel.generate(), n, zoneStaticPub, subject, bodyJson, System.currentTimeMillis());
    }

    static Outgoing seal(SealedTunnel.KeyPair ephemeral, byte[] n, byte[] zoneStaticPub, String subject,
                         byte[] bodyJson, long ts) throws GeneralSecurityException {
        var keys = derive(SealedTunnel.x25519(ephemeral.priv(), zoneStaticPub), n, ephemeral.pub());
        var body = bodyJson == null || bodyJson.length == 0 ? "{}" : new String(bodyJson, StandardCharsets.UTF_8);
        var plain = ("{\"ts\":" + ts + ",\"body\":" + body + "}").getBytes(StandardCharsets.UTF_8);
        var c = aead(Cipher.ENCRYPT_MODE, keys.req(), subject, plain);
        var wire = "{\"v\":" + VERSION + ",\"e\":\"" + b64(ephemeral.pub()) + "\",\"n\":\"" + b64(n)
            + "\",\"c\":\"" + b64(c) + "\"}";
        return new Outgoing(subject, wire.getBytes(StandardCharsets.UTF_8), keys.rep());
    }

    /** Opens the home's sealed reply. An unsealed reply (a refusal, or anyone else's answer) throws. */
    public static byte[] openReply(byte[] replyKey, String subject, byte[] replyWire) throws GeneralSecurityException {
        var w = parse(replyWire);
        var c = w == null ? null : TunnelKey.decodeKey(w.path("c").asText(null));
        if (w == null || w.path("v").asInt(0) != VERSION || c == null) {
            var error = w == null ? "" : w.path("error").asText("");
            throw new GeneralSecurityException("the reply was not sealed" + (error.isEmpty() ? "" : " (" + error + ")"));
        }
        return aead(Cipher.DECRYPT_MODE, replyKey, subject, c);
    }

    // ── the home's side ──

    /** True when a request has the sealed shape ({@code {"v":2,...}}); anything else is a plaintext request. */
    public static boolean looksSealed(byte[] data) {
        var w = parse(data);
        return w != null && w.path("v").asInt(0) == VERSION;
    }

    /** Seals the home's reply JSON for the request it answers. */
    public static byte[] sealReply(byte[] replyKey, String subject, byte[] replyJson) throws GeneralSecurityException {
        var c = aead(Cipher.ENCRYPT_MODE, replyKey, subject, replyJson);
        return ("{\"v\":" + VERSION + ",\"c\":\"" + b64(c) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** An opened request: the old plaintext request object, and the key its reply is sealed with. */
    public record Opened(byte[] body, long ts, byte[] replyKey) {}

    /**
     * The home: its static key and the {@code n} values it has answered in the last ten minutes. One per
     * home, shared by every bus it answers on, so a request taken off the relay cannot be replayed on
     * another leg.
     */
    public static final class Home {
        private final SealedTunnel.KeyPair zoneStatic;
        private final LongSupplier clock;
        private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

        public Home(SealedTunnel.KeyPair zoneStatic) {
            this(zoneStatic, System::currentTimeMillis);
        }

        Home(SealedTunnel.KeyPair zoneStatic, LongSupplier clock) {
            this.zoneStatic = zoneStatic;
            this.clock = clock;
        }

        /** Opens a sealed request sent on {@code subject}; one that is forged, altered, moved, stale or replayed throws. */
        public Opened open(String subject, byte[] wire) throws GeneralSecurityException {
            var w = parse(wire);
            if (w == null || w.path("v").asInt(0) != VERSION) throw new GeneralSecurityException("not a sealed request");
            var e = TunnelKey.decodeKey(w.path("e").asText(null));
            var n = TunnelKey.decodeKey(w.path("n").asText(null));
            var c = TunnelKey.decodeKey(w.path("c").asText(null));
            if (e == null || e.length != SealedTunnel.KEY_LEN || n == null || n.length != N_LEN
                    || c == null || c.length < SealedTunnel.TAG_LEN) {
                throw new GeneralSecurityException("malformed sealed request");
            }
            var keys = derive(SealedTunnel.x25519(zoneStatic.priv(), e), n, e);
            var inner = parse(aead(Cipher.DECRYPT_MODE, keys.req(), subject, c));
            var ts = inner == null ? null : inner.get("ts");
            if (ts == null || !ts.isIntegralNumber()) throw new GeneralSecurityException("sealed request without a time");
            long now = clock.getAsLong();
            if (Math.abs(now - ts.asLong()) > MAX_SKEW_MS) {
                throw new GeneralSecurityException("stale sealed request (" + (now - ts.asLong()) / 1000 + " s from this clock)");
            }
            remember(b64(n), now);
            var body = inner.get("body");
            try {
                return new Opened(body == null || body.isNull() ? "{}".getBytes(StandardCharsets.UTF_8)
                    : MAPPER.writeValueAsBytes(body), ts.asLong(), keys.rep());
            } catch (IOException ex) {
                throw new GeneralSecurityException("sealed request body unreadable", ex);
            }
        }

        private synchronized void remember(String n, long now) throws GeneralSecurityException {
            for (var it = seen.values().iterator(); it.hasNext(); ) {
                if (now - it.next() > REPLAY_WINDOW_MS) it.remove();
                else break;
            }
            if (seen.containsKey(n)) throw new GeneralSecurityException("replayed sealed request");
            if (seen.size() >= MAX_REMEMBERED) throw new GeneralSecurityException("too many sealed requests in ten minutes");
            seen.put(n, now);
        }
    }

    // ── primitives ──

    private static byte[] aead(int mode, byte[] key, String subject, byte[] input) throws GeneralSecurityException {
        var c = SealedTunnel.cipher(mode, key, ZERO_NONCE);
        c.updateAAD(subject.getBytes(StandardCharsets.UTF_8));
        return c.doFinal(input);
    }

    private static JsonNode parse(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            var node = MAPPER.readTree(data);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
