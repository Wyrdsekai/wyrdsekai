package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * World DNA pattern accumulation service (§27).
 * Records creation patterns, queries top patterns by type/zone,
 * and tracks outcome scores for co-evolution.
 */
public final class WorldDnaService {

    private static final Logger log = LoggerFactory.getLogger(WorldDnaService.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public record DnaPattern(
        String id,
        String patternType,
        String patternData,
        String sourceRoomId,
        String sourceAgentId,
        String zoneId,
        long observedAt,
        double outcomeScore,
        int usageCount,
        Long lastUsedAt
    ) {}

    public WorldDnaService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public WorldDnaService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /**
     * Record a new pattern observation.
     */
    public String record(String patternType, String patternData,
                         String sourceRoomId, String sourceAgentId, String zoneId) {
        var id = UUID.randomUUID().toString();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = dialect.insertIgnore("world_dna",
                "id, pattern_type, pattern_data, source_room_id, source_agent_id, zone_id, observed_at",
                "?, ?, ?, ?, ?, ?, ?");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, patternType);
                stmt.setString(3, patternData);
                stmt.setString(4, sourceRoomId);
                stmt.setString(5, sourceAgentId);
                stmt.setString(6, zoneId);
                stmt.setLong(7, Instant.now().getEpochSecond());
                stmt.executeUpdate();
            }
            log.debug("Recorded {} pattern from room {} by agent {}",
                patternType, sourceRoomId, sourceAgentId);
        } catch (SQLException e) {
            throw new RuntimeException("World DNA record failed", e);
        }
        return id;
    }

    /**
     * Query top patterns by type and zone, ordered by outcome_score descending.
     */
    public List<DnaPattern> queryTopPatterns(String patternType, String zoneId, int limit) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT id, pattern_type, pattern_data, source_room_id, source_agent_id, "
                + "zone_id, observed_at, outcome_score, usage_count, last_used_at "
                + "FROM world_dna WHERE pattern_type = ? AND zone_id = ? "
                + "ORDER BY outcome_score DESC LIMIT ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, patternType);
                stmt.setString(2, zoneId);
                stmt.setInt(3, limit);
                var rs = stmt.executeQuery();
                var patterns = new ArrayList<DnaPattern>();
                while (rs.next()) {
                    patterns.add(new DnaPattern(
                        rs.getString("id"),
                        rs.getString("pattern_type"),
                        rs.getString("pattern_data"),
                        rs.getString("source_room_id"),
                        rs.getString("source_agent_id"),
                        rs.getString("zone_id"),
                        rs.getLong("observed_at"),
                        rs.getDouble("outcome_score"),
                        rs.getInt("usage_count"),
                        rs.getObject("last_used_at") != null
                            ? rs.getLong("last_used_at") : null
                    ));
                }
                return patterns;
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA query failed", e);
        }
    }

    /**
     * Update the outcome score for a pattern.
     */
    public void updateScore(String id, double newScore) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE world_dna SET outcome_score = ? WHERE id = ?")) {
                stmt.setDouble(1, newScore);
                stmt.setString(2, id);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA score update failed", e);
        }
    }

    /**
     * Increment usage count and update last_used_at timestamp.
     */
    public void incrementUsage(String id) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE world_dna SET usage_count = usage_count + 1, last_used_at = ? WHERE id = ?")) {
                stmt.setLong(1, Instant.now().getEpochSecond());
                stmt.setString(2, id);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA usage increment failed", e);
        }
    }

    /**
     * Count patterns by type.
     */
    public int countByType(String patternType) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM world_dna WHERE pattern_type = ?")) {
                stmt.setString(1, patternType);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA count failed", e);
        }
    }

    /**
     * Count all patterns.
     */
    public int countAll() {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM world_dna");
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA count failed", e);
        }
    }

    /**
     * Record an outcome for a pattern (§27 outcome tracking).
     * Updates outcome_score using exponential moving average (alpha=0.2).
     * Positive outcomes push score toward 1.0, negative toward 0.0.
     */
    public void recordOutcome(String id, boolean positive) {
        double alpha = 0.2;
        double newValue = positive ? 1.0 : 0.0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            // EMA: score = (1 - alpha) * score + alpha * newValue
            try (var stmt = conn.prepareStatement(
                    "UPDATE world_dna SET outcome_score = (1 - ?) * outcome_score + ? * ?, "
                    + "usage_count = usage_count + 1, last_used_at = ? WHERE id = ?")) {
                stmt.setDouble(1, alpha);
                stmt.setDouble(2, alpha);
                stmt.setDouble(3, newValue);
                stmt.setLong(4, Instant.now().getEpochSecond());
                stmt.setString(5, id);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA outcome recording failed", e);
        }
    }

    /**
     * Query top patterns by usage count (popularity-based).
     * Useful for finding commonly-used patterns across zones.
     */
    public List<DnaPattern> queryByUsage(String patternType, int limit) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT id, pattern_type, pattern_data, source_room_id, source_agent_id, "
                + "zone_id, observed_at, outcome_score, usage_count, last_used_at "
                + "FROM world_dna WHERE pattern_type = ? "
                + "ORDER BY usage_count DESC, outcome_score DESC LIMIT ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, patternType);
                stmt.setInt(2, limit);
                var rs = stmt.executeQuery();
                var patterns = new ArrayList<DnaPattern>();
                while (rs.next()) {
                    patterns.add(new DnaPattern(
                        rs.getString("id"),
                        rs.getString("pattern_type"),
                        rs.getString("pattern_data"),
                        rs.getString("source_room_id"),
                        rs.getString("source_agent_id"),
                        rs.getString("zone_id"),
                        rs.getLong("observed_at"),
                        rs.getDouble("outcome_score"),
                        rs.getInt("usage_count"),
                        rs.getObject("last_used_at") != null
                            ? rs.getLong("last_used_at") : null
                    ));
                }
                return patterns;
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA usage query failed", e);
        }
    }

    /**
     * Query recently active patterns (used within timeWindow seconds ago).
     */
    public List<DnaPattern> queryRecentlyUsed(long withinSeconds, int limit) {
        var cutoff = Instant.now().getEpochSecond() - withinSeconds;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            var sql = "SELECT id, pattern_type, pattern_data, source_room_id, source_agent_id, "
                + "zone_id, observed_at, outcome_score, usage_count, last_used_at "
                + "FROM world_dna WHERE last_used_at IS NOT NULL AND last_used_at > ? "
                + "ORDER BY last_used_at DESC LIMIT ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, cutoff);
                stmt.setInt(2, limit);
                var rs = stmt.executeQuery();
                var patterns = new ArrayList<DnaPattern>();
                while (rs.next()) {
                    patterns.add(new DnaPattern(
                        rs.getString("id"),
                        rs.getString("pattern_type"),
                        rs.getString("pattern_data"),
                        rs.getString("source_room_id"),
                        rs.getString("source_agent_id"),
                        rs.getString("zone_id"),
                        rs.getLong("observed_at"),
                        rs.getDouble("outcome_score"),
                        rs.getInt("usage_count"),
                        rs.getLong("last_used_at")
                    ));
                }
                return patterns;
            }
        } catch (SQLException e) {
            throw new RuntimeException("World DNA recent query failed", e);
        }
    }
}
