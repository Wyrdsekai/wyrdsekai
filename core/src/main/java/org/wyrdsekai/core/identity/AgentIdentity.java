package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.core.soul.ResidencyToken;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Cryptographic identity for an agent (§85.1).
 * Uses Ed25519 keys bound to a DID:key identifier with KERI event log for key rotation history.
 *
 * <p>Private key is never stored as a KeyPair — raw bytes encrypted with AES-256-GCM
 * via the household secret (TheSafe). Public key stored as raw 32 bytes for Jackson serialization.</p>
 *
 * <p>Design decisions (see plan C1, C8, C11):
 * <ul>
 *   <li>Raw bytes, not KeyPair — KeyPair is not Jackson-serializable</li>
 *   <li>Private key encrypted at rest — AES-256-GCM, household secret from TheSafe</li>
 *   <li>AgentDelegation as record (not enum) — UCAN-inspired capability chains (Phase 6)</li>
 *   <li>KERI event log — append-only key rotation history with pre-rotation</li>
 * </ul></p>
 *
 * @param did                   DID:key identifier (did:key:z6Mk...)
 * @param publicKey             Raw 32-byte Ed25519 public key
 * @param privateKeyEncrypted   AES-256-GCM encrypted private key (null if key held elsewhere)
 * @param keyLog                KERI event log (inception + rotations)
 * @param created               When this identity was created
 * @param parentDid             Parent agent's DID (null for root agents)
 * @param delegation            Delegation from principal/parent
 */
public record AgentIdentity(
    @JsonProperty("did") String did,
    @JsonProperty("publicKey") byte[] publicKey,
    @JsonProperty("privateKeyEncrypted") byte[] privateKeyEncrypted,
    @JsonProperty("keyLog") List<ObjectNode> keyLog,
    @JsonProperty("created") Instant created,
    @JsonProperty("parentDid") String parentDid,
    @JsonProperty("delegation") AgentIdentity.IdentityDelegation delegation
) {
    /** AES-256-GCM tag length in bits. */
    private static final int GCM_TAG_BITS = 128;
    /** AES-256-GCM IV length in bytes. */
    private static final int GCM_IV_BYTES = 12;

    @JsonCreator
    public AgentIdentity {
        if (did == null || did.isBlank()) throw new IllegalArgumentException("DID must not be blank");
        if (publicKey == null || publicKey.length != 32)
            throw new IllegalArgumentException("Public key must be 32 bytes");
        if (keyLog == null) keyLog = List.of();
        if (created == null) created = Instant.now();
    }

    /**
     * Delegation info carried with the identity.
     * Phase 1: level only. Phase 6: capabilities + proof chain enriched.
     */
    public record IdentityDelegation(
        @JsonProperty("level") DelegationLevel level,
        @JsonProperty("capabilities") List<Capability> capabilities,
        @JsonProperty("parentProofId") String parentProofId,
        @JsonProperty("signature") byte[] signature
    ) {
        @JsonCreator
        public IdentityDelegation {
            if (level == null) level = DelegationLevel.FULL;
        }

        /** Phase 1 convenience — level only. */
        public static IdentityDelegation of(DelegationLevel level) {
            return new IdentityDelegation(level, null, null, null);
        }
    }

    /**
     * Generate a new agent identity with fresh Ed25519 keypair.
     *
     * @param householdSecret 32-byte secret from TheSafe for encrypting the private key
     * @return new AgentIdentity with inception event in key log
     */
    public static AgentIdentity generate(byte[] householdSecret) throws Exception {
        return generate(householdSecret, null, IdentityDelegation.of(DelegationLevel.FULL));
    }

    /**
     * Generate a new agent identity with fresh Ed25519 keypair.
     *
     * @param householdSecret 32-byte secret from TheSafe for encrypting the private key
     * @param parentDid       parent agent's DID (null for root agents)
     * @param delegation      delegation from parent/principal
     * @return new AgentIdentity with inception event in key log
     */
    public static AgentIdentity generate(byte[] householdSecret, String parentDid,
                                          IdentityDelegation delegation) throws Exception {
        // Generate Ed25519 keypair
        var didKeyPair = DidKey.generate();
        var keyPair = didKeyPair.keyPair();
        var did = didKeyPair.did();

        // Extract raw keys
        var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
        var rawPrivKey = extractRawPrivateKey(keyPair.getPrivate());

        // Encrypt private key
        var encryptedPrivKey = encryptPrivateKey(rawPrivKey, householdSecret);

        // Generate next rotation keypair for KERI pre-rotation
        var nextKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var inceptionEvent = KeriEvent.inception(keyPair.getPublic(), nextKeyPair.getPublic());

        return new AgentIdentity(
            did, rawPubKey, encryptedPrivKey,
            List.of(inceptionEvent),
            Instant.now(), parentDid, delegation
        );
    }

    /**
     * Fork this identity to create a child agent.
     * Child gets a new keypair but inherits parent DID reference.
     *
     * @param householdSecret 32-byte secret for encrypting child's private key
     * @param childDelegation delegation level for the child
     * @return new AgentIdentity for the forked child
     */
    public AgentIdentity fork(byte[] householdSecret, IdentityDelegation childDelegation) throws Exception {
        return generate(householdSecret, this.did, childDelegation);
    }

    /**
     * Create an AgentIdentity from a ResidencyToken (§110.3).
     * Used when a foreign agent transitions from VISITOR to RECOGNIZED.
     *
     * <p>The foreign agent holds their own private key — we only have the public key.
     * So privateKeyEncrypted is null for foreign agents. KERI inception event is created
     * from the token's public key.</p>
     *
     * @param token           ResidencyToken for the foreign agent
     * @param householdSecret 32-byte secret from TheSafe (used for KERI pre-rotation key)
     * @return new AgentIdentity with the foreign agent's public key
     */
    public static AgentIdentity fromResidencyToken(ResidencyToken token, byte[] householdSecret) throws Exception {
        // Reconstruct PublicKey from raw bytes for KERI event
        var spki = new byte[44];
        var header = new byte[]{
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        };
        System.arraycopy(header, 0, spki, 0, 12);
        System.arraycopy(token.publicKey(), 0, spki, 12, 32);
        var keySpec = new X509EncodedKeySpec(spki);
        var pubKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec);

        // Generate next keypair for KERI pre-rotation (household controls rotation for foreign agents)
        var nextKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var inceptionEvent = KeriEvent.inception(pubKey, nextKeyPair.getPublic());

        return new AgentIdentity(
            token.did(), token.publicKey(), null, // no private key — foreign agent holds it
            List.of(inceptionEvent),
            token.issued(), null, // no parent DID for foreign agents
            IdentityDelegation.of(DelegationLevel.READ_ONLY) // foreign agents start read-only
        );
    }

    /**
     * Sign data with this identity's private key.
     *
     * @param data            bytes to sign
     * @param householdSecret 32-byte secret to decrypt the private key
     * @return Base64-encoded Ed25519 signature
     */
    public String sign(byte[] data, byte[] householdSecret) throws Exception {
        var keyPair = toKeyPair(householdSecret);
        var sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(data);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * Verify a signature against this identity's public key.
     *
     * @param data              original bytes
     * @param signatureBase64   Base64-encoded signature
     * @return true if signature is valid
     */
    public boolean verify(byte[] data, String signatureBase64) {
        try {
            var pubKey = reconstructPublicKey(publicKey);
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reconstruct the JDK KeyPair by decrypting the private key.
     *
     * @param householdSecret 32-byte secret to decrypt the private key
     * @return JDK Ed25519 KeyPair
     */
    @JsonIgnore
    public KeyPair toKeyPair(byte[] householdSecret) throws Exception {
        var rawPrivKey = decryptPrivateKey(privateKeyEncrypted, householdSecret);
        var pubKey = reconstructPublicKey(publicKey);
        var privKey = reconstructPrivateKey(rawPrivKey);
        return new KeyPair(pubKey, privKey);
    }

    /**
     * Canonical string for signing (used by delegation chains).
     */
    public String canonicalString() {
        return did + "|" + Base64.getEncoder().encodeToString(publicKey)
            + "|" + created + "|" + (parentDid != null ? parentDid : "");
    }

    // --- Encryption helpers ---

    static byte[] encryptPrivateKey(byte[] rawPrivateKey, byte[] householdSecret) throws Exception {
        if (householdSecret.length != 32) {
            throw new IllegalArgumentException("Household secret must be 32 bytes (AES-256)");
        }
        var iv = new byte[GCM_IV_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
            new SecretKeySpec(householdSecret, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        var encrypted = cipher.doFinal(rawPrivateKey);

        // Prepend IV to ciphertext
        var result = new byte[GCM_IV_BYTES + encrypted.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_BYTES);
        System.arraycopy(encrypted, 0, result, GCM_IV_BYTES, encrypted.length);
        return result;
    }

    static byte[] decryptPrivateKey(byte[] encryptedWithIv, byte[] householdSecret) throws Exception {
        if (householdSecret.length != 32) {
            throw new IllegalArgumentException("Household secret must be 32 bytes (AES-256)");
        }
        var iv = Arrays.copyOfRange(encryptedWithIv, 0, GCM_IV_BYTES);
        var ciphertext = Arrays.copyOfRange(encryptedWithIv, GCM_IV_BYTES, encryptedWithIv.length);

        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
            new SecretKeySpec(householdSecret, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    // --- Key reconstruction helpers ---

    static byte[] extractRawPrivateKey(PrivateKey privateKey) {
        // Ed25519 private key: 48-byte PKCS#8 encoded. Raw seed is last 32 bytes.
        var encoded = privateKey.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    static PublicKey reconstructPublicKey(byte[] rawPubKey32) throws Exception {
        // Reconstruct via DER/SPKI encoding
        // Fixed 12-byte header for Ed25519: 302a300506032b6570032100
        var spki = new byte[44];
        var header = new byte[]{
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        };
        System.arraycopy(header, 0, spki, 0, 12);
        System.arraycopy(rawPubKey32, 0, spki, 12, 32);
        var keySpec = new X509EncodedKeySpec(spki);
        return KeyFactory.getInstance("Ed25519").generatePublic(keySpec);
    }

    static PrivateKey reconstructPrivateKey(byte[] rawPrivKey32) throws Exception {
        // Reconstruct via PKCS#8 encoding
        // Fixed header for Ed25519 private key: 302e020100300506032b657004220420
        var pkcs8 = new byte[48];
        var header = new byte[]{
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
            0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
        };
        System.arraycopy(header, 0, pkcs8, 0, 16);
        System.arraycopy(rawPrivKey32, 0, pkcs8, 16, 32);
        var keySpec = new PKCS8EncodedKeySpec(pkcs8);
        return KeyFactory.getInstance("Ed25519").generatePrivate(keySpec);
    }
}
