package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * JDBC-backed persistence for {@link Residency} rows.
 *
 * <p>Zone-local. Never replicated. Queried on login to branch between
 * Study (resident) and Docks (visitor).</p>
 */
public final class ResidencyStore {

    private static final Logger log = LoggerFactory.getLogger(ResidencyStore.class);

    private static volatile ResidencyStore INSTANCE;

    /** Runtime singleton, set once by {@code Main.java} at startup. */
    public static ResidencyStore get() {
        return INSTANCE;
    }

    /** Called once by {@code Main.java} after construction + migration. */
    public static void setInstance(ResidencyStore store) {
        INSTANCE = store;
    }

    /** Clear the singleton so tests can re-init per run. */
    public static void resetForTests() {
        INSTANCE = null;
    }

    /**
     * Grant residency in the local zone for a freshly created local account
     * Every account minted on this node lives here; without
     * the row, login lands in the Docks as a visitor instead of the Study.
     * Called from {@code AuthService} at account-creation time so no surface
     * (REST, SSH, telnet, /adduser, replication) can miss the grant. No-op
     * when the store or local zone isn't initialised (tests, early boot) —
     * {@link #backfillFromUsers} heals those rows on next start. Callers with
     * a more specific grantor (e.g. {@code invite:<code>}) may re-grant
     * afterwards; {@link #grant} upserts.
     */
    public static void grantLocal(String did, String role, String grantor) {
        var store = INSTANCE;
        if (store == null) return;
        var zoneId = store.localZoneId();
        if (zoneId == null) return;
        store.grant(new Residency(did, zoneId, role, Instant.now(), grantor, null));
    }

    private final String jdbcUrl;
    /** The zone this store was last seen migrating for. Set by backfillFromUsers
     *  so callers who don't carry a zoneId context can still issue grants. */
    private volatile String lastBackfillZone;

    /** The zone this store was last initialised for (set on {@code backfillFromUsers}). */
    public String localZoneId() {
        return lastBackfillZone;
    }

    /**
     * Optional hook fired whenever a residency is granted or back-filled.
     * Wired in {@code Main.java} to provision the resident's Study, seed
     * furnishings, etc. Kept nullable so tests can instantiate the store
     * without wiring the full actor system.
     */
    private volatile Consumer<Residency> provisionHook;

    public ResidencyStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Register the Study-provisioning hook. Nullable — null disables the callback. */
    public void setProvisionHook(Consumer<Residency> hook) {
        this.provisionHook = hook;
    }

    private void fireProvisionHook(Residency r) {
        var hook = provisionHook;
        if (hook == null) return;
        try {
            hook.accept(r);
        } catch (Exception e) {
            log.warn("Residency provision hook failed for {}/{}: {}",
                r.did(), r.zoneId(), e.getMessage());
        }
    }

    /** Insert or update a residency row. PK is (did, zone_id). */
    public void grant(Residency r) {
        var sql = "INSERT INTO residency "
            + "(did, zone_id, role, granted_at, grantor, study_room_id) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (did, zone_id) DO UPDATE SET "
            + "  role = excluded.role, "
            + "  granted_at = excluded.granted_at, "
            + "  grantor = excluded.grantor, "
            + "  study_room_id = excluded.study_room_id";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, r.did());
            st.setString(2, r.zoneId());
            st.setString(3, r.role());
            st.setLong(4, r.grantedAt().getEpochSecond());
            st.setString(5, r.grantor());
            st.setString(6, r.studyRoomId());
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to grant residency {}/{}: {}", r.did(), r.zoneId(), e.getMessage());
            return;
        }
        // Study provisioning fires here, not on login.
        fireProvisionHook(r);
    }

    /** Remove a residency row. Does not touch Study, inventory, or grants;
     *  callers arrange those (archive or cascade delete) separately. */
    public boolean revoke(String did, String zoneId) {
        var sql = "DELETE FROM residency WHERE did = ? AND zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            st.setString(2, zoneId);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to revoke residency {}/{}: {}", did, zoneId, e.getMessage());
            return false;
        }
    }

    public Optional<Residency> get(String did, String zoneId) {
        var sql = "SELECT * FROM residency WHERE did = ? AND zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            st.setString(2, zoneId);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get residency {}/{}: {}", did, zoneId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Hot-path login check: is this identity resident in this zone? */
    public boolean isResident(String did, String zoneId) {
        var sql = "SELECT 1 FROM residency WHERE did = ? AND zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            st.setString(2, zoneId);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed isResident check {}/{}: {}", did, zoneId, e.getMessage());
            return false;
        }
    }

    public List<Residency> listByZone(String zoneId) {
        var sql = "SELECT * FROM residency WHERE zone_id = ? ORDER BY granted_at";
        var out = new ArrayList<Residency>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, zoneId);
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list residency for zone {}: {}", zoneId, e.getMessage());
        }
        return out;
    }

    /** Attach a studyRoomId to an existing residency row (called after
     *  StudyProvisioner runs). No-op if the row doesn't exist. */
    public void setStudyRoomId(String did, String zoneId, String studyRoomId) {
        var sql = "UPDATE residency SET study_room_id = ? WHERE did = ? AND zone_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, studyRoomId);
            st.setString(2, did);
            st.setString(3, zoneId);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to setStudyRoomId {}/{}: {}", did, zoneId, e.getMessage());
        }
    }

    /**
     * §25.6 migration: back-fill residency rows for every existing user in
     * the local {@code users} table who doesn't yet have one. Idempotent —
     * existing residency rows are left alone.
     *
     * @return number of rows back-filled
     */
    public int backfillFromUsers(String zoneId) {
        this.lastBackfillZone = zoneId;
        var sql = "INSERT INTO residency (did, zone_id, role, granted_at, grantor, study_room_id) "
            + "SELECT u.id, ?, u.role, ?, ?, NULL FROM users u "
            + "WHERE NOT EXISTS ("
            + "  SELECT 1 FROM residency r WHERE r.did = u.id AND r.zone_id = ?"
            + ")";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, zoneId);
            st.setLong(2, Instant.now().getEpochSecond());
            st.setString(3, Residency.GRANTOR_MIGRATION);
            st.setString(4, zoneId);
            int n = st.executeUpdate();
            if (n > 0) {
                log.info("Residency: back-filled {} row(s) from users (zone={})", n, zoneId);
                // Fire the provision hook for each back-filled resident so
                // migrated users get a Study via the §25.3 path (not login).
                // Idempotent — ZoneGuardian.ProvisionStudy checks before
                // creating.
                for (var r : listByZone(zoneId)) {
                    if (Residency.GRANTOR_MIGRATION.equals(r.grantor())
                            && r.studyRoomId() == null) {
                        fireProvisionHook(r);
                    }
                }
            }
            return n;
        } catch (SQLException e) {
            log.error("Residency migration failed for zone {}: {}", zoneId, e.getMessage());
            return 0;
        }
    }

    private Residency fromRow(ResultSet rs) throws SQLException {
        return new Residency(
            rs.getString("did"),
            rs.getString("zone_id"),
            rs.getString("role"),
            Instant.ofEpochSecond(rs.getLong("granted_at")),
            rs.getString("grantor"),
            rs.getString("study_room_id")
        );
    }
}
