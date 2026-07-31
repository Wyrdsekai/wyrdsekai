package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed, item-based access token that grants one agent the right to summon
 * another agent's named familiar or thought form.
 *
 * <p>. The issuing parent retains ownership of the
 * familiar; the recipient only gets summoning permission. Structural
 * guarantees:</p>
 * <ul>
 *   <li><b>Signed</b> (§20.5) — issuer signs the canonical byte form; forging
 *       a key requires the issuer's private key.</li>
 *   <li><b>Recipient-locked</b> (§20.4) — the {@code issuedTo} DID is checked
 *       at summon time. Keys are transferable items, but only valid for
 *       their declared recipient.</li>
 *   <li><b>Scoped</b> — ONCE, UNTIL_DATE, PERMANENT, or REVOCABLE (§20.1).</li>
 *   <li><b>Non-authoring</b> (§20.5) — never grants the recipient the right
 *       to modify the underlying form or familiar.</li>
 * </ul>
 *
 * @param id            opaque identifier
 * @param targetRef     "form:{formId}" or "named:{familiarName}"
 * @param issuedBy      DID of the owning parent
 * @param issuedTo      DID of the authorized recipient
 * @param scope         access scope (see {@link Scope})
 * @param expiresAt     absolute expiry; only honored when {@code scope == UNTIL_DATE}
 * @param restrictions  key-imposed ceilings on invocation
 * @param issuedAt      issuance timestamp
 * @param signatureBase64  issuer's Ed25519 signature over canonical bytes (Base64)
 */
public record SummonKey(
    String id,
    String targetRef,
    String issuedBy,
    String issuedTo,
    Scope scope,
    Optional<Instant> expiresAt,
    Restrictions restrictions,
    Instant issuedAt,
    String signatureBase64
) {

    public enum Scope {
        /** Valid for exactly one summoning, then automatically invalidated. */
        ONCE,
        /** Valid until {@link #expiresAt}; after that, refused. */
        UNTIL_DATE,
        /** Indefinite until explicitly revoked. */
        PERMANENT,
        /** Explicitly revocable at any time by the issuer. */
        REVOCABLE
    }

    /**
     * Key-imposed restrictions. The recipient's effective summon tanks are
     * bounded by these; tighter of {key, form-default} wins at summon time.
     *
     * <p>{@code costTransfer} is cost routing: by default
     * the issuer's CU pays when the recipient summons (the familiar is the
     * issuer's to lend, so the issuer absorbs the cost). If {@code true}, the
     * cost transfers to the recipient's CU budget instead.</p>
     */
    public record Restrictions(
        Tanks maxTanks,
        Optional<Integer> maxSummons,
        boolean costTransfer
    ) {
        public Restrictions {
            if (maxTanks == null) maxTanks = Tanks.defaults();
            if (maxSummons == null) maxSummons = Optional.empty();
            maxSummons.ifPresent(n -> {
                if (n < 1) throw new IllegalArgumentException("maxSummons must be positive");
            });
        }

        /** Backward-compatible constructor — costTransfer defaults to false (issuer pays). */
        public Restrictions(Tanks maxTanks, Optional<Integer> maxSummons) {
            this(maxTanks, maxSummons, false);
        }

        public static Restrictions defaults() {
            return new Restrictions(Tanks.defaults(), Optional.empty(), false);
        }
    }

    public SummonKey {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef required");
        }
        if (issuedBy == null || issuedBy.isBlank()) {
            throw new IllegalArgumentException("issuedBy required");
        }
        if (issuedTo == null || issuedTo.isBlank()) {
            throw new IllegalArgumentException("issuedTo required");
        }
        if (scope == null) scope = Scope.REVOCABLE;
        if (expiresAt == null) expiresAt = Optional.empty();
        if (scope == Scope.UNTIL_DATE && expiresAt.isEmpty()) {
            throw new IllegalArgumentException("UNTIL_DATE scope requires expiresAt");
        }
        if (restrictions == null) restrictions = Restrictions.defaults();
        if (issuedAt == null) issuedAt = Instant.now();
        // signatureBase64 may be null during construction; set via withSignature()
    }

    /** Create an unsigned key ready to be passed to the issuer's signer. */
    public static SummonKey draft(String targetRef, String issuedBy, String issuedTo,
                                   Scope scope, Optional<Instant> expiresAt,
                                   Restrictions restrictions) {
        return new SummonKey(UUID.randomUUID().toString(), targetRef,
            issuedBy, issuedTo, scope, expiresAt, restrictions,
            Instant.now(), null);
    }

    /** Return a copy of this key with the given signature attached. */
    public SummonKey withSignature(String signatureBase64) {
        return new SummonKey(id, targetRef, issuedBy, issuedTo, scope,
            expiresAt, restrictions, issuedAt, signatureBase64);
    }

    /**
     * Deterministic byte form of the key's substantive fields for signing/verifying.
     * Keep this stable — changing the format invalidates previously-issued keys.
     */
    public byte[] canonicalBytes() {
        var sb = new StringBuilder();
        sb.append(id).append('|');
        sb.append(targetRef).append('|');
        sb.append(issuedBy).append('|');
        sb.append(issuedTo).append('|');
        sb.append(scope).append('|');
        sb.append(expiresAt.map(Instant::getEpochSecond).orElse(0L)).append('|');
        sb.append(restrictions.maxTanks()).append('|');
        sb.append(restrictions.maxSummons().orElse(-1)).append('|');
        sb.append(restrictions.costTransfer()).append('|');
        sb.append(issuedAt.getEpochSecond());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the key is expired given a current instant. */
    public boolean isExpired(Instant now) {
        if (scope == Scope.UNTIL_DATE && expiresAt.isPresent()) {
            return !now.isBefore(expiresAt.get());
        }
        return false;
    }

    @JsonIgnore
    public boolean isSigned() {
        return signatureBase64 != null && !signatureBase64.isBlank();
    }
}
