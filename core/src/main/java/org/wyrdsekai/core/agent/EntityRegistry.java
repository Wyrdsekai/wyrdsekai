package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AliasResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Tracks which entities are in which rooms across the zone.
 *
 * Used for cross-room communication (tell) and presence awareness.
 * Updated when entities enter/leave rooms. Player sessions register on connect.
 *
 * Supports alias-based lookup with MUD-style ordinal disambiguation:
 * "tell wyrd" → first entity with alias "wyrd"
 * "tell 2.guard" → second entity with alias "guard"
 *
 * For multi-node: Between syncs entity locations via NATS gossip.
 * This is the single-node version — sufficient for household deployment.
 */
public class EntityRegistry {

    private static final Logger log = LoggerFactory.getLogger(EntityRegistry.class);

    /** Entity presence state for cross-zone awareness. */
    public enum PresenceState { PRESENT, TRAVELING, OFFLINE }

    /** Global instance — initialized by Main.java. */
    private static volatile EntityRegistry instance;

    /** entityId → current roomId */
    private final ConcurrentHashMap<String, String> entityRooms = new ConcurrentHashMap<>();

    /** entityId → entityName (for name-based lookup) */
    private final ConcurrentHashMap<String, String> entityNames = new ConcurrentHashMap<>();

    /** entityName (lowercase) → entityId (for tell-by-name) */
    private final ConcurrentHashMap<String, String> nameIndex = new ConcurrentHashMap<>();

    /** entityId → entityType ("player" or "agent") */
    private final ConcurrentHashMap<String, String> entityTypes = new ConcurrentHashMap<>();

    /** entityId → presence state. Default PRESENT. */
    private final ConcurrentHashMap<String, PresenceState> presenceStates = new ConcurrentHashMap<>();

    /** entityId → destination zone (when TRAVELING). */
    private final ConcurrentHashMap<String, String> travelDestinations = new ConcurrentHashMap<>();

    /** entityId → home zone ID. */
    private final ConcurrentHashMap<String, String> homeZones = new ConcurrentHashMap<>();

    /** alias (lowercase) → Set<entityId>. Multiple entities can share an alias. */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<String>> aliasIndex =
        new ConcurrentHashMap<>();

    /** entityId → List<alias>. For cleanup on remove. */
    private final ConcurrentHashMap<String, List<String>> entityAliases = new ConcurrentHashMap<>();

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() {
        instance = new EntityRegistry();
    }

    /** Get the global instance. */
    public static EntityRegistry get() {
        return instance;
    }

    /** Register an entity entering a room (no aliases). */
    public void enter(String entityId, String entityName, String entityType, String roomId) {
        enter(entityId, entityName, entityType, roomId, List.of());
    }

    /** Register an entity entering a room with aliases. */
    public void enter(String entityId, String entityName, String entityType, String roomId,
                      List<String> aliases) {
        entityRooms.put(entityId, roomId);
        entityNames.put(entityId, entityName);
        nameIndex.put(entityName.toLowerCase(), entityId);
        entityTypes.put(entityId, entityType);
        presenceStates.put(entityId, PresenceState.PRESENT);
        // Register aliases
        if (aliases != null && !aliases.isEmpty()) {
            entityAliases.put(entityId, List.copyOf(aliases));
            for (var alias : aliases) {
                aliasIndex.computeIfAbsent(alias.toLowerCase(),
                    k -> new CopyOnWriteArraySet<>()).add(entityId);
            }
        }
    }

    /** Register an entity leaving (update room, or remove if disconnected). */
    public void leave(String entityId) {
        entityRooms.remove(entityId);
        // Keep name/alias index — entity still exists, just not in a room
    }

    /** Update room for an entity that moved. */
    public void moved(String entityId, String newRoomId) {
        entityRooms.put(entityId, newRoomId);
    }

    /** Mark an entity as traveling to another zone. */
    public void setTraveling(String entityId, String destinationZone) {
        presenceStates.put(entityId, PresenceState.TRAVELING);
        travelDestinations.put(entityId, destinationZone);
        log.info("Entity {} now TRAVELING to zone {}", entityId, destinationZone);
    }

    /** Mark a traveling entity as returned (back to PRESENT). */
    public void setReturned(String entityId) {
        presenceStates.put(entityId, PresenceState.PRESENT);
        travelDestinations.remove(entityId);
        log.info("Entity {} returned from traveling, now PRESENT", entityId);
    }

    /** Set entity's home zone. */
    public void setHomeZone(String entityId, String zoneId) {
        homeZones.put(entityId, zoneId);
    }

    /** Get entity's presence state. */
    public PresenceState presenceOf(String entityId) {
        return presenceStates.getOrDefault(entityId, PresenceState.OFFLINE);
    }

    /** Get entity's travel destination (only valid when TRAVELING). */
    public Optional<String> travelDestinationOf(String entityId) {
        return Optional.ofNullable(travelDestinations.get(entityId));
    }

    /** Get entity's home zone. */
    public Optional<String> homeZoneOf(String entityId) {
        return Optional.ofNullable(homeZones.get(entityId));
    }

    /** Remove an entity entirely (disconnect, shutdown). */
    public void remove(String entityId) {
        var name = entityNames.remove(entityId);
        if (name != null) nameIndex.remove(name.toLowerCase());
        entityRooms.remove(entityId);
        entityTypes.remove(entityId);
        presenceStates.remove(entityId);
        travelDestinations.remove(entityId);
        homeZones.remove(entityId);
        // Remove aliases
        var aliases = entityAliases.remove(entityId);
        if (aliases != null) {
            for (var alias : aliases) {
                var set = aliasIndex.get(alias.toLowerCase());
                if (set != null) {
                    set.remove(entityId);
                    if (set.isEmpty()) aliasIndex.remove(alias.toLowerCase());
                }
            }
        }
    }

    /** Find which room an entity is in. */
    public Optional<String> roomOf(String entityId) {
        return Optional.ofNullable(entityRooms.get(entityId));
    }

    /**
     * Find an entity ID by name or alias, with MUD-style ordinal support.
     * Supports "N.query" syntax: "2.guard" → second entity with alias/name "guard".
     *
     * Resolution order:
     * 1. Exact alias match
     * 2. Exact name match
     * 3. Fuzzy name match (legacy behavior — contains)
     */
    public Optional<String> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();

        var parsed = AliasResolver.parseQuery(name);
        var query = parsed.query();
        var ordinal = parsed.ordinal();
        if (query.isEmpty()) return Optional.empty();
        var normalizedQuery = query.toLowerCase().trim();

        // 1. Alias match — returns Set<entityId>
        var aliasMatches = aliasIndex.get(normalizedQuery);
        if (aliasMatches != null && !aliasMatches.isEmpty()) {
            var list = new ArrayList<>(aliasMatches);
            if (ordinal > 0 && ordinal <= list.size()) {
                return Optional.of(list.get(ordinal - 1));
            }
            return Optional.of(list.getFirst());
        }

        // 2. Exact name match
        var exact = nameIndex.get(normalizedQuery);
        if (exact != null) return Optional.of(exact);

        // 3. Fuzzy: any registered name contained in the query
        for (var entry : nameIndex.entrySet()) {
            if (normalizedQuery.contains(entry.getKey())) {
                log.debug("EntityRegistry fuzzy match: '{}' matched registered name '{}'",
                    name, entry.getKey());
                return Optional.of(entry.getValue());
            }
        }

        // 4. Fuzzy: query contained in any registered name
        for (var entry : nameIndex.entrySet()) {
            if (entry.getKey().contains(normalizedQuery)) {
                log.debug("EntityRegistry reverse fuzzy match: '{}' found in registered name '{}'",
                    name, entry.getKey());
                return Optional.of(entry.getValue());
            }
        }

        log.info("EntityRegistry: no match for '{}'. Registered names: {}", name, nameIndex.keySet());
        return Optional.empty();
    }

    /** Get entity name by ID. */
    public Optional<String> nameOf(String entityId) {
        return Optional.ofNullable(entityNames.get(entityId));
    }

    /** Check if an entity is online (has a room). */
    public boolean isOnline(String entityId) {
        return entityRooms.containsKey(entityId);
    }

    /** Check if an entity is an agent. */
    public boolean isAgent(String entityId) {
        return "agent".equals(entityTypes.get(entityId));
    }

    /** All registered entity IDs. */
    public Set<String> allEntities() {
        return Collections.unmodifiableSet(entityRooms.keySet());
    }

    /** Count of registered entities. */
    public int count() {
        return entityRooms.size();
    }
}
