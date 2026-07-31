package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.SoulStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for the
 * {@code companions} table. Gives companion DIDs a canonical home.
 *
 * <p> canonical: world.db:companions.
 * Before this, companion DIDs appeared in 5 places (legacy
 * {@code souls/*.did} files, {@code souls/*.json:identity.did},
 * {@code soul_manifests.did}, {@code bonds.companion_did},
 * {@code foreign_identities.did}) with no single source of truth.
 * Writers register here on companion birth + load + cross-zone arrival;
 * other tables continue to reference DIDs by string but this is the
 * authoritative directory.
 *
 * <p>Schema (see {@code sqlite-create-schema.sql} /
 * {@code postgresql-create-schema.sql}):
 * <pre>
 *   companions(
 *     did             TEXT PRIMARY KEY,
 *     entity_id       TEXT NOT NULL,
 *     name            TEXT,
 *     home_zone       TEXT,
 *     born_at         INTEGER NOT NULL,
 *     last_seen_at    INTEGER,
 *     archived        INTEGER NOT NULL DEFAULT 0
 *   )
 * </pre>
 */
public final class CompanionRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompanionRegistry.class);

    public record Row(
        String did,
        String entityId,
        String name,
        String homeZone,
        Instant bornAt,
        Instant lastSeenAt,
        boolean archived
    ) {}

    private final String jdbcUrl;

    public CompanionRegistry(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Idempotent register. {@code did} is the PK. {@code entityId} +
     * {@code name} + {@code homeZone} are refreshed on conflict (so a
     * rename or zone-relocation reflects immediately).
     * {@code lastSeenAt} bumps to {@code Instant.now()} on every call.
     */
    public void register(String did, String entityId, String name, String homeZone) {
        if (did == null || did.isBlank()) {
            log.warn("CompanionRegistry.register called with blank did — skipping");
            return;
        }
        if (entityId == null || entityId.isBlank()) {
            log.warn("CompanionRegistry.register called with blank entityId — skipping");
            return;
        }
        var sql = "INSERT INTO companions "
            + "(did, entity_id, name, home_zone, born_at, last_seen_at, archived) "
            + "VALUES (?, ?, ?, ?, ?, ?, 0) "
            + "ON CONFLICT (did) DO UPDATE SET "
            + "  entity_id = excluded.entity_id, "
            + "  name = excluded.name, "
            + "  home_zone = excluded.home_zone, "
            + "  last_seen_at = excluded.last_seen_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            long now = Instant.now().getEpochSecond();
            st.setString(1, did);
            st.setString(2, entityId);
            st.setString(3, name);
            st.setString(4, homeZone);
            st.setLong(5, now);
            st.setLong(6, now);
            st.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to register companion {}: {}", did, e.getMessage());
        }
    }

    /** Touch last_seen_at without mutating other fields. */
    public void touch(String did) {
        if (did == null || did.isBlank()) return;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "UPDATE companions SET last_seen_at = ? WHERE did = ?")) {
            st.setLong(1, Instant.now().getEpochSecond());
            st.setString(2, did);
            st.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to touch companion {}: {}", did, e.getMessage());
        }
    }

    /** Mark a companion archived (soft-delete). */
    public void archive(String did) {
        if (did == null || did.isBlank()) return;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "UPDATE companions SET archived = 1 WHERE did = ?")) {
            st.setString(1, did);
            st.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to archive companion {}: {}", did, e.getMessage());
        }
    }

    public Optional<Row> get(String did) {
        if (did == null || did.isBlank()) return Optional.empty();
        var sql = "SELECT entity_id, name, home_zone, born_at, last_seen_at, archived "
            + "FROM companions WHERE did = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rowFromRs(did, rs, 1));
            }
        } catch (Exception e) {
            log.error("Failed to get companion {}: {}", did, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<Row> findByEntityId(String entityId) {
        if (entityId == null || entityId.isBlank()) return Optional.empty();
        var sql = "SELECT did, entity_id, name, home_zone, born_at, last_seen_at, archived "
            + "FROM companions WHERE entity_id = ? AND archived = 0 LIMIT 1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, entityId);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Row(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4),
                    Instant.ofEpochSecond(rs.getLong(5)),
                    optInstant(rs, 6),
                    rs.getInt(7) != 0));
            }
        } catch (Exception e) {
            log.error("Failed to find companion by entityId {}: {}", entityId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Row> all() {
        var out = new ArrayList<Row>();
        var sql = "SELECT did, entity_id, name, home_zone, born_at, last_seen_at, archived "
            + "FROM companions ORDER BY born_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql);
             var rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(new Row(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4),
                    Instant.ofEpochSecond(rs.getLong(5)),
                    optInstant(rs, 6),
                    rs.getInt(7) != 0));
            }
        } catch (SQLException e) {
            log.warn("companions scan failed: {}", e.getMessage());
        }
        return out;
    }

    public int count() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM companions WHERE archived = 0");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("companions count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Walk every active soul_manifests row and register the companion.
     * Idempotent — uses upsert semantics on did. Run at startup so legacy
     * companions (born before this table existed) get a row.
     *
     * @return number of newly written rows
     */
    public int backfillFromManifests(SoulStore manifestStore, String localZone) {
        int written = 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "SELECT DISTINCT did FROM soul_manifests WHERE archived = 0");
             var rs = st.executeQuery()) {
            while (rs.next()) {
                var did = rs.getString(1);
                if (didExists(conn, did)) continue;
                var manifestOpt = manifestStore.latest(did);
                if (manifestOpt.isEmpty()) continue;
                var manifest = manifestOpt.get();
                var profile = manifest.profile();
                if (profile == null) continue;
                register(did, profile.entityId(),
                    profile.name(), localZone);
                written++;
            }
        } catch (SQLException e) {
            log.warn("companions backfill query failed: {}", e.getMessage());
        }
        if (written > 0) {
            log.info("CompanionRegistry: backfilled {} companion(s) from soul_manifests", written);
        }
        return written;
    }

    private boolean didExists(Connection conn, String did) throws SQLException {
        try (var st = conn.prepareStatement(
                "SELECT 1 FROM companions WHERE did = ? LIMIT 1")) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Row rowFromRs(String did, ResultSet rs, int startIdx) throws SQLException {
        return new Row(
            did,
            rs.getString(startIdx),
            rs.getString(startIdx + 1),
            rs.getString(startIdx + 2),
            Instant.ofEpochSecond(rs.getLong(startIdx + 3)),
            optInstant(rs, startIdx + 4),
            rs.getInt(startIdx + 5) != 0
        );
    }

    private static Instant optInstant(ResultSet rs, int idx) throws SQLException {
        long v = rs.getLong(idx);
        return rs.wasNull() ? null : Instant.ofEpochSecond(v);
    }
}
