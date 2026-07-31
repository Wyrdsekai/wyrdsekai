package org.wyrdsekai.common.home;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Apply a {@link TrustTier} to a base grant shape.
 *
 * <p>Carries the default expiry + scope additions for the tier; callers can
 * still override {@code expiresAt} or merge additional scope keys.</p>
 */
public final class GrantTemplate {

    private GrantTemplate() {}

    /**
     * Build a Grant with the tier's defaults. Caller supplies issuer/subject/
     * resource/capability/reason; the template fills {@code expiresAt} (if
     * null) and merges {@link TrustTier#scopeAdditions()} into {@code scope}.
     */
    public static Grant forTier(
            TrustTier tier,
            String issuer,
            String subject,
            ResourceUri resource,
            Capability capability,
            Map<String, Object> baseScope,
            Instant explicitExpiresAt,
            String reason) {
        var scope = new HashMap<String, Object>();
        if (baseScope != null) scope.putAll(baseScope);
        scope.putAll(tier.scopeAdditions());
        Instant expiresAt = explicitExpiresAt;
        if (expiresAt == null && tier.defaultTtl() != null) {
            expiresAt = Instant.now().plus(tier.defaultTtl());
        }
        return new Grant(
            UUID.randomUUID().toString(),
            issuer, subject, resource, capability,
            scope,
            RevocationMode.standard,
            Instant.now(),
            expiresAt,
            null,
            reason,
            null, null);
    }
}
