package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.DidKey;

import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks summon keys, counts usage, and enforces revocation + §20.5 security.
 *
 * <p>Typical flow at summon time:</p>
 * <pre>
 *   var check = registry.validate(key, callerDid, issuerPublicKey, now);
 *   if (check.valid()) { ...summon...; registry.recordUse(key.id()); }
 * </pre>
 *
 * <p>In-memory today; persistence piggy-backs on the broader item/locker
 * persistence pass ( — "Persistence").</p>
 */
public final class SummonKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(SummonKeyRegistry.class);

    /** Per-key usage counter — used to enforce ONCE and maxSummons. */
    private final ConcurrentHashMap<String, Integer> uses = new ConcurrentHashMap<>();

    /** Revoked key ids. */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    // ── Validation ─────────────────────────────────────────────────────────

    public sealed interface Result {
        boolean valid();
        String reason();

        record Valid() implements Result {
            @Override public boolean valid() { return true; }
            @Override public String reason() { return "ok"; }
        }
        record Invalid(String reason) implements Result {
            @Override public boolean valid() { return false; }
        }
    }

    /**
     * Validate a summon key against its declared recipient, the issuer's
     * public key, and current registry state.
     *
     * @param key               key to check
     * @param callerDid         the DID attempting to summon
     * @param issuerPublicKey   the JDK Ed25519 public key belonging to {@code key.issuedBy}
     * @param now               current instant (pass Instant.now() in production)
     */
    public Result validate(SummonKey key, String callerDid,
                            PublicKey issuerPublicKey, Instant now) {
        if (key == null) return new Result.Invalid("key missing");
        if (!key.isSigned()) return new Result.Invalid("key unsigned");
        if (callerDid == null || callerDid.isBlank()) {
            return new Result.Invalid("caller DID missing");
        }
        if (!callerDid.equals(key.issuedTo())) {
            return new Result.Invalid("key issued to '" + key.issuedTo()
                + "', caller is '" + callerDid + "' (§20.4)");
        }
        if (revoked.contains(key.id())) {
            return new Result.Invalid("key revoked");
        }
        if (key.isExpired(now)) {
            return new Result.Invalid("key expired at " + key.expiresAt().orElseThrow());
        }
        // ONCE / maxSummons usage check
        var used = uses.getOrDefault(key.id(), 0);
        if (key.scope() == SummonKey.Scope.ONCE && used >= 1) {
            return new Result.Invalid("single-use key already consumed");
        }
        var cap = key.restrictions().maxSummons();
        if (cap.isPresent() && used >= cap.get()) {
            return new Result.Invalid("maxSummons " + cap.get() + " reached");
        }
        // Signature verification (§20.5)
        if (issuerPublicKey == null) {
            return new Result.Invalid("issuer public key missing");
        }
        if (!verifySignature(key, issuerPublicKey)) {
            return new Result.Invalid("signature verification failed");
        }
        return new Result.Valid();
    }

    /**
     * Convenience overload that resolves the issuer's public key directly from
     * {@code key.issuedBy()} when it is a {@code did:key:…} DID. No registry
     * lookup required — the DID spec embeds the public key. Falls back to
     * {@code Invalid("issuer DID not resolvable")} if the DID isn't a did:key.
     */
    public Result validate(SummonKey key, String callerDid, Instant now) {
        if (key == null) return new Result.Invalid("key missing");
        var pk = DidKey.publicKeyFromDid(key.issuedBy());
        if (pk.isEmpty()) {
            return new Result.Invalid("issuer DID '" + key.issuedBy()
                + "' not a did:key — cannot resolve signing key");
        }
        return validate(key, callerDid, pk.get(), now);
    }

    /** Ed25519 signature verification over canonicalBytes. */
    static boolean verifySignature(SummonKey key, PublicKey issuerPublicKey) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initVerify(issuerPublicKey);
            sig.update(key.canonicalBytes());
            return sig.verify(Base64.getDecoder().decode(key.signatureBase64()));
        } catch (Exception e) {
            log.debug("summon key signature verify failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Usage tracking ─────────────────────────────────────────────────────

    /** Record one summoning through this key. Returns the new usage count. */
    public int recordUse(String keyId) {
        return uses.merge(keyId, 1, Integer::sum);
    }

    public int usageCount(String keyId) {
        return uses.getOrDefault(keyId, 0);
    }

    // ── Revocation (§20.3) ─────────────────────────────────────────────────

    /**
     * Revoke a key. Returns true on first revocation. Revocation is
     * immediate; subsequent {@link #validate} calls refuse.
     */
    public boolean revoke(String keyId, String revokingDid, SummonKey key) {
        if (key == null || !revokingDid.equals(key.issuedBy())) {
            return false;    // only the issuer can revoke
        }
        if (key.scope() == SummonKey.Scope.PERMANENT) {
            // PERMANENT scope means indefinite until revoked explicitly. §20.1
            // reads as: PERMANENT is still revocable by the issuer (revocation
            // is a sovereign act). REVOCABLE just makes the expectation clear.
            // Either way, the issuer can revoke; non-issuers cannot.
        }
        return revoked.add(keyId);
    }

    public boolean isRevoked(String keyId) {
        return revoked.contains(keyId);
    }

    /** Snapshot — diagnostic only. */
    public int revokedCount() { return revoked.size(); }

    // --- Disk-hydration support ---

    /** Restore a usage counter value (for reloading from persisted state). */
    public void loadUsage(String keyId, int count) {
        if (keyId != null && count >= 0) uses.put(keyId, count);
    }

    /** Restore a revoked marker (for reloading from persisted state). */
    public void loadRevoked(String keyId) {
        if (keyId != null) revoked.add(keyId);
    }

    /** Snapshot of usage counters for serialization. */
    public Map<String, Integer> usageSnapshot() {
        return Map.copyOf(uses);
    }

    /** Snapshot of revoked key ids for serialization. */
    public Set<String> revokedSnapshot() {
        return Set.copyOf(revoked);
    }
}
