package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Optional;

/**
 * DID:key method implementation for Ed25519 (§73, §80).
 * Generates W3C DID identifiers and documents from Ed25519 keypairs.
 * Pure JDK — no external dependencies.
 *
 * Format: did:key:z{base58btc( [0xed, 0x01] + raw_32_byte_pubkey )}
 *
 * @see <a href="https://w3c-ccg.github.io/did-method-key/">DID:key Method Spec</a>
 */
public final class DidKey {

    private static final String BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** Ed25519 multicodec varint prefix: 0xed encoded as unsigned varint = [0xed, 0x01]. */
    private static final byte[] MULTICODEC_ED25519 = { (byte) 0xed, 0x01 };

    /** Fixed 12-byte DER/SPKI header for Ed25519 public keys. */
    private static final int ED25519_SPKI_PREFIX_LEN = 12;

    private DidKey() {}

    /**
     * Generate a new Ed25519 keypair and derive its DID:key identifier.
     * @return keypair + DID string
     */
    public static DidKeyPair generate() throws Exception {
        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = kpg.generateKeyPair();
        var did = fromPublicKey(keyPair.getPublic());
        return new DidKeyPair(keyPair, did);
    }

    /**
     * Derive a DID:key identifier from an existing Ed25519 public key.
     * @param publicKey JDK Ed25519 public key
     * @return did:key:z6Mk... string
     */
    public static String fromPublicKey(PublicKey publicKey) {
        var rawKey = extractRawEd25519PublicKey(publicKey);
        return fromRawPublicKey(rawKey);
    }

    /**
     * Derive a DID:key identifier from raw 32-byte Ed25519 public key.
     */
    public static String fromRawPublicKey(byte[] rawPubKey32) {
        if (rawPubKey32.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        // Prepend multicodec varint prefix: [0xed, 0x01] + 32 bytes = 34 bytes
        var multicodecKey = new byte[34];
        System.arraycopy(MULTICODEC_ED25519, 0, multicodecKey, 0, 2);
        System.arraycopy(rawPubKey32, 0, multicodecKey, 2, 32);

        // Base58btc encode, prepend 'z' (multibase prefix for base58btc)
        return "did:key:z" + base58Encode(multicodecKey);
    }

    /**
     * Build a minimal W3C DID document for a did:key identifier.
     */
    public static ObjectNode buildDocument(String did) {
        var mapper = new ObjectMapper();
        var doc = mapper.createObjectNode();

        var multibaseKey = did.substring("did:key:".length());
        var vmId = did + "#" + multibaseKey;

        doc.putArray("@context").add("https://www.w3.org/ns/did/v1.1");
        doc.put("id", did);

        var vm = mapper.createObjectNode();
        vm.put("id", vmId);
        vm.put("type", "Multikey");
        vm.put("controller", did);
        vm.put("publicKeyMultibase", multibaseKey);
        doc.putArray("verificationMethod").add(vm);

        doc.putArray("authentication").add(vmId);
        doc.putArray("assertionMethod").add(vmId);
        doc.putArray("capabilityDelegation").add(vmId);
        doc.putArray("capabilityInvocation").add(vmId);

        return doc;
    }

    /**
     * Extract raw 32-byte Ed25519 public key from JDK SPKI-encoded key.
     * The SPKI/DER encoding has a fixed 12-byte header for Ed25519.
     */
    public static byte[] extractRawEd25519PublicKey(PublicKey publicKey) {
        var encoded = publicKey.getEncoded(); // 44 bytes: 12-byte header + 32-byte key
        if (encoded.length != 44) {
            throw new IllegalArgumentException("Expected 44-byte SPKI Ed25519 key, got " + encoded.length);
        }
        return Arrays.copyOfRange(encoded, ED25519_SPKI_PREFIX_LEN, encoded.length);
    }

    /** Keypair + DID bundle. */
    public record DidKeyPair(KeyPair keyPair, String did) {}

    /**
     * Extract raw 32-byte Ed25519 public key from a multibase-encoded key string.
     * The multibase string is base58btc-encoded (prefix 'z') with a 2-byte
     * Ed25519 multicodec prefix [0xed, 0x01].
     *
     * @param multibaseKey Base58btc-encoded multibase key (starts with 'z')
     * @return raw 32-byte Ed25519 public key
     */
    /**
     * Resolve a {@code did:key:…} string directly to a JDK {@link PublicKey}.
     * The key is derivable from the DID itself — no registry lookup required.
     * Returns {@link java.util.Optional#empty()} on any parse or crypto error.
     */
    public static Optional<PublicKey> publicKeyFromDid(String did) {
        if (did == null || !did.startsWith("did:key:")) return Optional.empty();
        try {
            var multibase = did.substring("did:key:".length());
            var raw = rawPublicKeyFromMultibase(multibase);
            var spki = new byte[44];
            var header = new byte[]{
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
                0x70, 0x03, 0x21, 0x00
            };
            System.arraycopy(header, 0, spki, 0, 12);
            System.arraycopy(raw, 0, spki, 12, 32);
            var pubKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(spki));
            return Optional.of(pubKey);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static byte[] rawPublicKeyFromMultibase(String multibaseKey) {
        if (multibaseKey == null || multibaseKey.length() < 2) {
            throw new IllegalArgumentException("Invalid multibase key");
        }
        // Strip 'z' multibase prefix
        String base58Str = multibaseKey.startsWith("z") ? multibaseKey.substring(1) : multibaseKey;
        byte[] decoded = base58Decode(base58Str);
        if (decoded.length < 34) {
            throw new IllegalArgumentException(
                "Expected at least 34 bytes (2 multicodec + 32 key), got " + decoded.length);
        }
        // Strip 2-byte multicodec prefix [0xed, 0x01]
        return Arrays.copyOfRange(decoded, 2, 34);
    }

    // --- Base58btc encoding/decoding (Bitcoin alphabet) ---

    static String base58Encode(byte[] input) {
        if (input.length == 0) return "";

        // Count leading zeros
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) {
            leadingZeros++;
        }

        // Convert base-256 to base-58
        var number = Arrays.copyOf(input, input.length);
        var encoded = new char[number.length * 2]; // upper bound
        int outputStart = encoded.length;

        for (int inputStart = leadingZeros; inputStart < number.length; ) {
            encoded[--outputStart] = BASE58_ALPHABET.charAt(
                divmod(number, inputStart, 256, 58));
            if (number[inputStart] == 0) {
                inputStart++;
            }
        }

        // Preserve leading zeros as '1' characters
        while (outputStart < encoded.length
               && encoded[outputStart] == BASE58_ALPHABET.charAt(0)) {
            outputStart++;
        }
        while (--leadingZeros >= 0) {
            encoded[--outputStart] = BASE58_ALPHABET.charAt(0);
        }

        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    static byte[] base58Decode(String input) {
        if (input.isEmpty()) return new byte[0];

        // Count leading '1' chars (they map to leading zero bytes)
        int leadingOnes = 0;
        while (leadingOnes < input.length() && input.charAt(leadingOnes) == '1') {
            leadingOnes++;
        }

        // Allocate enough space in base-256 representation
        var decoded = new byte[input.length()]; // upper bound
        int outputStart = decoded.length;

        for (int i = leadingOnes; i < input.length(); i++) {
            int digit = BASE58_ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character: " + input.charAt(i));
            }
            int carry = digit;
            for (int j = decoded.length - 1; j >= outputStart || carry != 0; j--) {
                carry += 58 * (decoded[j] & 0xFF);
                decoded[j] = (byte) (carry % 256);
                carry /= 256;
                if (j <= outputStart) outputStart = j;
            }
        }

        // Build result with leading zeros
        var result = new byte[leadingOnes + (decoded.length - outputStart)];
        // leadingOnes zero bytes are already 0 in Java arrays
        System.arraycopy(decoded, outputStart, result, leadingOnes, decoded.length - outputStart);
        return result;
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
