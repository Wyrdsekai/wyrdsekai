package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.ModerationService.Report;
import org.wyrdsekai.core.governance.ModerationService.ReportStatus;
import org.wyrdsekai.core.governance.ModerationService.Sanction;
import org.wyrdsekai.core.governance.ModerationService.SanctionLevel;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for moderation reports and sanctions.
 * Follows the SqlDialect pattern for SQLite/PostgreSQL portability.
 */
public final class ModerationPersistence {

    private static final Logger log = LoggerFactory.getLogger(ModerationPersistence.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public ModerationPersistence(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public ModerationPersistence(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    // --- Reports ---

    public void saveReport(Report report) {
        var sql = dialect.upsert("moderation_reports",
            "id, reporter_entity, target_entity, reason, room_id, status, resolution, created_at, resolved_at",
            "?, ?, ?, ?, ?, ?, ?, ?, ?",
            "id",
            "status = EXCLUDED.status, resolution = EXCLUDED.resolution, resolved_at = EXCLUDED.resolved_at");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, report.id());
            stmt.setString(2, report.reporterEntity());
            stmt.setString(3, report.targetEntity());
            stmt.setString(4, report.reason());
            stmt.setString(5, report.roomId());
            stmt.setString(6, report.status().name());
            stmt.setString(7, report.resolution());
            stmt.setLong(8, report.createdAt().getEpochSecond());
            stmt.setObject(9, report.resolution() != null ? Instant.now().getEpochSecond() : null);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save report", e);
        }
    }

    public Optional<Report> loadReport(String reportId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM moderation_reports WHERE id = ?")) {
            stmt.setString(1, reportId);
            var rs = stmt.executeQuery();
            if (rs.next()) return Optional.of(readReport(rs));
            return Optional.empty();
        } catch (SQLException e) {
            log.error("Failed to load report {}: {}", reportId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Report> reportsFor(String targetEntity) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM moderation_reports WHERE target_entity = ? ORDER BY created_at DESC")) {
            stmt.setString(1, targetEntity);
            return readReports(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query reports", e);
        }
    }

    public List<Report> openReports() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM moderation_reports WHERE status IN ('OPEN', 'INVESTIGATING') ORDER BY created_at")) {
            return readReports(stmt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query open reports", e);
        }
    }

    public int reportCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM moderation_reports");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count reports", e);
        }
    }

    // --- Sanctions ---

    public void saveSanction(Sanction sanction) {
        var sql = dialect.upsert("moderation_sanctions",
            "entity_id, level, reason, applied_at, expires_at",
            "?, ?, ?, ?, ?",
            "entity_id",
            "level = EXCLUDED.level, reason = EXCLUDED.reason, applied_at = EXCLUDED.applied_at, expires_at = EXCLUDED.expires_at");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sanction.entityId());
            stmt.setString(2, sanction.level().name());
            stmt.setString(3, sanction.reason());
            stmt.setLong(4, sanction.appliedAt().getEpochSecond());
            stmt.setObject(5, sanction.expiresAt() != null ? sanction.expiresAt().getEpochSecond() : null);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save sanction", e);
        }
    }

    public Optional<Sanction> loadSanction(String entityId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT * FROM moderation_sanctions WHERE entity_id = ?")) {
            stmt.setString(1, entityId);
            var rs = stmt.executeQuery();
            if (rs.next()) return Optional.of(readSanction(rs));
            return Optional.empty();
        } catch (SQLException e) {
            log.error("Failed to load sanction for {}: {}", entityId, e.getMessage());
            return Optional.empty();
        }
    }

    public void deleteSanction(String entityId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "DELETE FROM moderation_sanctions WHERE entity_id = ?")) {
            stmt.setString(1, entityId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete sanction", e);
        }
    }

    public int activeSanctionCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM moderation_sanctions WHERE level != 'NONE'");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count sanctions", e);
        }
    }

    // --- Helpers ---

    private List<Report> readReports(PreparedStatement stmt) throws SQLException {
        var reports = new ArrayList<Report>();
        var rs = stmt.executeQuery();
        while (rs.next()) {
            reports.add(readReport(rs));
        }
        return reports;
    }

    private static Report readReport(ResultSet rs) throws SQLException {
        return new Report(
            rs.getString("id"),
            rs.getString("reporter_entity"),
            rs.getString("target_entity"),
            rs.getString("reason"),
            rs.getString("room_id"),
            ReportStatus.valueOf(rs.getString("status")),
            Instant.ofEpochSecond(rs.getLong("created_at")),
            rs.getString("resolution")
        );
    }

    private static Sanction readSanction(ResultSet rs) throws SQLException {
        long expiresAt = rs.getLong("expires_at");
        return new Sanction(
            rs.getString("entity_id"),
            SanctionLevel.valueOf(rs.getString("level")),
            rs.getString("reason"),
            Instant.ofEpochSecond(rs.getLong("applied_at")),
            rs.wasNull() || expiresAt == 0 ? null : Instant.ofEpochSecond(expiresAt)
        );
    }
}
