package org.wyrdsekai.between.federation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.home.HomeClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Materializes bilateral agreements as Grants on the zone steward's Home
 * ( AGREEMENT row).
 *
 * <p>Every federation agreement this zone holds is written twice: once into
 * the {@code bilateral_agreements} table (quota enforcement path in
 * {@code NatsInferenceServer}) and once as a {@code Grant} with
 * resource {@code home://did:zone:{local}/agreement/{remote}}. The grant
 * is the queryable/revocable view exposed by the Manifest furnishing and
 * {@code /api/home/grants} — FederationService remains the authority on
 * quota accounting.</p>
 *
 * <p>Active agreements mint grants with capability {@code use}; pending
 * agreements don't yet (they'd mislead the UI). Status changes re-sync.</p>
 */
public final class AgreementGrantSync {

    private static final Logger log = LoggerFactory.getLogger(AgreementGrantSync.class);

    private final HomeClient homeClient;

    public AgreementGrantSync(HomeClient homeClient) {
        this.homeClient = homeClient;
    }

    /** Zone-identity DID — synthetic until zones bind to a human steward account. */
    public static String zoneDid(String zoneId) {
        return "did:zone:" + zoneId;
    }

    /** Mint or replace the grant for this agreement. No-op when not {@code active}. */
    public void onSaved(BilateralAgreement agreement) {
        if (agreement == null) return;
        var issuer = zoneDid(agreement.localZoneId());
        var subject = zoneDid(agreement.remoteZoneId());
        var resource = ResourceUri.of(issuer, ResourceTypeRegistry.AGREEMENT,
            agreement.remoteZoneId());
        try {
            if (!agreement.isActive()) {
                // Pending / revoked / expired agreements clear any prior grant.
                homeClient.revokeByKey(issuer, subject, resource, Capability.use);
                return;
            }
            homeClient.issueOrReplace(
                issuer, subject, resource, Capability.use,
                scopeOf(agreement), agreement.expiresAt(),
                "bilateral-agreement:" + agreement.trustLevel());
        } catch (Exception e) {
            log.warn("AgreementGrantSync.onSaved {} → {}: {}",
                agreement.localZoneId(), agreement.remoteZoneId(), e.getMessage());
        }
    }

    /** Revoke on status change when the new status is non-active. */
    public void onStatusChanged(String localZoneId, String remoteZoneId, String newStatus) {
        if (BilateralAgreement.STATUS_ACTIVE.equals(newStatus)) return;
        var issuer = zoneDid(localZoneId);
        var subject = zoneDid(remoteZoneId);
        var resource = ResourceUri.of(issuer, ResourceTypeRegistry.AGREEMENT, remoteZoneId);
        try {
            homeClient.revokeByKey(issuer, subject, resource, Capability.use);
        } catch (Exception e) {
            log.warn("AgreementGrantSync.onStatusChanged {} → {} ({}): {}",
                localZoneId, remoteZoneId, newStatus, e.getMessage());
        }
    }

    private static Map<String, Object> scopeOf(BilateralAgreement a) {
        var scope = new HashMap<String, Object>();
        scope.put("trustLevel", a.trustLevel());
        if (a.localQuota() != null) {
            scope.put("localInferenceTokensPerDay", a.localQuota().inferenceTokensPerDay());
            scope.put("localStorageBytesTotal", a.localQuota().storageBytesTotal());
            scope.put("localBandwidthBytesPerDay", a.localQuota().bandwidthBytesPerDay());
            scope.put("localAllowTransit", a.localQuota().allowTransit());
            scope.put("localAllowTell", a.localQuota().allowTell());
            scope.put("localAllowInventory", a.localQuota().allowInventory());
        }
        if (a.remoteQuota() != null) {
            scope.put("remoteInferenceTokensPerDay", a.remoteQuota().inferenceTokensPerDay());
        }
        return scope;
    }
}
