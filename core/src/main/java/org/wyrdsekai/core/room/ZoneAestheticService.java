package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages zone and room aesthetics. Singleton.
 *
 * The steward configures zone aesthetic via:
 * 1. ~/.wyrdsekai/zone-aesthetic.json (preset name or full config)
 * 2. WYRDSEKAI_ZONE_AESTHETIC env var (preset name)
 * 3. Study UI (future)
 *
 * Room aesthetics can be set per-room for the "holodeck effect."
 */
public class ZoneAestheticService {

    private static final Logger log = LoggerFactory.getLogger(ZoneAestheticService.class);
    private static volatile ZoneAestheticService instance;

    public static void init() { instance = new ZoneAestheticService(); }
    public static ZoneAestheticService get() { return instance; }

    private volatile ZoneAesthetic zoneAesthetic = ZoneAesthetic.none();
    private final Map<String, RoomAesthetic> roomOverrides = new ConcurrentHashMap<>();

    /** Load zone aesthetic. Resolution order (first hit wins):
     *  <ol>
     *    <li>{@code WYRDSEKAI_ZONE_AESTHETIC} env var (override / CI)</li>
     *    <li>{@code WyrdConfig.theme()} — reads {@code node.theme} from
     *        {@code ~/.wyrdsekai/profile.toml} (canonical for fresh installs;
     *        seeded by {@code wyrd config init} from a ZoneNameGenerator
     *        bundle so a fresh node has a coherent identity out of the box)</li>
     *    <li>Legacy {@code ~/.wyrdsekai/zone-aesthetic.json}</li>
     *    <li>Fallback: {@code none()}</li>
     *  </ol>
     *  Order matches WyrdConfig's general precedence: env → profile → default.
     */
    public void loadConfig() {
        // 1. Explicit aesthetic preset (env > profile.toml zone.aesthetic_path)
        var envPreset = WyrdConfig.get().zoneAestheticPath();
        if (envPreset != null && !envPreset.isBlank()) {
            zoneAesthetic = ZoneAesthetic.preset(envPreset);
            log.info("Zone aesthetic loaded from config: {}", zoneAesthetic.name());
            return;
        }

        // 2. profile.toml node.theme (via WyrdConfig — same source of truth as
        //    everything else this session has migrated). The default for the
        //    accessor is a hostname-derived bundle theme, so this nearly
        //    always returns *something* sensible.
        try {
            var theme = WyrdConfig.get().theme();
            if (theme != null && !theme.isBlank() && !"none".equalsIgnoreCase(theme)) {
                var aesthetic = ZoneAesthetic.preset(theme);
                if (aesthetic != null && !"none".equals(aesthetic.name())) {
                    zoneAesthetic = aesthetic;
                    log.info("Zone aesthetic loaded from profile.toml node.theme: {}",
                        zoneAesthetic.name());
                    return;
                }
            }
        } catch (Throwable t) {
            log.debug("WyrdConfig theme lookup failed: {}", t.getMessage());
        }

        // 3. Legacy zone-aesthetic.json (kept for backward compat)
        var configPath = Path.of(
            System.getProperty("user.home"), ".wyrdsekai", "zone-aesthetic.json");
        if (Files.exists(configPath)) {
            try {
                var json = Files.readString(configPath);
                var mapper = new ObjectMapper();
                try {
                    zoneAesthetic = mapper.readValue(json, ZoneAesthetic.class);
                    log.info("Zone aesthetic loaded from legacy config: {}", zoneAesthetic.name());
                    return;
                } catch (Exception e) {
                    var node = mapper.readTree(json);
                    if (node.has("preset")) {
                        zoneAesthetic = ZoneAesthetic.preset(node.get("preset").asText());
                        log.info("Zone aesthetic loaded from legacy preset: {}", zoneAesthetic.name());
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load legacy zone aesthetic config: {}", e.getMessage());
            }
        }

        log.debug("No zone aesthetic configured — using default ({})", zoneAesthetic.name());
    }

    /** Get the zone-level aesthetic. */
    public ZoneAesthetic zoneAesthetic() {
        return zoneAesthetic;
    }

    /** Set the zone aesthetic (steward action). */
    public void setZoneAesthetic(ZoneAesthetic aesthetic) {
        this.zoneAesthetic = aesthetic != null ? aesthetic : ZoneAesthetic.none();
        log.info("Zone aesthetic set to: {}", this.zoneAesthetic.name());
    }

    /** Set a room-level aesthetic override. */
    public void setRoomAesthetic(String roomId, ZoneAesthetic aesthetic) {
        if (aesthetic == null) {
            roomOverrides.remove(roomId);
        } else {
            roomOverrides.put(roomId, new RoomAesthetic(roomId, aesthetic));
        }
    }

    /** Get the room-level aesthetic override (may be null). */
    public RoomAesthetic roomAesthetic(String roomId) {
        return roomOverrides.get(roomId);
    }

    /**
     * Resolve the effective aesthetic for a companion in a given room.
     * Room aesthetic overrides zone aesthetic.
     */
    public ZoneAesthetic effectiveAesthetic(String roomId) {
        var roomOverride = roomOverrides.get(roomId);
        return RoomAesthetic.resolve(zoneAesthetic, roomOverride);
    }

    /**
     * Build the aesthetic prompt overlay for system prompt injection.
     * Returns empty string if no aesthetic is configured.
     */
    public String buildPromptOverlay(String roomId) {
        var aesthetic = effectiveAesthetic(roomId);
        if (aesthetic == null || aesthetic.stylePrompt() == null || aesthetic.stylePrompt().isBlank()) {
            return "";
        }
        return "\n## Zone Style\n" + aesthetic.stylePrompt() + "\n";
    }

    /** Get restricted actions for a room (combines zone + room restrictions). */
    public List<String> restrictedActions(String roomId) {
        var aesthetic = effectiveAesthetic(roomId);
        if (aesthetic == null || aesthetic.restrictedActions() == null) {
            return List.of();
        }
        return aesthetic.restrictedActions();
    }

    /** Get the cost modifier for an action in a room. */
    public double costModifier(String roomId, String actionType) {
        var aesthetic = effectiveAesthetic(roomId);
        return aesthetic != null ? aesthetic.costModifier(actionType) : 1.0;
    }
}
