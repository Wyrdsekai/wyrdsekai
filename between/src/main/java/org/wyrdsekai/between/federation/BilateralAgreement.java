package org.wyrdsekai.between.federation;

import org.wyrdsekai.common.model.QuotaPolicy;

import java.time.Instant;

/**
 * A bilateral federation agreement between two zones.
 * Persisted in the bilateral_agreements table.
 *
 * <p>Quota policies define what each zone allows the other to consume:
 * {@code localQuota} is what WE allow THEM to consume from us;
 * {@code remoteQuota} is what THEY said they'd allow from us (received during negotiation).</p>
 */
public record BilateralAgreement(
    String localZoneId,
    String remoteZoneId,
    String remotePublicKey,
    String status,
    String trustLevel,
    Instant agreedAt,
    Instant expiresAt,
    QuotaPolicy localQuota,
    QuotaPolicy remoteQuota,
    long epoch,
    String epochOwner
) {
    /**
     * Backward-compatible constructor (no epoch) — quotas default to trust-level
     * presets, epoch defaults to 0 / unowned. Pre-fence agreements read back as
     * epoch 0, which the fence treats as "oldest possible" (always superseded by a
     * fenced attempt), so legacy rows and old peers interoperate safely.
     */
    public BilateralAgreement(String localZoneId, String remoteZoneId, String remotePublicKey,
                              String status, String trustLevel, Instant agreedAt, Instant expiresAt) {
        this(localZoneId, remoteZoneId, remotePublicKey, status, trustLevel, agreedAt, expiresAt,
             QuotaPolicy.forTrustLevel(trustLevel),
             QuotaPolicy.forTrustLevel(trustLevel),
             0L, "");
    }

    /** Backward-compatible constructor with explicit quotas but no epoch. */
    public BilateralAgreement(String localZoneId, String remoteZoneId, String remotePublicKey,
                              String status, String trustLevel, Instant agreedAt, Instant expiresAt,
                              QuotaPolicy localQuota, QuotaPolicy remoteQuota) {
        this(localZoneId, remoteZoneId, remotePublicKey, status, trustLevel, agreedAt, expiresAt,
             localQuota, remoteQuota, 0L, "");
    }

    /** Return a copy stamped with a fencing epoch (counter + minting zone). */
    public BilateralAgreement withEpoch(long newEpoch, String newEpochOwner) {
        return new BilateralAgreement(localZoneId, remoteZoneId, remotePublicKey, status, trustLevel,
            agreedAt, expiresAt, localQuota, remoteQuota, newEpoch, newEpochOwner);
    }

    /** Return a copy with a new status (epoch/owner preserved). */
    public BilateralAgreement toStatus(String newStatus) {
        return new BilateralAgreement(localZoneId, remoteZoneId, remotePublicKey, newStatus, trustLevel,
            agreedAt, expiresAt, localQuota, remoteQuota, epoch, epochOwner);
    }

    /**
     * Total order on fencing tokens: a monotonic counter, with the minting zone id
     * as a deterministic tiebreak when two zones independently mint the same counter
     * (the crossing-propose case — see spec/tla/PeerHandshakeFenced.tla). Returns true
     * iff (aEpoch, aOwner) is strictly newer than (bEpoch, bOwner).
     */
    public static boolean isNewerEpoch(long aEpoch, String aOwner, long bEpoch, String bOwner) {
        if (aEpoch != bEpoch) return aEpoch > bEpoch;
        return aOwner.compareTo(bOwner) > 0;
    }

    /** Convenience: is the candidate token strictly newer than this agreement's token? */
    public boolean isOlderThan(long candidateEpoch, String candidateOwner) {
        return isNewerEpoch(candidateEpoch, candidateOwner, this.epoch, this.epochOwner);
    }

    /** Agreement statuses. */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_REVOKED = "revoked";

    /** Trust levels (M0 only supports tourist). */
    public static final String TRUST_TOURIST = "tourist";
    public static final String TRUST_RESIDENT = "resident";
    public static final String TRUST_CITIZEN = "citizen";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
