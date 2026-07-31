package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * KERI Key Event construction with pre-rotation support (§73).
 * Uses SHA-256 (CESR code "I") as the digest algorithm — pure JDK, no Blake3 dependency.
 * Pre-rotation: inception event includes the digest of the next public key,
 * committing to the rotation target without revealing it.
 *
 * @see <a href="https://weboftrust.github.io/ietf-keri/draft-ssmith-keri.html">KERI IETF Draft</a>
 */
public final class KeriEvent {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SAID placeholder: 44 '#' characters (CESR primitive length for 32-byte digest). */
    private static final String SAID_PLACEHOLDER = "#".repeat(44);

    private KeriEvent() {}

    /**
     * CESR-encode an Ed25519 public key as transferable (code "D").
     * Format: "D" + 43 base64url chars = 44 chars total.
     */
    public static String cesrEncodeEd25519PubKey(byte[] rawPubKey32) {
        if (rawPubKey32.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        var padded = new byte[33];
        System.arraycopy(rawPubKey32, 0, padded, 1, 32);
        var b64 = B64URL.encodeToString(padded); // 44 chars
        return "D" + b64.substring(1);
    }

    /**
     * CESR-encode a SHA-256 digest (code "I").
     * Format: "I" + 43 base64url chars = 44 chars total.
     */
    public static String cesrEncodeSha256Digest(byte[] digest32) {
        if (digest32.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must be 32 bytes");
        }
        var padded = new byte[33];
        System.arraycopy(digest32, 0, padded, 1, 32);
        var b64 = B64URL.encodeToString(padded);
        return "I" + b64.substring(1);
    }

    /**
     * Compute pre-rotation commitment for a next public key.
     * 1. CESR-encode the key (code "D")
     * 2. SHA-256 hash the CESR-encoded string
     * 3. CESR-encode the digest (code "I")
     */
    public static String preRotationCommitment(byte[] nextRawPubKey32) throws Exception {
        var cesrKey = cesrEncodeEd25519PubKey(nextRawPubKey32);
        var digest = sha256(cesrKey.getBytes(StandardCharsets.UTF_8));
        return cesrEncodeSha256Digest(digest);
    }

    /**
     * Compute pre-rotation commitment from a JDK public key.
     */
    public static String preRotationCommitment(PublicKey nextPublicKey) throws Exception {
        return preRotationCommitment(DidKey.extractRawEd25519PublicKey(nextPublicKey));
    }

    /**
     * Build a KERI inception event (type "icp") with pre-rotation.
     *
     * @param currentKeys   current signing public keys (raw 32-byte Ed25519)
     * @param nextKeys      next rotation public keys (raw 32-byte Ed25519, for pre-rotation commitment)
     * @param signingThreshold  K-of-N signing threshold for current keys
     * @param nextThreshold     K-of-N threshold for next key set
     * @return inception event as JSON ObjectNode
     */
    public static ObjectNode inception(List<byte[]> currentKeys, List<byte[]> nextKeys,
                                        int signingThreshold, int nextThreshold) throws Exception {
        var event = MAPPER.createObjectNode();

        // CESR-encode current keys
        var kArray = MAPPER.createArrayNode();
        for (var key : currentKeys) {
            kArray.add(cesrEncodeEd25519PubKey(key));
        }

        // Pre-rotation commitments for next keys
        var nArray = MAPPER.createArrayNode();
        for (var key : nextKeys) {
            nArray.add(preRotationCommitment(key));
        }

        // Build event with SAID placeholder
        event.put("v", "KERI10JSON000000_"); // placeholder (same length as final)
        event.put("t", "icp");
        event.put("d", SAID_PLACEHOLDER);
        event.put("i", SAID_PLACEHOLDER);
        event.put("s", "0");
        event.put("kt", String.valueOf(signingThreshold));
        event.set("k", kArray);
        event.put("nt", String.valueOf(nextThreshold));
        event.set("n", nArray);
        event.put("bt", "0");
        event.set("b", MAPPER.createArrayNode());
        event.set("c", MAPPER.createArrayNode());
        event.set("a", MAPPER.createArrayNode());

        // Fix version string first (byte length is same with placeholder or final SAID — both 44 chars)
        var serialized = MAPPER.writeValueAsBytes(event);
        event.put("v", String.format("KERI10JSON%06x_", serialized.length));

        // Compute SAID (Self-Addressing Identifier) with finalized version string
        var said = computeSaid(event);
        event.put("d", said);
        event.put("i", said); // Self-addressing: AID = SAID

        return event;
    }

    /**
     * Build a KERI inception event from JDK public keys.
     */
    public static ObjectNode inception(PublicKey currentKey, PublicKey nextKey) throws Exception {
        return inception(
            List.of(DidKey.extractRawEd25519PublicKey(currentKey)),
            List.of(DidKey.extractRawEd25519PublicKey(nextKey)),
            1, 1);
    }

    /**
     * Compute SAID (Self-Addressing IDentifier) for a KERI event.
     * 1. Replace "d" (and "i" if self-addressing) with placeholder (44 '#' chars)
     * 2. SHA-256 hash the serialized bytes
     * 3. CESR-encode the digest (code "I")
     *
     * Note: modifies the event in place (d and i fields set to placeholder).
     */
    static String computeSaid(ObjectNode event) throws Exception {
        event.put("d", SAID_PLACEHOLDER);
        // For inception events, i == d (self-addressing AID)
        if (event.has("i")) {
            event.put("i", SAID_PLACEHOLDER);
        }

        var serialized = MAPPER.writeValueAsBytes(event);
        var digest = sha256(serialized);
        return cesrEncodeSha256Digest(digest);
    }

    static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
