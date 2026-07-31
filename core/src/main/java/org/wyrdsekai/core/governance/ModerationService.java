package org.wyrdsekai.core.governance;

import org.wyrdsekai.common.i18n.I18n;

import org.wyrdsekai.core.persistence.ModerationPersistence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Content moderation service (§32).
 * Manages reports, graduated sanctions, and resolution tracking.
 * Supports optional JDBC persistence via ModerationPersistence.
 */
public class ModerationService {

    public enum ReportStatus { OPEN, INVESTIGATING, RESOLVED, DISMISSED }

    public enum SanctionLevel {
        NONE(0), WARNING(1), PROBATION(2), SUSPENSION(3), BAN(4);
        private final int severity;
        SanctionLevel(int severity) { this.severity = severity; }
        public int severity() { return severity; }

        public SanctionLevel escalate() {
            return switch (this) {
                case NONE -> WARNING;
                case WARNING -> PROBATION;
                case PROBATION -> SUSPENSION;
                case SUSPENSION -> BAN;
                case BAN -> BAN;
            };
        }
    }

    public record Report(
        String id,
        String reporterEntity,
        String targetEntity,
        String reason,
        String roomId,
        ReportStatus status,
        Instant createdAt,
        String resolution
    ) {}

    public record Sanction(
        String entityId,
        SanctionLevel level,
        String reason,
        Instant appliedAt,
        Instant expiresAt  // null = permanent
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        public boolean isActive() {
            return level != SanctionLevel.NONE && !isExpired();
        }
    }

    // W5 (2026-07-11): process-wide holder so session actors can file the
    // reports users submit (`report <name>`). Main's wiring block installs the
    // one it builds; unset → callers keep their ack-only behavior. Mirrors
    // StewardAuditLog.register/get.
    private static volatile ModerationService instance;

    /** Called once from Main's wiring block. */
    public static void install(ModerationService s) { instance = s; }

    /** The installed instance, or null if none (tests / bare boots). */
    public static ModerationService get() { return instance; }

    /** Tests only. */
    public static void resetForTests() { instance = null; }

    private final Map<String, Report> reports = new ConcurrentHashMap<>();
    private final Map<String, Sanction> sanctions = new ConcurrentHashMap<>();
    private final ModerationPersistence persistence; // nullable
    private int nextReportId = 1;

    /** In-memory only (no persistence). */
    public ModerationService() {
        this.persistence = null;
    }

    /** With JDBC persistence. */
    public ModerationService(ModerationPersistence persistence) {
        this.persistence = persistence;
    }

    /** File a new report. */
    public Report fileReport(String reporter, String target, String reason, String roomId) {
        var id = "report-" + nextReportId++;
        var report = new Report(id, reporter, target, reason, roomId,
            ReportStatus.OPEN, Instant.now(), null);
        reports.put(id, report);
        if (persistence != null) persistence.saveReport(report);
        return report;
    }

    /** Update report status. */
    public Optional<Report> resolveReport(String reportId, ReportStatus status, String resolution) {
        var r = reports.get(reportId);
        if (r == null) return Optional.empty();
        var updated = new Report(r.id(), r.reporterEntity(), r.targetEntity(),
            r.reason(), r.roomId(), status, r.createdAt(), resolution);
        reports.put(reportId, updated);
        if (persistence != null) persistence.saveReport(updated);
        return Optional.of(updated);
    }

    /** Get reports for a target entity. */
    public List<Report> reportsFor(String targetEntity) {
        return reports.values().stream()
            .filter(r -> r.targetEntity().equals(targetEntity))
            .sorted(Comparator.comparing(Report::createdAt).reversed())
            .toList();
    }

    /** Get open reports. */
    public List<Report> openReports() {
        return reports.values().stream()
            .filter(r -> r.status() == ReportStatus.OPEN || r.status() == ReportStatus.INVESTIGATING)
            .sorted(Comparator.comparing(Report::createdAt).reversed())
            .toList();
    }

    /** Apply or escalate a sanction on an entity. */
    public Sanction applySanction(String entityId, SanctionLevel level, String reason,
                                   Instant expiresAt) {
        var sanction = new Sanction(entityId, level, reason, Instant.now(), expiresAt);
        sanctions.put(entityId, sanction);
        if (persistence != null) persistence.saveSanction(sanction);
        return sanction;
    }

    /** Escalate: apply the next sanction level above current. */
    public Sanction escalate(String entityId, String reason) {
        var current = getSanction(entityId);
        var nextLevel = current.level().escalate();
        return applySanction(entityId, nextLevel, reason, null);
    }

    /** Lift a sanction (set to NONE). */
    public void liftSanction(String entityId) {
        var lifted = new Sanction(entityId, SanctionLevel.NONE,
            "lifted", Instant.now(), null);
        sanctions.put(entityId, lifted);
        if (persistence != null) persistence.saveSanction(lifted);
    }

    /** Get current sanction for an entity. */
    public Sanction getSanction(String entityId) {
        var s = sanctions.get(entityId);
        if (s == null) return new Sanction(entityId, SanctionLevel.NONE, "", Instant.now(), null);
        if (s.isExpired()) {
            liftSanction(entityId);
            return new Sanction(entityId, SanctionLevel.NONE, "expired", Instant.now(), null);
        }
        return s;
    }

    /** Check if entity is banned. */
    public boolean isBanned(String entityId) {
        return getSanction(entityId).level() == SanctionLevel.BAN;
    }

    /** Check if entity is suspended or banned. */
    public boolean isRestricted(String entityId) {
        var level = getSanction(entityId).level();
        return level == SanctionLevel.SUSPENSION || level == SanctionLevel.BAN;
    }

    /** Total report count. */
    public int reportCount() {
        return reports.size();
    }

    /** Total sanction count (active). */
    public int activeSanctionCount() {
        return (int) sanctions.values().stream().filter(Sanction::isActive).count();
    }

    /** Human-readable summary. */
    public String describe() {
        var open = openReports();
        if (open.isEmpty() && sanctions.isEmpty()) {
            return I18n.get("moderation.no_reports");
        }
        var sb = new StringBuilder("=== ").append(I18n.get("moderation.title")).append(" ===\n\n");
        sb.append(I18n.get("moderation.open_reports")).append(": ").append(open.size()).append("\n");
        sb.append(I18n.get("moderation.active_sanctions")).append(": ").append(activeSanctionCount()).append("\n");

        if (!open.isEmpty()) {
            sb.append("\n").append(I18n.get("moderation.recent_reports")).append(":\n");
            for (var r : open.stream().limit(5).toList()) {
                sb.append("  [").append(r.id()).append("] ")
                    .append(r.targetEntity()).append(" — ").append(r.reason())
                    .append(" (").append(r.status()).append(")\n");
            }
        }

        return sb.toString().stripTrailing();
    }
}
