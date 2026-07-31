package org.wyrdsekai.common.home;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A single unit of delegated access. Immutable; lifecycle changes (revocation,
 * extension) produce new rows.
 *
 * @param id             UUID string
 * @param issuer         DID of the Home owner who issued the grant
 * @param subject        DID of the grantee, or {@code "public"}
 * @param resource       parsed {@link ResourceUri}
 * @param capability     one of {@link Capability}
 * @param scope          capability-specific qualifier (JSON-like Map); empty allowed
 * @param revocationMode propagation contract; default {@link RevocationMode#standard}
 * @param issuedAt       when the grant was issued
 * @param expiresAt      optional open-ended if {@code null}
 * @param revokedAt      {@code null} if active, set on revocation
 * @param reason         optional human-readable justification
 * @param witness        optional co-signing DID
 * @param delegatedFrom  optional parent-grant id if this was issued via {@link Capability#delegate}
 */
public record Grant(
    String id,
    String issuer,
    String subject,
    ResourceUri resource,
    Capability capability,
    Map<String, Object> scope,
    RevocationMode revocationMode,
    Instant issuedAt,
    Instant expiresAt,
    Instant revokedAt,
    String reason,
    String witness,
    String delegatedFrom
) {

    public static final String PUBLIC_SUBJECT = "public";

    public Grant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (scope == null) scope = Map.of();
        if (revocationMode == null) revocationMode = RevocationMode.standard;
    }

    /** True if this grant has been revoked, regardless of time. */
    public boolean isRevoked() { return revokedAt != null; }

    /** True if this grant has expired at {@code now}. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** True if this grant is currently valid — not revoked, not expired. */
    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    /** Mark this grant as revoked. Returns a new record; the old row remains in the ledger. */
    public Grant revoke(Instant when) {
        return new Grant(id, issuer, subject, resource, capability, scope, revocationMode,
            issuedAt, expiresAt, when, reason, witness, delegatedFrom);
    }

    // --- Builders for common cases ---------------------------------------

    /** New active grant with default standard revocation mode. */
    public static Grant issue(String issuer, String subject, ResourceUri resource,
                              Capability capability, Map<String, Object> scope,
                              Instant issuedAt, Instant expiresAt, String reason) {
        return new Grant(
            UUID.randomUUID().toString(),
            issuer, subject, resource, capability,
            scope == null ? Map.of() : scope,
            RevocationMode.standard,
            issuedAt, expiresAt, null, reason, null, null);
    }

    /** Strict-mode grant (no caching at callers). Use for attest, delegate, unbounded budgets. */
    public static Grant issueStrict(String issuer, String subject, ResourceUri resource,
                                     Capability capability, Map<String, Object> scope,
                                     Instant issuedAt, Instant expiresAt, String reason) {
        return new Grant(
            UUID.randomUUID().toString(),
            issuer, subject, resource, capability,
            scope == null ? Map.of() : scope,
            RevocationMode.strict,
            issuedAt, expiresAt, null, reason, null, null);
    }
}
