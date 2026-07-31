package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * JDBC-backed persistence for signed identity outbox records.
 *
 * <p>One row per DID. Wire format ({@link IdentityOutboxRecord#toWireJson}) lives
 * in {@code record_json}. {@code updated_at} (sender wall-clock, unix-ms) drives
 * latest-wins on {@link #upsertIfNewer}. {@code received_at} is local wall-clock
 * for debug/ordering.
 *
 * <p>Signature verification is the caller's responsibility — this store does not
 * verify on every write (would be expensive). The publishing path
 * ({@code IdentityOutboxRoutes#handlePut}) verifies before calling
 * {@link #upsertIfNewer}.
 */
public final class IdentityOutboxStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityOutboxStore.class);

    private final String jdbcUrl;

    public IdentityOutboxStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Insert if absent, replace if {@code record.updatedAt > existing.updatedAt}.
     * @return {@link UpsertResult#INSERTED}, {@link UpsertResult#REPLACED}, or
     *         {@link UpsertResult#STALE} (existing row has higher updatedAt).
     */
    public UpsertResult upsertIfNewer(IdentityOutboxRecord record) {
        if (record == null || record.did() == null || record.did().isBlank()) {
            throw new IllegalArgumentException("record.did is required");
        }
        var existingTs = existingUpdatedAt(record.did());
        if (existingTs.isPresent() && existingTs.getAsLong() >= record.updatedAt()) {
            return UpsertResult.STALE;
        }
        var json = record.toWireJson();
        var now = System.currentTimeMillis();
        // INSERT...ON CONFLICT works on both SQLite and Postgres (9.5+).
        var sql = "INSERT INTO identity_outbox(did, record_json, updated_at, received_at) "
            + "VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (did) DO UPDATE SET "
            + "  record_json = excluded.record_json, "
            + "  updated_at = excluded.updated_at, "
            + "  received_at = excluded.received_at";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, record.did());
            st.setString(2, json);
            st.setLong(3, record.updatedAt());
            st.setLong(4, now);
            st.executeUpdate();
            return existingTs.isPresent() ? UpsertResult.REPLACED : UpsertResult.INSERTED;
        } catch (SQLException e) {
            log.error("Failed to upsert outbox for {}: {}", record.did(), e.getMessage());
            throw new RuntimeException("upsert failed", e);
        }
    }

    /** Fetch by DID, or empty if absent. */
    public Optional<IdentityOutboxRecord> get(String did) {
        var sql = "SELECT record_json FROM identity_outbox WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    return IdentityOutboxRecord.fromWireJson(rs.getString("record_json"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get outbox for {}: {}", did, e.getMessage());
        }
        return Optional.empty();
    }

    /** Most recently received first. */
    public List<IdentityOutboxRecord> listAll() {
        var sql = "SELECT record_json FROM identity_outbox ORDER BY received_at DESC";
        var out = new ArrayList<IdentityOutboxRecord>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql);
             var rs = st.executeQuery()) {
            while (rs.next()) {
                IdentityOutboxRecord.fromWireJson(rs.getString("record_json")).ifPresent(out::add);
            }
        } catch (SQLException e) {
            log.error("Failed to list outbox: {}", e.getMessage());
        }
        return out;
    }

    /** Delete by DID. Mostly for tests and cleanup. Returns true if a row was removed. */
    public boolean delete(String did) {
        var sql = "DELETE FROM identity_outbox WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete outbox for {}: {}", did, e.getMessage());
            return false;
        }
    }

    private OptionalLong existingUpdatedAt(String did) {
        var sql = "SELECT updated_at FROM identity_outbox WHERE did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return OptionalLong.of(rs.getLong("updated_at"));
            }
        } catch (SQLException e) {
            log.warn("Failed to read existing outbox updated_at for {}: {}", did, e.getMessage());
        }
        return OptionalLong.empty();
    }

    public enum UpsertResult { INSERTED, REPLACED, STALE }
}
