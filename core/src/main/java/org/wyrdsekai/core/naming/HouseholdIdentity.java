package org.wyrdsekai.core.naming;

import org.wyrdsekai.core.identity.DidKey;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * The canonical identity of a household — an Ed25519 keypair fingerprint,
 * rendered as a W3C-compatible {@code did:wyrd:z6Mk…} string.
 *
 * <p>A household is the unit of sovereignty in wyrdsekai: one keypair, one
 * identity, N zones, N nodes. The same Ed25519 keypair that signs
 * {@code BetweenEnvelope} messages is the household's identity root — we
 * don't introduce a separate "household key" because one key / three uses
 * (signing, transport, identity) is simpler and enforces the property that
 * everything a household publishes is provably from the same holder.</p>
 *
 * <p>The fingerprint format follows the did:key multibase convention
 * ({@code z} prefix + base58btc-encoded [0xed, 0x01] multicodec + 32 raw key
 * bytes), so any library that understands did:key can read the pubkey bytes
 * out of our DIDs by swapping the scheme prefix. We use the custom
 * {@code did:wyrd} scheme rather than {@code did:key} so wyrdsekai-specific
 * document shape (zones, contact semantics, etc.) is discoverable from the
 * DID alone.</p>
 *
 * <p>This class is <strong>immutable and pure</strong> — constructing a
 * {@code HouseholdIdentity} does not touch the filesystem or generate
 * keypairs. It's a view over an existing Ed25519 public key. Use
 * {@link #fromSpkiBytes(byte[])} to derive from a {@code NodeIdentity}'s
 * {@code publicKeyBytes()} output without depending on the between module
 * directly (keeps the core module free of the between-&gt;core layering
 * reversal that would otherwise arise).</p>
 */
public final class HouseholdIdentity {

    /** DID method scheme for wyrdsekai households. */
    public static final String DID_SCHEME = "did:wyrd:";

    /** Multibase prefix (base58btc) — the {@code z} in {@code z6Mk…}. */
    private static final String MULTIBASE_PREFIX = "z";

    private final byte[] rawPublicKey;
    private final String fingerprint;
    private final String did;

    private HouseholdIdentity(byte[] rawPublicKey, String fingerprint) {
        this.rawPublicKey = rawPublicKey.clone();
        this.fingerprint = fingerprint;
        this.did = DID_SCHEME + fingerprint;
    }

    /**
     * Build from an Ed25519 public key object directly.
     *
     * @param publicKey an {@code Ed25519} {@link PublicKey}. The raw 32-byte
     *                  form is extracted via {@link DidKey#extractRawEd25519PublicKey}.
     * @throws IllegalArgumentException if the key is not an Ed25519 SPKI key.
     */
    public static HouseholdIdentity fromPublicKey(PublicKey publicKey) {
        var raw = DidKey.extractRawEd25519PublicKey(publicKey);
        // DidKey.fromRawPublicKey returns "did:key:z6Mk…"; strip the scheme and
        // keep the multibase portion ({@code z6Mk…}) — that's our fingerprint.
        // We intentionally depend on the did:key encoding so swapping schemes
        // doesn't require re-implementing the multicodec+base58 dance.
        var didKey = DidKey.fromRawPublicKey(raw);
        var multibase = didKey.substring("did:key:".length());
        return new HouseholdIdentity(raw, multibase);
    }

    /**
     * Build from the SPKI-encoded public key bytes that
     * {@code NodeIdentity.publicKeyBytes()} returns. Convenience so callers
     * in the core module don't have to import {@code PublicKey} APIs.
     *
     * @param spkiBytes SubjectPublicKeyInfo DER bytes, 44 bytes for Ed25519.
     */
    public static HouseholdIdentity fromSpkiBytes(byte[] spkiBytes) {
        try {
            var kf = KeyFactory.getInstance("Ed25519");
            var pub = kf.generatePublic(new X509EncodedKeySpec(spkiBytes));
            return fromPublicKey(pub);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to decode Ed25519 SPKI bytes into a PublicKey (length="
                    + (spkiBytes == null ? "null" : spkiBytes.length) + ")", e);
        }
    }

    /**
     * The multibase-encoded fingerprint ({@code z6Mk…}) — the portion that
     * follows the {@code did:wyrd:} scheme prefix. This is what a NATS subject
     * or a file path uses; {@link #did()} is what a UI or W3C consumer shows.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * The fully-qualified DID for this household: {@code did:wyrd:z6Mk…}.
     */
    public String did() {
        return did;
    }

    /**
     * A copy of the raw 32-byte Ed25519 public key. Callers that verify
     * envelope signatures need the raw form; we store it so they don't have
     * to re-parse the DID.
     */
    public byte[] rawPublicKey() {
        return rawPublicKey.clone();
    }

    /**
     * Build a {@link ZoneAddress} rooted at this household with the given
     * zone label. Validates the label at construction time — reserved
     * keywords and malformed strings are rejected here rather than deep in
     * the resolver, so invalid zones can't materialise in the registry.
     */
    public ZoneAddress zone(String label) {
        ZoneLabels.requireValid(label, "zone label");
        return new ZoneAddress(fingerprint, label);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HouseholdIdentity other
            && fingerprint.equals(other.fingerprint);
    }

    @Override
    public int hashCode() {
        return fingerprint.hashCode();
    }

    @Override
    public String toString() {
        return did;
    }
}
