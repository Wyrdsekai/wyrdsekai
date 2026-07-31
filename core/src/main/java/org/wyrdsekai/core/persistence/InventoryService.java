package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.TransitInventory;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-entity inventory persistence backed by SQLite or PostgreSQL.
 * Tracks objects carried by players and agents across rooms and restarts.
 *
 * Supports scripted items (e.g., library_card, oracle_lens) via the
 * {@code script_source} and {@code script_id} columns, which are preserved
 * through cross-zone transit so scripts continue to function in the remote zone.
 */
public final class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public record InventoryItem(String objectId, String objectName, String description,
                                boolean takeable, String takenFrom,
                                String scriptSource, String scriptId) {
        /** Backward-compat constructor — non-scripted. */
        public InventoryItem(String objectId, String objectName, String description,
                             boolean takeable, String takenFrom) {
            this(objectId, objectName, description, takeable, takenFrom, null, null);
        }

        public boolean isScripted() {
            return scriptSource != null && !scriptSource.isEmpty();
        }
    }

    public InventoryService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public InventoryService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /** Add a non-scripted item. Idempotent (upsert). */
    public void addItem(String entityId, String objectId, String objectName,
                        String description, boolean takeable, String roomId) {
        addItem(entityId, objectId, objectName, description, takeable, roomId, null, null);
    }

    /**
     * Add an item with optional script fields. Idempotent (upsert).
     * When scriptSource is non-null, the item is scripted and will carry
     * its script through cross-zone transit.
     */
    public void addItem(String entityId, String objectId, String objectName,
                        String description, boolean takeable, String roomId,
                        String scriptSource, String scriptId) {
        var sql = dialect.upsert("inventory",
            "entity_id, object_id, object_name, description, takeable, taken_from, script_source, script_id",
            "?, ?, ?, ?, ?, ?, ?, ?",
            "entity_id, object_id",
            "object_name = EXCLUDED.object_name, description = EXCLUDED.description, "
                + "takeable = EXCLUDED.takeable, taken_from = EXCLUDED.taken_from, "
                + "script_source = EXCLUDED.script_source, script_id = EXCLUDED.script_id");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, entityId);
            stmt.setString(2, objectId);
            stmt.setString(3, objectName);
            stmt.setString(4, description != null ? description : "");
            stmt.setBoolean(5, takeable);
            stmt.setString(6, roomId);
            stmt.setString(7, scriptSource);
            stmt.setString(8, scriptId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to add inventory item {} for {}: {}", objectId, entityId, e.getMessage());
        }
    }

    /** Remove an item from inventory. Returns the removed item, or empty if not found. */
    public Optional<InventoryItem> removeItem(String entityId, String objectId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            // Select first, then delete (RETURNING not universally supported)
            InventoryItem item;
            try (var sel = conn.prepareStatement("""
                    SELECT object_id, object_name, description, takeable, taken_from,
                           script_source, script_id
                    FROM inventory WHERE entity_id = ? AND object_id = ?""")) {
                sel.setString(1, entityId);
                sel.setString(2, objectId);
                var rs = sel.executeQuery();
                if (!rs.next()) return Optional.empty();
                item = readItem(rs);
            }
            try (var del = conn.prepareStatement(
                    "DELETE FROM inventory WHERE entity_id = ? AND object_id = ?")) {
                del.setString(1, entityId);
                del.setString(2, objectId);
                del.executeUpdate();
            }
            return Optional.of(item);
        } catch (SQLException e) {
            log.error("Failed to remove inventory item {} for {}: {}", objectId, entityId, e.getMessage());
            return Optional.empty();
        }
    }

    /** List all items carried by an entity, ordered by acquisition time. */
    // Why: the "inventory" user surface should show portable items only.
    // Pinned scripted furnishings live in the inventory table so
    // tryInvokeCarriedScript() can resolve them, but they aren't "carried"
    // in the user-mental-model sense. Use this variant for UI rendering.
    public List<InventoryItem> listTakeableItems(String entityId) {
        return listItems(entityId).stream().filter(InventoryItem::takeable).toList();
    }

    public List<InventoryItem> listItems(String entityId) {
        var items = new ArrayList<InventoryItem>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement("""
                 SELECT object_id, object_name, description, takeable, taken_from,
                        script_source, script_id
                 FROM inventory WHERE entity_id = ? ORDER BY acquired_at""")) {
            stmt.setString(1, entityId);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                items.add(readItem(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list inventory for {}: {}", entityId, e.getMessage());
        }
        return items;
    }

    /** Find an item by name (case-insensitive). */
    public Optional<InventoryItem> findByName(String entityId, String objectName) {
        var sql = "SELECT object_id, object_name, description, takeable, taken_from, "
            + "script_source, script_id"
            + " FROM inventory WHERE entity_id = ? AND "
            + dialect.caseInsensitiveEquals("object_name", "?")
            + " LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, entityId);
            stmt.setString(2, objectName);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(readItem(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find inventory item '{}' for {}: {}", objectName, entityId, e.getMessage());
        }
        return Optional.empty();
    }

    // Why: a player's inventory holds two shapes — portable items (compass)
    // and pinned scripted furnishings (Study's Compass, Board, ...). A plain
    // name match on "compass" collides. Drop/give must only ever act on
    // takeable rows; otherwise you can silently strip scripted tools from
    // your Home.
    public Optional<InventoryItem> findTakeableByName(String entityId, String objectName) {
        var sql = "SELECT object_id, object_name, description, takeable, taken_from, "
            + "script_source, script_id"
            + " FROM inventory WHERE entity_id = ? AND takeable = ? AND "
            + dialect.caseInsensitiveEquals("object_name", "?")
            + " LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, entityId);
            stmt.setBoolean(2, true);
            stmt.setString(3, objectName);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(readItem(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to find takeable inventory item '{}' for {}: {}", objectName, entityId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Check if entity has a specific item by ID. */
    public boolean hasItem(String entityId, String objectId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT 1 FROM inventory WHERE entity_id = ? AND object_id = ?")) {
            stmt.setString(1, entityId);
            stmt.setString(2, objectId);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            log.error("Failed to check inventory item {} for {}: {}", objectId, entityId, e.getMessage());
            return false;
        }
    }

    /** Count items carried by an entity. */
    public int countItems(String entityId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM inventory WHERE entity_id = ?")) {
            stmt.setString(1, entityId);
            var rs = stmt.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Failed to count inventory for {}: {}", entityId, e.getMessage());
            return 0;
        }
    }

    /**
     * Serialize an entity's inventory for cross-zone transit.
     * All items become TransitItems; scripted items include their script source
     * so they continue to function in the destination zone.
     */
    public TransitInventory serializeForTransit(
            String entityId, String sourceZone) {
        var items = listItems(entityId);
        var transitItems = new ArrayList<TransitInventory.TransitItem>();
        for (var item : items) {
            transitItems.add(new TransitInventory.TransitItem(
                item.objectId(),
                item.objectName(),
                item.description(),
                item.takeable(),
                List.of(),   // aliases not persisted in inventory table yet
                item.scriptSource(),
                item.scriptId(),
                Map.of()));  // properties not persisted yet
        }
        return new TransitInventory(sourceZone, transitItems);
    }

    /**
     * Apply a transit delta on session.close: remove items dropped remotely,
     * add items picked up remotely. Scripts on picked-up items are preserved.
     */
    public void applyTransitDelta(String entityId,
                                   TransitInventory.TransitDelta delta) {
        if (delta == null || delta.isEmpty()) return;
        for (var removedId : delta.removedItemIds()) {
            removeItem(entityId, removedId);
        }
        for (var added : delta.addedItems()) {
            addItem(entityId, added.id(), added.name(), added.description(),
                added.takeable(), "remote_zone",
                added.scriptSource(), added.scriptId());
        }
        log.info("Applied transit delta for {}: -{} items, +{} items",
            entityId, delta.removedItemIds().size(), delta.addedItems().size());
    }

    private static InventoryItem readItem(ResultSet rs) throws SQLException {
        return new InventoryItem(
            rs.getString("object_id"),
            rs.getString("object_name"),
            rs.getString("description"),
            rs.getBoolean("takeable"),
            rs.getString("taken_from"),
            rs.getString("script_source"),
            rs.getString("script_id"));
    }
}
