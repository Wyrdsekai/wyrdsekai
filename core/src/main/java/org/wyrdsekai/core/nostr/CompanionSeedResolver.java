package org.wyrdsekai.core.nostr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.soul.SoulStore;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * per-companion seed resolver.
 *
 * <p>Loads the {@link AgentIdentity} for a DID from the soul store, decrypts
 * the private key with the household secret, and returns the raw 32-byte
 * Ed25519 seed (used as IKM for the HKDF that yields the secp256k1 Nostr key).
 *
 * <p>Returns null if:
 * <ul>
 *   <li>No companion AgentIdentity is loaded for the given DID</li>
 *   <li>The identity's {@code privateKeyEncrypted} is null (e.g. foreign
 *       agents — they hold their own keys, not us)</li>
 *   <li>The household secret can't be obtained (Safe not unlocked yet)</li>
 *   <li>Decryption fails (corrupted ciphertext, wrong secret)</li>
 * </ul>
 *
 * <p>Null return signals "I don't know this DID" — the caller (usually a
 * {@link CompositeSeedResolver}) tries the next resolver in the chain.
 *
 * <p>Wired in {@code Main} after the soul store and Safe are both online:
 * <pre>{@code
 * var resolver = CompositeSeedResolver.of(
 *     new CompanionSeedResolver(
 *         soulStore::latest,
 *         () -> theSafe.readSecret("household.secret").orElse(null)),
 *     did -> nodeDid.equals(did) ? nodeSeed.clone() : null);
 * NostrAdapterBootstrap.setSeedResolver(resolver);
 * }</pre>
 */
public final class CompanionSeedResolver implements NostrAdapter.SeedResolver {

    private static final Logger log = LoggerFactory.getLogger(CompanionSeedResolver.class);

    /**
     * How to fetch an {@link AgentIdentity} for a DID. Production wiring
     * passes {@code soulStore::latestAgentIdentity} (or whatever the soul
     * store exposes); tests pass a Map-backed lambda.
     */
    @FunctionalInterface public interface AgentIdentityLookup {
        AgentIdentity findByDid(String did);
    }

    private final AgentIdentityLookup lookup;
    private final Supplier<byte[]> householdSecretSupplier;

    public CompanionSeedResolver(AgentIdentityLookup lookup,
                                  Supplier<byte[]> householdSecretSupplier) {
        this.lookup = lookup;
        this.householdSecretSupplier = householdSecretSupplier;
    }

    @Override public byte[] seedForDid(String did) {
        if (did == null || did.isBlank()) return null;
        AgentIdentity identity;
        try {
            identity = lookup.findByDid(did);
        } catch (Exception e) {
            log.debug("CompanionSeedResolver lookup failed for {}: {}", did, e.getMessage());
            return null;
        }
        if (identity == null) return null;
        if (identity.privateKeyEncrypted() == null
            || identity.privateKeyEncrypted().length == 0) {
            // Foreign agent or shell identity — we don't hold the private key.
            return null;
        }
        var secret = householdSecretSupplier.get();
        if (secret == null || secret.length != 32) {
            log.debug("CompanionSeedResolver: household secret unavailable for {}", did);
            return null;
        }
        try {
            var keyPair = identity.toKeyPair(secret);
            return extractSeedFromPkcs8(keyPair.getPrivate().getEncoded());
        } catch (Exception e) {
            log.warn("CompanionSeedResolver: decrypt failed for {}: {}",
                did, e.getMessage());
            return null;
        }
    }

    /**
     * Ed25519 PKCS#8 encoded private key is 48 bytes total: 16-byte ASN.1
     * header + 32-byte raw seed. Mirrors {@code AgentIdentity.extractRawPrivateKey}
     * (which is package-private, so we replicate the math here).
     */
    private static byte[] extractSeedFromPkcs8(byte[] pkcs8Encoded) {
        var seed = new byte[32];
        System.arraycopy(pkcs8Encoded, pkcs8Encoded.length - 32, seed, 0, 32);
        return seed;
    }

    /** Convenience factory for tests: bypass SoulStore wiring entirely. */
    public static CompanionSeedResolver forTest(
        Function<String, AgentIdentity> identityFn,
        byte[] householdSecret
    ) {
        return new CompanionSeedResolver(identityFn::apply, () -> householdSecret);
    }

    /**
     * Marker reference to {@link SoulStore} so callers see at-a-glance which
     * store is meant. SoulStore doesn't currently have a "find AgentIdentity
     * by DID" surface — Phase 2d will add one when the dependency materialises.
     */
    @SuppressWarnings("unused")
    private static void docOnlyRef(SoulStore unused) {}
}
