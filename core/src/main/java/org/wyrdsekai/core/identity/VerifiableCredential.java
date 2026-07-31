package org.wyrdsekai.core.identity;

import java.time.Instant;
import java.util.Map;

/**
 * Verifiable Credential for Wyrdsekai (§18).
 * Issues credentials for roles, membership, and permissions.
 * Uses did:wyrd as issuer and subject identifiers.
 *
 * @param id            Unique credential ID
 * @param type          Credential type (role, membership, capability)
 * @param issuerDid     DID of the issuing authority
 * @param subjectDid    DID of the credential holder
 * @param claims        Key-value claims (e.g., role=wizard, zone=foundation)
 * @param issuedAt      When the credential was issued
 * @param expiresAt     When the credential expires (null = no expiry)
 * @param revoked       Whether the credential has been revoked
 */
public record VerifiableCredential(
    String id,
    String type,
    String issuerDid,
    String subjectDid,
    Map<String, String> claims,
    Instant issuedAt,
    Instant expiresAt,
    boolean revoked
) {
    /** Check if the credential is currently valid. */
    public boolean isValid() {
        if (revoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        return true;
    }

    /** Check if the credential has a specific claim. */
    public boolean hasClaim(String key) {
        return claims.containsKey(key);
    }

    /** Get a claim value. */
    public String getClaim(String key) {
        return claims.get(key);
    }

    /** Create a role credential. */
    public static VerifiableCredential role(String id, String issuerDid, String subjectDid,
                                             String role, Instant expiresAt) {
        return new VerifiableCredential(id, "role", issuerDid, subjectDid,
            Map.of("role", role), Instant.now(), expiresAt, false);
    }

    /** Create a membership credential. */
    public static VerifiableCredential membership(String id, String issuerDid, String subjectDid,
                                                    String zone) {
        return new VerifiableCredential(id, "membership", issuerDid, subjectDid,
            Map.of("zone", zone), Instant.now(), null, false);
    }

    /** Create a revoked copy of this credential. */
    public VerifiableCredential revoke() {
        return new VerifiableCredential(id, type, issuerDid, subjectDid,
            claims, issuedAt, expiresAt, true);
    }
}
