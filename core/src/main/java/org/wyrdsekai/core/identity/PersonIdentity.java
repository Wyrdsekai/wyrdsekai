package org.wyrdsekai.core.identity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A PERSON's cryptographic identity — the human half of what {@link AgentIdentity}
 * already gives companions and familiars.
 *
 * <p><b>Why this exists (2026-08-07).</b> Agents got real identities from the
 * start: an Ed25519 keypair, the private key encrypted at rest under the
 * household secret, and a KERI key log with a pre-rotation commitment. People
 * got none of it. {@link PlayerAccount#create} generates a keypair and
 * <em>discards the private key</em>, keeping only the DID string — so no person
 * could ever sign anything, {@link AccountService#authenticate} could never
 * succeed for a locally-created account, and there was no key to wrap content
 * keys with.</p>
 *
 * <p>The consequence showed up as four different owner namespaces for one human
 * (a Unix username, a UUID, a mobile placeholder, and nothing) — see
 * The fix is not a better string; it
 * is giving the person the same machinery the agents have.</p>
 *
 * <p><b>Key custody.</b> The private key is encrypted with the 32-byte household
 * secret, exactly as {@link AgentIdentity} does. That is deliberate: a household
 * node is frequently headless (a box reached only over SSH), so a design that
 * requires a device-held key cannot be the <em>only</em> path. Moving a person
 * to a device-held key later is a rebind, not a redesign.</p>
 *
 * <p><b>Rotation.</b> The inception event carries a pre-rotation commitment to
 * the next key, so rotation is reachable without redesigning identity. It is not
 * yet implemented — {@link KeriEvent} builds {@code icp} but no {@code rot}, and
 * the identifier is still a {@code did:key} derived from the current key, so a
 * rotation today would change the identifier. Until that lands, rotation means
 * rebind.</p>
 *
 * @param did                 {@code did:key:z…} derived from the public key
 * @param publicKey           raw 32-byte Ed25519 public key
 * @param encryptedPrivateKey IV ‖ AES-256-GCM ciphertext of the raw private key
 * @param keyLog              KERI event log; element 0 is the inception event
 * @param createdAt           when this identity was minted
 */
public record PersonIdentity(
    String did,
    byte[] publicKey,
    byte[] encryptedPrivateKey,
    List<ObjectNode> keyLog,
    Instant createdAt
) {
    private static final Logger log = LoggerFactory.getLogger(PersonIdentity.class);

    public PersonIdentity {
        Objects.requireNonNull(did, "did");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(encryptedPrivateKey, "encryptedPrivateKey");
        if (!did.startsWith("did:key:")) {
            throw new IllegalArgumentException("Person DID must be a did:key — got: " + did);
        }
        if (publicKey.length != 32) {
            throw new IllegalArgumentException(
                "Ed25519 public key must be 32 bytes, got " + publicKey.length);
        }
        keyLog = keyLog == null ? List.of() : List.copyOf(keyLog);
    }

    /**
     * Mint a fresh person identity.
     *
     * @param householdSecret 32-byte secret used to encrypt the private key at rest
     * @return a new identity whose private key is recoverable only with that secret
     */
    public static PersonIdentity generate(byte[] householdSecret) throws Exception {
        Objects.requireNonNull(householdSecret, "householdSecret");

        var didKeyPair = DidKey.generate();
        var keyPair = didKeyPair.keyPair();
        var did = didKeyPair.did();

        var rawPubKey = DidKey.extractRawEd25519PublicKey(keyPair.getPublic());
        var rawPrivKey = AgentIdentity.extractRawPrivateKey(keyPair.getPrivate());
        var encryptedPrivKey = AgentIdentity.encryptPrivateKey(rawPrivKey, householdSecret);

        // Pre-rotation: commit to the next key without revealing it, so rotation
        // stays reachable later without reissuing the identity.
        var nextKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var inception = KeriEvent.inception(keyPair.getPublic(), nextKeyPair.getPublic());

        log.info("Person identity minted: {}", did);
        return new PersonIdentity(did, rawPubKey, encryptedPrivKey,
            List.of(inception), Instant.now());
    }

    /**
     * Recover the signing key. Needed to sign rebind attestations and
     * cross-device authentication challenges.
     *
     * @param householdSecret the same 32-byte secret used at mint time
     */
    public PrivateKey signingKey(byte[] householdSecret) throws Exception {
        var raw = AgentIdentity.decryptPrivateKey(encryptedPrivateKey, householdSecret);
        return AgentIdentity.reconstructPrivateKey(raw);
    }

    /** Sign bytes as this person. */
    public byte[] sign(byte[] data, byte[] householdSecret) throws Exception {
        var sig = Signature.getInstance("Ed25519");
        sig.initSign(signingKey(householdSecret));
        sig.update(data);
        return sig.sign();
    }

    /** Verify a signature made by this person — needs only the public half. */
    public boolean verify(byte[] data, byte[] signature) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKeyObject());
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.debug("Signature verification failed for {}: {}", did, e.getMessage());
            return false;
        }
    }

    /** The JDK public key for this identity. */
    public PublicKey publicKeyObject() throws Exception {
        return AgentIdentity.reconstructPublicKey(publicKey);
    }

    // Records with array components need these to be value-equal rather than identity-equal.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersonIdentity other)) return false;
        return did.equals(other.did)
            && Arrays.equals(publicKey, other.publicKey)
            && Arrays.equals(encryptedPrivateKey, other.encryptedPrivateKey)
            && Objects.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(did, Arrays.hashCode(publicKey), createdAt);
    }

    @Override
    public String toString() {
        // Never let key material reach a log line.
        return "PersonIdentity[did=" + did + ", created=" + createdAt + "]";
    }
}
