package org.wyrdsekai.core.nostr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * NIP-01 event record.
 *
 * <p>Wire shape:
 * <pre>{@code
 * {
 *   "id":         "<32-byte sha256 hex of canonical serialization>",
 *   "pubkey":     "<32-byte x-only pubkey hex>",
 *   "created_at": <unix seconds>,
 *   "kind":       1,
 *   "tags":       [["t", "wyrdsekai"], ...],
 *   "content":    "...",
 *   "sig":        "<64-byte BIP-340 Schnorr signature hex>"
 * }
 * }</pre>
 *
 * <p>Canonical serialization for the id:
 * {@code JSON.stringify([0, pubkey, created_at, kind, tags, content])}
 * — exact RFC-8259 escaping, no extraneous whitespace.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NostrEvent(
    String id,
    String pubkey,
    @JsonProperty("created_at") long createdAt,
    int kind,
    List<List<String>> tags,
    String content,
    String sig
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Build, sign, and return a complete event ready to publish to a relay.
     *
     * @param key      companion's Nostr keypair
     * @param kind     event kind (1 = short text note, 0 = metadata, 30023 = long-form, etc.)
     * @param tags     optional tags (each is a list of strings); empty list if none
     * @param content  the text content
     * @param createdAt unix seconds; pass {@link Instant#now()}.getEpochSecond() unless replaying
     */
    public static NostrEvent buildAndSign(
        NostrKey key, int kind, List<List<String>> tags, String content, long createdAt
    ) {
        var pubkey = key.pubKeyHex();
        var safeTags = tags == null ? List.<List<String>>of() : tags;
        var idBytes = computeId(pubkey, createdAt, kind, safeTags, content);
        var idHex = HEX.formatHex(idBytes);
        var sigBytes = Bip340.sign(key.privateScalar(), idBytes);
        var sigHex = HEX.formatHex(sigBytes);
        return new NostrEvent(idHex, pubkey, createdAt, kind, safeTags, content, sigHex);
    }

    /**
     * Verify this event's id matches the canonical serialization AND the
     * signature is valid against the {@code pubkey} field.
     */
    public boolean verify() {
        try {
            var expectedId = computeId(pubkey, createdAt, kind, tags, content);
            if (!HEX.formatHex(expectedId).equals(id)) return false;
            var pubKeyBytes = HEX.parseHex(pubkey);
            var sigBytes = HEX.parseHex(sig);
            return Bip340.verify(pubKeyBytes, expectedId, sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /** Serialize as a Nostr relay-bound message: {@code ["EVENT", {event}]} */
    public String toRelayPublishFrame() {
        try {
            var arr = MAPPER.createArrayNode();
            arr.add("EVENT");
            arr.addPOJO(this);
            return MAPPER.writeValueAsString(arr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** JSON for storage/logging. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<NostrEvent> fromJson(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(json, NostrEvent.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Compute the canonical event id: sha256 of
     * {@code [0, pubkey, created_at, kind, tags, content]} serialized as
     * UTF-8 JSON with no extra whitespace.
     */
    static byte[] computeId(String pubkey, long createdAt, int kind,
                             List<List<String>> tags, String content) {
        try {
            var arr = MAPPER.createArrayNode();
            arr.add(0);
            arr.add(pubkey);
            arr.add(createdAt);
            arr.add(kind);
            var tagsNode = MAPPER.createArrayNode();
            for (var t : tags == null ? List.<List<String>>of() : tags) {
                var inner = MAPPER.createArrayNode();
                for (var s : t) inner.add(s);
                tagsNode.add(inner);
            }
            arr.add(tagsNode);
            arr.add(content);
            var serial = MAPPER.writeValueAsBytes(arr);
            var sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(serial);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** UTF-8 wire bytes — convenience for outbound publish. */
    public byte[] toRelayPublishFrameBytes() {
        return toRelayPublishFrame().getBytes(StandardCharsets.UTF_8);
    }
}
