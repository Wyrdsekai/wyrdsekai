package org.wyrdsekai.between;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The one way a pinned key changes: a {@code key_rotation} message whose envelope is signed by the OLD
 * key (checked by the normal path) and whose payload carries the new key plus a signature by the NEW key
 * over {@link #statement}, so the sender proves it holds both. Used for household nodes (by node id) and
 * federated zones (by zone id).
 */
public final class KeyRotation {

    public static final String TYPE = "key_rotation";

    private KeyRotation() {}

    /** The bytes the new key signs: binds the subject id and both keys. */
    public static byte[] statement(String id, byte[] oldKey, byte[] newKey) {
        var b64 = Base64.getEncoder();
        return ("wyrd-key-rotation:v1:" + id + ":" + b64.encodeToString(oldKey) + ":"
            + b64.encodeToString(newKey)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The new key named by a rotation payload, when its proof verifies against {@code oldKey};
     * null otherwise. The caller has already verified the envelope itself against {@code oldKey}.
     */
    public static byte[] provenNewKey(String id, byte[] oldKey, JsonNode payload) {
        if (payload == null || !TYPE.equals(payload.path("type").asText())) return null;
        try {
            var newKey = Base64.getDecoder().decode(payload.path("newPublicKey").asText(""));
            var proof = Base64.getDecoder().decode(payload.path("newKeySig").asText(""));
            if (newKey.length == 0 || proof.length == 0) return null;
            return NodeIdentity.verify(statement(id, oldKey, newKey), proof, newKey) ? newKey : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
