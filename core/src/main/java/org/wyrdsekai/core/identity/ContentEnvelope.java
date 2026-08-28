package org.wyrdsekai.core.identity;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Envelope encryption for person-owned content — the thing that makes identity
 * <em>changeable</em>.
 *
 * <p><b>The problem this replaces.</b> {@code PrivateJournalCipher} derives its
 * key from the owner's identity string ({@code study-journal:<userDid>}) and
 * also uses that string as GCM AAD. Content encrypted under one identity can
 * therefore <em>only ever</em> be decrypted as that identity — identity is
 * welded to data. Any identity mistake becomes permanent, and identity mistakes
 * are inevitable (key compromise, account merge, household transfer, a person
 * registering twice).</p>
 *
 * <p><b>The shape here.</b> Content is encrypted under a fresh per-item content
 * key. Only the <em>content key</em> is wrapped with something derived from the
 * owner's identity:</p>
 *
 * <pre>
 *   ciphertext  = AES-GCM(content_key,  plaintext,   aad = itemId)
 *   wrappedKey  = AES-GCM(wrapping_key, content_key, aad = ownerDid)
 * </pre>
 *
 * <p>Rebinding to a new owner then means unwrap-and-rewrap: one small operation
 * per item, and <b>the ciphertext is never touched</b>. Cost goes from
 * O(bytes-of-all-content) to O(items × 32 bytes). It also makes recovery keys
 * and shared access expressible later — the same content key can be wrapped for
 * more than one identity.</p>
 *
 * <p><b>AAD binds to the item, not the person</b> — deliberately. If the AAD
 * were the owner, rebinding would invalidate authentication on every item, which
 * is the exact trap being escaped.</p>
 *
 * @param ownerDid   the person this envelope is currently wrapped for
 * @param wrappedKey IV ‖ AES-GCM ciphertext of the 32-byte content key
 * @param ciphertext IV ‖ AES-GCM ciphertext of the payload
 */
public record ContentEnvelope(String ownerDid, byte[] wrappedKey, byte[] ciphertext) {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int CONTENT_KEY_BYTES = 32;

    /** Salt/info for deriving a wrapping key from a person's signing key. v1 allows rotation. */
    private static final byte[] WRAP_SALT =
        "wyrdsekai:content-key-wrap:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] WRAP_INFO =
        "content-key-wrapping".getBytes(StandardCharsets.UTF_8);

    public ContentEnvelope {
        Objects.requireNonNull(ownerDid, "ownerDid");
        Objects.requireNonNull(wrappedKey, "wrappedKey");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }

    /**
     * Encrypt content for a person.
     *
     * @param itemId          stable item identifier — bound as AAD, so it must not change
     * @param plaintext       the content
     * @param owner           the person who owns it
     * @param householdSecret unlocks the owner's private key
     */
    public static ContentEnvelope seal(String itemId, String plaintext,
                                       PersonIdentity owner, byte[] householdSecret)
            throws Exception {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(plaintext, "plaintext");

        var contentKey = new byte[CONTENT_KEY_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(contentKey);

        var ciphertext = gcmEncrypt(contentKey,
            plaintext.getBytes(StandardCharsets.UTF_8), aad(itemId));
        var wrapped = gcmEncrypt(wrappingKey(owner, householdSecret),
            contentKey, aad(owner.did()));

        Arrays.fill(contentKey, (byte) 0);
        return new ContentEnvelope(owner.did(), wrapped, ciphertext);
    }

    /**
     * Decrypt content.
     *
     * @param itemId          must be the same identifier used at seal time
     * @param owner           the person this envelope is wrapped for
     * @param householdSecret unlocks the owner's private key
     */
    public String open(String itemId, PersonIdentity owner, byte[] householdSecret)
            throws Exception {
        if (!owner.did().equals(ownerDid)) {
            throw new IllegalArgumentException(
                "Envelope is wrapped for " + ownerDid + ", not " + owner.did()
                    + " — rebind it rather than forcing it open");
        }
        var contentKey = gcmDecrypt(wrappingKey(owner, householdSecret), wrappedKey, aad(ownerDid));
        try {
            return new String(gcmDecrypt(contentKey, ciphertext, aad(itemId)), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(contentKey, (byte) 0);
        }
    }

    /**
     * Re-point this envelope at a different person.
     *
     * <p>This is the primitive the whole identity story rests on — key rotation,
     * account merge, household transfer and recovery are all calls to it. Only
     * the wrapped content key is rewritten; {@link #ciphertext()} comes back
     * byte-identical, which is what makes it affordable at 13.7M items.</p>
     *
     * @param from            the person it is currently wrapped for
     * @param to              the person it should be wrapped for
     * @param householdSecret unlocks both private keys
     */
    public ContentEnvelope rebind(PersonIdentity from, PersonIdentity to, byte[] householdSecret)
            throws Exception {
        if (!from.did().equals(ownerDid)) {
            throw new IllegalArgumentException(
                "Envelope is wrapped for " + ownerDid + ", not " + from.did());
        }
        if (from.did().equals(to.did())) return this;

        var contentKey = gcmDecrypt(wrappingKey(from, householdSecret), wrappedKey, aad(ownerDid));
        try {
            var rewrapped = gcmEncrypt(wrappingKey(to, householdSecret), contentKey, aad(to.did()));
            // ciphertext is passed through untouched — that is the point.
            return new ContentEnvelope(to.did(), rewrapped, ciphertext);
        } finally {
            Arrays.fill(contentKey, (byte) 0);
        }
    }

    // --- internals ---

    /**
     * A symmetric wrapping key derived from the person's Ed25519 private seed.
     * Ed25519 is a signing key, so it cannot wrap directly; HKDF gives a
     * deterministic AES key bound to the same identity.
     */
    private static byte[] wrappingKey(PersonIdentity identity, byte[] householdSecret)
            throws Exception {
        var seed = AgentIdentity.decryptPrivateKey(identity.encryptedPrivateKey(), householdSecret);
        try {
            var hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(seed, WRAP_SALT, WRAP_INFO));
            var out = new byte[32];
            hkdf.generateBytes(out, 0, out.length);
            return out;
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }

    private static byte[] aad(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gcmEncrypt(byte[] key, byte[] plaintext, byte[] aad) throws Exception {
        var iv = new byte[GCM_IV_BYTES];
        SecureRandom.getInstanceStrong().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad);
        var body = cipher.doFinal(plaintext);

        var out = new byte[GCM_IV_BYTES + body.length];
        System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
        System.arraycopy(body, 0, out, GCM_IV_BYTES, body.length);
        return out;
    }

    private static byte[] gcmDecrypt(byte[] key, byte[] ivAndBody, byte[] aad) throws Exception {
        var iv = Arrays.copyOfRange(ivAndBody, 0, GCM_IV_BYTES);
        var body = Arrays.copyOfRange(ivAndBody, GCM_IV_BYTES, ivAndBody.length);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContentEnvelope other)) return false;
        return ownerDid.equals(other.ownerDid)
            && Arrays.equals(wrappedKey, other.wrappedKey)
            && Arrays.equals(ciphertext, other.ciphertext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerDid, Arrays.hashCode(wrappedKey), Arrays.hashCode(ciphertext));
    }

    @Override
    public String toString() {
        return "ContentEnvelope[owner=" + ownerDid
            + ", wrappedKey=" + wrappedKey.length + "B"
            + ", ciphertext=" + ciphertext.length + "B]";
    }
}
