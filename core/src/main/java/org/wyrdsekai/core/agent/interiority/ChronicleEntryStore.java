package org.wyrdsekai.core.agent.interiority;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC store for {@link ChronicleEntry}.
 *
 * <p>Append-only log keyed by {@code (did, ts)}. Cheap-to-read for the
 * Study Chronicle furnishing (recent-window scan) and C7
 * recipes console (RECIPE_RUN filter). Lazy
 * {@link #ensureMigrated(Connection)} on first use so old DBs upgrade
 * in place — same idiom as {@link
 * org.wyrdsekai.core.recipe.SqlRecipeQueue}.</p>
 */
public final class ChronicleEntryStore {

    private static final Logger log = LoggerFactory.getLogger(ChronicleEntryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> DATA_TYPE =
        new TypeReference<>() {};

    private static volatile ChronicleEntryStore INSTANCE;

    /**
     * Process singleton. Production wires once at boot via
     * {@link #setInstance}; the CompanionActor sleep pass + Study
     * furnishings consult via {@link #get()}. Returns {@code null} when
     * unwired; callers must null-check (sleep pass already does — see
     * CompanionActor recipe-Forge block).
     */
    public static ChronicleEntryStore get() { return INSTANCE; }
    public static void setInstance(ChronicleEntryStore s) { INSTANCE = s; }
    public static void resetForTests() { INSTANCE = null; }

    private final String jdbcUrl;
    private volatile boolean migrated;

    public ChronicleEntryStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void append(ChronicleEntry entry) {
        if (entry == null) return;
        var sql = "INSERT INTO chronicle_entries "
            + "(did, ts, kind, summary, data_json) VALUES (?,?,?,?,?)";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, entry.agentDid());
                st.setLong(2, entry.ts().toEpochMilli());
                st.setString(3, entry.kind().name());
                if (entry.summary() == null) st.setNull(4, Types.VARCHAR);
                else st.setString(4, entry.summary());
                try {
                    st.setString(5, MAPPER.writeValueAsString(
                        entry.data() == null ? Map.of() : entry.data()));
                } catch (Exception jsonEx) {
                    st.setString(5, "{}");
                }
                st.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("ChronicleEntryStore.append({}, {}) failed: {}",
                entry.agentDid(), entry.kind(), e.getMessage());
        }
    }

    /** Recent entries for a DID in the given window, newest-first. */
    public List<ChronicleEntry> recent(String agentDid, Duration window, int limit) {
        if (agentDid == null) return List.of();
        var since = Instant.now().minus(window == null ? Duration.ofDays(7) : window);
        var sql = "SELECT * FROM chronicle_entries "
            + "WHERE did = ? AND ts >= ? "
            + "ORDER BY ts DESC LIMIT ?";
        var out = new ArrayList<ChronicleEntry>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, agentDid);
                st.setLong(2, since.toEpochMilli());
                st.setInt(3, limit > 0 ? limit : 100);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("ChronicleEntryStore.recent({}) failed: {}", agentDid, e.getMessage());
        }
        return out;
    }

    /** Recent entries for a DID filtered by kind. */
    public List<ChronicleEntry> recentByKind(String agentDid, ChronicleEntry.Kind kind,
            Duration window, int limit) {
        if (agentDid == null || kind == null) return List.of();
        var since = Instant.now().minus(window == null ? Duration.ofDays(7) : window);
        var sql = "SELECT * FROM chronicle_entries "
            + "WHERE did = ? AND kind = ? AND ts >= ? "
            + "ORDER BY ts DESC LIMIT ?";
        var out = new ArrayList<ChronicleEntry>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, agentDid);
                st.setString(2, kind.name());
                st.setLong(3, since.toEpochMilli());
                st.setInt(4, limit > 0 ? limit : 100);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("ChronicleEntryStore.recentByKind({},{}) failed: {}",
                agentDid, kind, e.getMessage());
        }
        return out;
    }

    void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "chronicle_entries")) {
            log.info("Creating chronicle_entries table (Track-C C5 migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chronicle_entries(
                      did       TEXT NOT NULL,
                      ts        INTEGER NOT NULL,
                      kind      TEXT NOT NULL,
                      summary   TEXT,
                      data_json TEXT NOT NULL DEFAULT '{}'
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chronicle_entries_did_ts "
                    + "ON chronicle_entries(did, ts DESC)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chronicle_entries_kind "
                    + "ON chronicle_entries(did, kind, ts DESC)");
            }
        }
        migrated = true;
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static ChronicleEntry read(ResultSet rs) throws SQLException {
        var rawJson = rs.getString("data_json");
        Map<String, Object> data;
        try {
            data = rawJson == null || rawJson.isBlank()
                ? new LinkedHashMap<>()
                : MAPPER.readValue(rawJson, DATA_TYPE);
        } catch (Exception ex) {
            data = new LinkedHashMap<>();
        }
        ChronicleEntry.Kind kind;
        try { kind = ChronicleEntry.Kind.valueOf(rs.getString("kind")); }
        catch (Exception e) { kind = ChronicleEntry.Kind.NOTE; }
        return new ChronicleEntry(
            rs.getString("did"),
            Instant.ofEpochMilli(rs.getLong("ts")),
            kind, rs.getString("summary"), data);
    }
}
