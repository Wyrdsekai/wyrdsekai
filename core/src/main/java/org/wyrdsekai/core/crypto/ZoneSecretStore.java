package org.wyrdsekai.core.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;

/**
 * foundation — JDBC store for {@code zone_wrapped_secrets}: this node's per-zone
 * master secret, wrapped under its node KEK (never plaintext at rest). Mirrors the
 * {@link org.wyrdsekai.core.identity.HouseholdStore} pattern. One row per (zone, node).
 */
public final class ZoneSecretStore {

    private static final Logger log = LoggerFactory.getLogger(ZoneSecretStore.class);

    private final String jdbcUrl;

    public ZoneSecretStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Idempotent upsert of the wrapped master for (zone, node). */
    public void put(String zoneId, String nodeId, byte[] wrappedSecret) {
        if (zoneId == null || zoneId.isBlank() || nodeId == null || nodeId.isBlank()
            || wrappedSecret == null || wrappedSecret.length == 0) {
            log.warn("ZoneSecretStore.put skipped — blank zone/node or empty wrapped secret");
            return;
        }
        var sql = "INSERT INTO zone_wrapped_secrets (zone_id, node_id, wrapped_secret, created_at) "
            + "VALUES (?, ?, ?, strftime('%s','now')) "
            + "ON CONFLICT (zone_id, node_id) DO UPDATE SET wrapped_secret = excluded.wrapped_secret";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, zoneId);
            st.setString(2, nodeId);
            st.setBytes(3, wrappedSecret);
            st.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to persist wrapped zone secret for {}/{}: {}", zoneId, nodeId, e.getMessage());
        }
    }

    /** The wrapped master for (zone, node), if this node has been granted/originated it. */
    public Optional<byte[]> get(String zoneId, String nodeId) {
        if (zoneId == null || zoneId.isBlank() || nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }
        var sql = "SELECT wrapped_secret FROM zone_wrapped_secrets WHERE zone_id = ? AND node_id = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, zoneId);
            st.setString(2, nodeId);
            try (var rs = st.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.empty();
            }
        } catch (Exception e) {
            log.error("Failed to load wrapped zone secret for {}/{}: {}", zoneId, nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether this node holds a wrapped master for the zone. */
    public boolean has(String zoneId, String nodeId) {
        return get(zoneId, nodeId).isPresent();
    }
}
