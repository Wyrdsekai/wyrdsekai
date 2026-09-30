package org.wyrdsekai.between;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Wire format for all Between messages.
 * Signed with Ed25519 — signature covers src:dst:ts:payload.
 *
 * <p>{@code rawPayload} is the payload's JSON text exactly as it arrived (not part of the wire form).
 * The sender signs its payload's text; re-printing a parsed tree does not always give the same text
 * (a timestamp written as a decimal comes back as a double), so a received envelope is verified against
 * the text it carried.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BetweenEnvelope(
    int v,           // protocol version (1)
    String src,      // source node ID
    String dst,      // destination node ID (null for broadcast)
    Instant ts,      // sender timestamp
    String sig,      // base64 Ed25519 signature
    JsonNode payload, // message-specific payload
    @JsonIgnore String rawPayload
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** The six wire fields, read in one pass so {@code ts} keeps its full precision. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Wire(int v, String src, String dst, Instant ts, String sig, JsonNode payload) {}

    public BetweenEnvelope(int v, String src, String dst, Instant ts, String sig, JsonNode payload) {
        this(v, src, dst, ts, sig, payload, null);
    }

    /**
     * Create and sign an envelope.
     */
    public static BetweenEnvelope create(String src, String dst, JsonNode payload,
                                          NodeIdentity identity) {
        var ts = Instant.now();
        var sigData = signingData(src, dst, ts, payload.toString());
        var sig = Base64.getEncoder().encodeToString(identity.sign(sigData));
        return new BetweenEnvelope(1, src, dst, ts, sig, payload);
    }

    /**
     * Verify this envelope's signature against a peer's public key.
     */
    public boolean verify(byte[] peerPublicKey) {
        var text = rawPayload != null ? rawPayload : payload.toString();
        var sigData = signingData(src, dst, ts, text);
        var sigBytes = Base64.getDecoder().decode(sig);
        return NodeIdentity.verify(sigData, sigBytes, peerPublicKey);
    }

    /**
     * Serialize to JSON bytes.
     */
    public byte[] toBytes() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize envelope", e);
        }
    }

    /**
     * Deserialize from JSON bytes.
     */
    public static BetweenEnvelope fromBytes(byte[] data) {
        try {
            var w = MAPPER.readValue(data, Wire.class);
            return new BetweenEnvelope(w.v(), w.src(), w.dst(), w.ts(), w.sig(), w.payload(), rawPayloadOf(data));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize envelope", e);
        }
    }

    /** The top-level {@code payload} value's JSON text as it appears in {@code data}; null if absent. */
    private static String rawPayloadOf(byte[] data) {
        try (var p = MAPPER.getFactory().createParser(data)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                var name = p.currentName();
                p.nextToken();
                if ("payload".equals(name)) {
                    int start = (int) p.currentTokenLocation().getByteOffset();
                    p.skipChildren();
                    int end = (int) p.currentLocation().getByteOffset();
                    return new String(data, start, end - start, StandardCharsets.UTF_8);
                }
                p.skipChildren();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] signingData(String src, String dst, Instant ts, String payloadText) {
        var dstStr = dst != null ? dst : "*";
        var combined = src + ":" + dstStr + ":" + ts.toString() + ":" + payloadText;
        return combined.getBytes(StandardCharsets.UTF_8);
    }
}
