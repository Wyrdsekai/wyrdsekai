package org.wyrdsekai.between;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Wire format for all Between messages.
 * Signed with Ed25519 — signature covers src:dst:ts:payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BetweenEnvelope(
    int v,           // protocol version (1)
    String src,      // source node ID
    String dst,      // destination node ID (null for broadcast)
    Instant ts,      // sender timestamp
    String sig,      // base64 Ed25519 signature
    JsonNode payload // message-specific payload
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Create and sign an envelope.
     */
    public static BetweenEnvelope create(String src, String dst, JsonNode payload,
                                          NodeIdentity identity) {
        var ts = Instant.now();
        var sigData = signingData(src, dst, ts, payload);
        var sig = Base64.getEncoder().encodeToString(identity.sign(sigData));
        return new BetweenEnvelope(1, src, dst, ts, sig, payload);
    }

    /**
     * Verify this envelope's signature against a peer's public key.
     */
    public boolean verify(byte[] peerPublicKey) {
        var sigData = signingData(src, dst, ts, payload);
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
            return MAPPER.readValue(data, BetweenEnvelope.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize envelope", e);
        }
    }

    private static byte[] signingData(String src, String dst, Instant ts, JsonNode payload) {
        var dstStr = dst != null ? dst : "*";
        var payloadStr = payload.toString();
        var combined = src + ":" + dstStr + ":" + ts.toString() + ":" + payloadStr;
        return combined.getBytes(StandardCharsets.UTF_8);
    }
}
