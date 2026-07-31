package org.wyrdsekai.core.agent.interiority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.memory.MemoryEntityStore;
import org.wyrdsekai.core.memory.MemoryEntityStore.EntityRow;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * introspection surface available during Orient.
 *
 * <p>The drive-OODA loop may invoke any of these to bring more context into
 * the wanting step. They are <em>tools the agent uses</em>, not pre-populated
 * fields. The agent decides whether to pull more, after seeing the ambient
 * observation.
 *
 * <ul>
 *   <li>{@link #recallShort}  — memory_entities rows from the last hour
 *   <li>{@link #recallMedium} — last 7 days
 *   <li>{@link #recallLong}   — last 30 days (or as far back as the store has)
 *   <li>{@link #recallRandom} — N random pulls across all of the agent's memory
 *   <li>{@link #recallThread} — memory_entities matching a specific named value
 * </ul>
 *
 * <p>Each returns a list of short string descriptors — the OODA loop can feed
 * them directly into the Orient prompt without further marshalling. Backed by
 * the {@code memory_entities} table via {@link MemoryEntityStore}; resilient
 * to a missing store (returns empty list).
 */
public final class IntrospectionTools {

    private static final Logger log = LoggerFactory.getLogger(IntrospectionTools.class);

    private static final long HOUR_MS  = 60L * 60 * 1000;
    private static final long DAY_MS   = 24L * HOUR_MS;
    private static final long WEEK_MS  = 7L * DAY_MS;
    private static final long MONTH_MS = 30L * DAY_MS;

    private final String jdbcUrl;
    private final MemoryEntityStore entityStore;

    public IntrospectionTools(String jdbcUrl, MemoryEntityStore entityStore) {
        this.jdbcUrl = jdbcUrl;
        this.entityStore = entityStore;
    }

    /** Memory rows in the last hour for {@code agentDid}, most recent first. */
    public List<String> recallShort(String agentDid, int limit) {
        return queryWindow(agentDid, HOUR_MS, limit, "recallShort");
    }

    /** Memory rows in the last 7 days. */
    public List<String> recallMedium(String agentDid, int limit) {
        return queryWindow(agentDid, WEEK_MS, limit, "recallMedium");
    }

    /** Memory rows in the last 30 days — effectively a lifetime probe. */
    public List<String> recallLong(String agentDid, int limit) {
        return queryWindow(agentDid, MONTH_MS, limit, "recallLong");
    }

    /**
     * N random pulls across the agent's full memory horizon — the "wandering
     * mind" surface. Used by the OODA loop on dice-roll ticks ({@link MemoryPullPolicy}).
     */
    public List<String> recallRandom(String agentDid, int n) {
        if (jdbcUrl == null || agentDid == null || n <= 0) return List.of();
        // Pull a generous candidate pool, then shuffle to N. Bounded so memory
        // stays small even for long-lived agents.
        var sql = "SELECT entity_type, entity_role, entity_value, timestamp "
            + "FROM memory_entities WHERE did = ? "
            + "ORDER BY timestamp DESC LIMIT 500";
        var pool = new ArrayList<String>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    pool.add(truncate(renderRow(
                        rs.getString("entity_type"),
                        rs.getString("entity_role"),
                        rs.getString("entity_value")), 160));
                }
            }
        } catch (SQLException e) {
            log.debug("recallRandom({}) failed: {}", agentDid, e.getMessage());
            return List.of();
        }
        if (pool.isEmpty()) return List.of();
        Collections.shuffle(pool, ThreadLocalRandom.current());
        return new ArrayList<>(pool.subList(0, Math.min(n, pool.size())));
    }

    /**
     * Pull memories tied to a specific named thread — a person, a project, a topic.
     * Implemented via the entity store's value-match.
     */
    public List<String> recallThread(String agentDid, String threadName, int limit) {
        if (entityStore == null || agentDid == null || threadName == null) return List.of();
        try {
            var rows = entityStore.findByValue(agentDid, threadName, Math.max(1, limit));
            var out = new ArrayList<String>(rows.size());
            for (var r : rows) out.add(truncate(renderRow(r), 160));
            return out;
        } catch (Exception e) {
            log.debug("recallThread({}, {}) failed: {}", agentDid, threadName, e.getMessage());
            return List.of();
        }
    }

    // ─── internals ────────────────────────────────────────────────────────

    private List<String> queryWindow(String agentDid, long windowMs, int limit, String which) {
        if (jdbcUrl == null || agentDid == null || limit <= 0) return List.of();
        var cutoff = System.currentTimeMillis() - windowMs;
        var sql = "SELECT entity_type, entity_role, entity_value, timestamp "
            + "FROM memory_entities "
            + "WHERE did = ? AND timestamp >= ? "
            + "ORDER BY timestamp DESC LIMIT ?";
        var out = new ArrayList<String>(limit);
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setLong(2, cutoff);
            st.setInt(3, limit);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(truncate(renderRow(
                        rs.getString("entity_type"),
                        rs.getString("entity_role"),
                        rs.getString("entity_value")), 160));
                }
            }
        } catch (SQLException e) {
            log.debug("{}({}) failed: {}", which, agentDid, e.getMessage());
            return List.of();
        }
        return out;
    }

    private static String renderRow(EntityRow row) {
        return renderRow(row.entityType(), row.entityRole(), row.entityValue());
    }

    private static String renderRow(String type, String role, String value) {
        var sb = new StringBuilder();
        if (type != null && !type.isBlank()) sb.append(type);
        if (role != null && !role.isBlank()) {
            if (sb.length() > 0) sb.append('/');
            sb.append(role);
        }
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append(": ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
