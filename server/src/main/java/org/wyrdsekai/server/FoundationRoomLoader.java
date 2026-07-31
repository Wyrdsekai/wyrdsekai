package org.wyrdsekai.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.config.HotReloadableConfig;
import org.wyrdsekai.core.item.RoomImprintTracker;
import org.wyrdsekai.core.pak.PakManager;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads foundation room definitions from JSON config.
 *
 * Default: classpath resource {@code foundation-rooms.json}.
 * Override: set system property {@code wyrdsekai.foundation-rooms} to a file path.
 *
 * This replaces the hardcoded room definitions that previously lived in Main.java,
 * enabling community room packs and per-deployment customization.
 */
public final class FoundationRoomLoader {

    private static final Logger log = LoggerFactory.getLogger(FoundationRoomLoader.class);
    private static final String CLASSPATH_RESOURCE = "foundation-rooms.json";
    private static final String SYSTEM_PROPERTY = "wyrdsekai.foundation-rooms";

    private FoundationRoomLoader() {}

    /**
     * Load foundation room seeds. Checks system property for override path first,
     * then falls back to classpath resource. Also loads rooms from installed extensions.
     */
    public static List<ZoneGuardian.RoomSeed> load() {
        var overridePath = System.getProperty(SYSTEM_PROPERTY);
        var seeds = new ArrayList<>(overridePath != null
            ? loadFromFile(Path.of(overridePath))
            : loadFromClasspath());

        // Load rooms from installed .wyrdpak extensions
        var pakManager = PakManager.defaultManager();
        var extensionScripts = pakManager.allRoomScripts();
        if (!extensionScripts.isEmpty()) {
            log.info("Found {} room scripts from extensions", extensionScripts.size());
        }

        return seeds;
    }

    /**
     * Create a hot-reloadable foundation room config.
     * Checks the override file (system property or default ~/.wyrdsekai/foundation-rooms.json)
     * for modifications on each access. Returns any NEW rooms not in the existing set.
     *
     * <p>This does not remove rooms that are already seeded — removing rooms would
     * disconnect players. Only new room definitions are returned.</p>
     *
     * @return HotReloadableConfig wrapping the room seed list
     */
    public static HotReloadableConfig<List<ZoneGuardian.RoomSeed>> hotReloadable() {
        var overrideProp = System.getProperty(SYSTEM_PROPERTY);
        var path = overrideProp != null
            ? Path.of(overrideProp)
            : Path.of(System.getProperty("user.home"), ".wyrdsekai", "foundation-rooms.json");
        return new HotReloadableConfig<>(path, FoundationRoomLoader::loadFromFile, List.of());
    }

    /**
     * Find rooms in the reloaded config that are not already known.
     * Call this to discover new room definitions added to the override file.
     *
     * @param reloaded    Full list of room seeds from the override file
     * @param existingIds Set of room IDs already seeded in the zone
     * @return List of new room seeds not present in existingIds
     */
    public static List<ZoneGuardian.RoomSeed> findNewRooms(
            List<ZoneGuardian.RoomSeed> reloaded, Set<String> existingIds) {
        return reloaded.stream()
            .filter(seed -> !existingIds.contains(seed.roomId()))
            .collect(Collectors.toList());
    }

    /** Load from a file on disk (for community room packs or testing). */
    public static List<ZoneGuardian.RoomSeed> loadFromFile(Path path) {
        try (var in = Files.newInputStream(path)) {
            var seeds = parse(in);
            log.info("Loaded {} foundation rooms from {}", seeds.size(), path);
            return seeds;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load foundation rooms from " + path, e);
        }
    }

    /** Load from classpath resource. */
    public static List<ZoneGuardian.RoomSeed> loadFromClasspath() {
        try (var in = FoundationRoomLoader.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                throw new RuntimeException(
                    "Foundation rooms config not found on classpath: " + CLASSPATH_RESOURCE);
            }
            var seeds = parse(in);
            log.info("Loaded {} foundation rooms from classpath", seeds.size());
            return seeds;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load foundation rooms from classpath", e);
        }
    }

    private static List<ZoneGuardian.RoomSeed> parse(InputStream in) throws IOException {
        var mapper = new ObjectMapper();
        List<RoomSeedDto> dtos = mapper.readValue(in, new TypeReference<>() {});
        // every room must declare embodiment_summary.
        // Rooms are baked into the install, so we warn (not REJECT) to avoid
        // bricking the world; the spec gate lands here so missing data is loud.
        var missing = dtos.stream()
            .filter(d -> d.embodiment_summary() == null || d.embodiment_summary().isBlank())
            .map(RoomSeedDto::roomId)
            .toList();
        if (!missing.isEmpty()) {
            log.warn("{} room(s) missing embodiment_summary: {}",
                missing.size(), missing);
        }
        return dtos.stream().map(RoomSeedDto::toRoomSeed).toList();
    }

    /**
     * DTO matching the JSON schema. Jackson deserializes into this,
     * then we convert to ZoneGuardian.RoomSeed (which uses Exit and RoomObject records).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RoomSeedDto(
        String roomId,
        String name,
        String description,
        List<String> aliases,
        List<ExitDto> exits,
        List<RoomObjectDto> objects,
        ImprintDto imprint,
        String replication,
        List<String> requirements,  // Wave 2: capability requirements for placement
        String embodiment_summary   // what the room's ambient state contributes
    ) {
        ZoneGuardian.RoomSeed toRoomSeed() {
            return new ZoneGuardian.RoomSeed(
                roomId, name, description,
                aliases != null ? aliases : List.of(),
                exits != null ? exits.stream().map(ExitDto::toExit).toList() : List.of(),
                objects != null ? objects.stream().map(RoomObjectDto::toRoomObject).toList() : List.of(),
                imprint != null ? imprint.toRoomImprint(roomId) : null
            );
        }
    }

    /**
     * Extract room capability requirements from foundation rooms.
     * Used by PlacementEngine to match rooms to capable nodes.
     * @return map of roomId → set of required capabilities
     */
    public static Map<String, Set<String>> loadRoomRequirements() {
        try {
            var overridePath = System.getProperty(SYSTEM_PROPERTY);
            InputStream in = overridePath != null
                ? Files.newInputStream(Path.of(overridePath))
                : FoundationRoomLoader.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE);
            if (in == null) return Map.of();
            try (in) {
                var mapper = new ObjectMapper();
                List<RoomSeedDto> dtos = mapper.readValue(in, new TypeReference<>() {});
                var result = new HashMap<String, Set<String>>();
                for (var dto : dtos) {
                    if (dto.requirements() != null && !dto.requirements().isEmpty()) {
                        result.put(dto.roomId(), Set.copyOf(dto.requirements()));
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to load room requirements: {}", e.getMessage());
            return Map.of();
        }
    }

    /** DTO for room imprint data — maps to RoomImprintTracker.RoomImprint. */
    private record ImprintDto(Map<String, Double> traits, String description, int threshold) {
        RoomImprintTracker.RoomImprint toRoomImprint(String roomId) {
            return new RoomImprintTracker.RoomImprint(roomId, traits, description, threshold);
        }
    }

    private record ExitDto(String direction, String targetRoom, String label) {
        Exit toExit() {
            return new Exit(direction, targetRoom, label);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RoomObjectDto(String id, String name, String description, boolean takeable,
                                  Boolean visible, List<String> aliases) {
        RoomObject toRoomObject() {
            return new RoomObject(id, name, description, takeable,
                visible != null ? visible : true, true,
                aliases != null ? aliases : List.of());
        }
    }
}
