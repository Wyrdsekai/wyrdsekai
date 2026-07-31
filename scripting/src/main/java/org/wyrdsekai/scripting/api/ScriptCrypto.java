package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Java-backed crypto utilities exposed to GraalJS scripts.
 * Available at {@link org.wyrdsekai.scripting.sandbox.SandboxLevel#SKILL_BASIC} and above.
 *
 * <p>Provides hashing, HMAC, and base64 encoding/decoding.
 * Uses JDK-native implementations only (no external crypto deps).
 *
 * <p>Scripts use this as:
 * <pre>
 *   var hash = crypto.sha256("hello");
 *   var mac = crypto.hmac("secret-key", "data");
 *   var encoded = crypto.base64Encode("hello");
 *   var decoded = crypto.base64Decode(encoded);
 * </pre>
 */
public class ScriptCrypto {

    /**
     * Compute SHA-256 hash of the input, returned as lowercase hex string.
     *
     * @param input The string to hash
     * @return Hex-encoded SHA-256 hash
     * @throws IllegalArgumentException if input is null
     */
    @HostAccess.Export
    public String sha256(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Compute HMAC-SHA256 of the data using the given key.
     * Returns a lowercase hex string.
     *
     * @param key  The secret key
     * @param data The data to authenticate
     * @return Hex-encoded HMAC-SHA256
     * @throws IllegalArgumentException if key or data is null
     */
    @HostAccess.Export
    public String hmac(String key, String data) {
        if (key == null) {
            throw new IllegalArgumentException("Key must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("Data must not be null");
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Base64-encode a string.
     *
     * @param input The string to encode
     * @return Base64-encoded string
     * @throws IllegalArgumentException if input is null
     */
    @HostAccess.Export
    public String base64Encode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64-decode a string.
     *
     * @param encoded The base64-encoded string
     * @return Decoded string
     * @throws IllegalArgumentException if encoded is null or invalid
     */
    @HostAccess.Export
    public String base64Decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 input: " + e.getMessage(), e);
        }
    }
}
