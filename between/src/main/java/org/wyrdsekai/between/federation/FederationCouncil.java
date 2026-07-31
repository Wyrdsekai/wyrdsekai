package org.wyrdsekai.between.federation;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federation Council — cross-zone governance, ban list sharing, and appeals (§71).
 * Manages shared ban lists and cross-zone moderation appeals.
 */
public class FederationCouncil {

    /** A cross-zone ban entry. */
    public record BanEntry(
        String entityId,
        String issuingZoneId,
        String reason,
        BanScope scope,
        Instant bannedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        public boolean isActive() {
            return !isExpired();
        }
    }

    public enum BanScope { ZONE_LOCAL, FEDERATION_WIDE }

    /** An appeal against a ban. */
    public record Appeal(
        String appealId,
        String entityId,
        String bannedByZoneId,
        String reason,
        AppealStatus status,
        Instant filedAt,
        Instant resolvedAt,
        String resolution,
        Map<String, Boolean> votes
    ) {}

    public enum AppealStatus { PENDING, UNDER_REVIEW, APPROVED, DENIED }

    /** Result of a ban check. */
    public record BanCheck(boolean banned, String reason, String issuingZoneId) {
        public static BanCheck clear() {
            return new BanCheck(false, null, null);
        }

        public static BanCheck banned(BanEntry entry) {
            return new BanCheck(true, entry.reason(), entry.issuingZoneId());
        }
    }

    private final Map<String, BanEntry> banList = new ConcurrentHashMap<>();
    private final Map<String, Appeal> appeals = new ConcurrentHashMap<>();
    private final Set<String> subscribedZones = ConcurrentHashMap.newKeySet();
    private int nextAppealId = 1;

    /**
     * Issue a ban against an entity.
     */
    public BanEntry ban(String entityId, String issuingZoneId, String reason,
                        BanScope scope, Instant expiresAt) {
        var entry = new BanEntry(entityId, issuingZoneId, reason, scope,
            Instant.now(), expiresAt);
        banList.put(entityId, entry);
        return entry;
    }

    /**
     * Check if an entity is banned.
     */
    public BanCheck checkBan(String entityId) {
        var entry = banList.get(entityId);
        if (entry == null || !entry.isActive()) return BanCheck.clear();
        return BanCheck.banned(entry);
    }

    /**
     * Check if an entity is banned from a specific zone (includes federation-wide bans).
     */
    public BanCheck checkBanForZone(String entityId, String zoneId) {
        var entry = banList.get(entityId);
        if (entry == null || !entry.isActive()) return BanCheck.clear();

        // Federation-wide bans apply everywhere
        if (entry.scope() == BanScope.FEDERATION_WIDE) return BanCheck.banned(entry);

        // Zone-local bans only apply to the issuing zone
        if (entry.issuingZoneId().equals(zoneId)) return BanCheck.banned(entry);

        return BanCheck.clear();
    }

    /**
     * Lift a ban on an entity.
     * @return true if a ban was removed
     */
    public boolean unban(String entityId) {
        return banList.remove(entityId) != null;
    }

    /**
     * File an appeal against a ban.
     */
    public Appeal fileAppeal(String entityId, String bannedByZoneId, String reason) {
        var appealId = "appeal-" + nextAppealId++;
        var appeal = new Appeal(appealId, entityId, bannedByZoneId, reason,
            AppealStatus.PENDING, Instant.now(), null, null, new HashMap<>());
        appeals.put(appealId, appeal);
        return appeal;
    }

    /**
     * Vote on an appeal. Each zone gets one vote.
     */
    public Optional<Appeal> voteOnAppeal(String appealId, String zoneId, boolean approve) {
        var appeal = appeals.get(appealId);
        if (appeal == null || appeal.status() != AppealStatus.PENDING
            && appeal.status() != AppealStatus.UNDER_REVIEW) {
            return Optional.empty();
        }

        var votes = new HashMap<>(appeal.votes());
        votes.put(zoneId, approve);

        var updated = new Appeal(appeal.appealId(), appeal.entityId(),
            appeal.bannedByZoneId(), appeal.reason(),
            AppealStatus.UNDER_REVIEW, appeal.filedAt(),
            null, null, votes);
        appeals.put(appealId, updated);
        return Optional.of(updated);
    }

    /**
     * Resolve an appeal based on votes. Requires majority to approve.
     */
    public Optional<Appeal> resolveAppeal(String appealId) {
        var appeal = appeals.get(appealId);
        if (appeal == null || appeal.status() == AppealStatus.APPROVED
            || appeal.status() == AppealStatus.DENIED) {
            return Optional.empty();
        }

        long approvals = appeal.votes().values().stream().filter(v -> v).count();
        long denials = appeal.votes().values().stream().filter(v -> !v).count();

        var approved = approvals > denials;
        var status = approved ? AppealStatus.APPROVED : AppealStatus.DENIED;
        var resolution = approved
            ? "Appeal approved by majority vote (" + approvals + "/" + appeal.votes().size() + ")"
            : "Appeal denied by majority vote (" + denials + "/" + appeal.votes().size() + ")";

        var resolved = new Appeal(appeal.appealId(), appeal.entityId(),
            appeal.bannedByZoneId(), appeal.reason(),
            status, appeal.filedAt(), Instant.now(), resolution, appeal.votes());
        appeals.put(appealId, resolved);

        // If approved, lift the ban
        if (approved) {
            unban(appeal.entityId());
        }

        return Optional.of(resolved);
    }

    /** Get an appeal by ID. */
    public Optional<Appeal> getAppeal(String appealId) {
        return Optional.ofNullable(appeals.get(appealId));
    }

    /** List all pending/under-review appeals. */
    public List<Appeal> pendingAppeals() {
        return appeals.values().stream()
            .filter(a -> a.status() == AppealStatus.PENDING || a.status() == AppealStatus.UNDER_REVIEW)
            .sorted(Comparator.comparing(Appeal::filedAt))
            .toList();
    }

    /** Subscribe a zone to ban list updates. */
    public void subscribeZone(String zoneId) {
        subscribedZones.add(zoneId);
    }

    /** Unsubscribe a zone from ban list updates. */
    public void unsubscribeZone(String zoneId) {
        subscribedZones.remove(zoneId);
    }

    /** Get all subscribed zones. */
    public Set<String> subscribedZones() {
        return Set.copyOf(subscribedZones);
    }

    /** Get all active (non-expired) bans. */
    public List<BanEntry> activeBans() {
        return banList.values().stream()
            .filter(BanEntry::isActive)
            .sorted(Comparator.comparing(BanEntry::bannedAt))
            .toList();
    }

    /** Get federation-wide bans only. */
    public List<BanEntry> federationWideBans() {
        return banList.values().stream()
            .filter(BanEntry::isActive)
            .filter(b -> b.scope() == BanScope.FEDERATION_WIDE)
            .toList();
    }

    /** Total ban count (including expired). */
    public int banCount() {
        return banList.size();
    }

    /** Total active ban count. */
    public int activeBanCount() {
        return (int) banList.values().stream().filter(BanEntry::isActive).count();
    }

    /** Total appeal count. */
    public int appealCount() {
        return appeals.size();
    }

    /** Clean expired bans. Returns count removed. */
    public int cleanExpired() {
        var expired = banList.entrySet().stream()
            .filter(e -> e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .toList();
        expired.forEach(banList::remove);
        return expired.size();
    }
}
