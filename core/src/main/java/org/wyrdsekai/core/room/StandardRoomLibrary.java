package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.RoomImprintTracker;
import org.wyrdsekai.core.room.RoomTemplate.DefaultObject;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard Room Library — room templates for conversational creation.
 *
 * <p>Provides template definitions for standard room types. Each template has
 * a base script, default objects, and a default imprint. Rooms are created
 * via {@link #instantiate(String, String, Map, String)} which produces a
 * {@link ZoneGuardian.RoomSeed} ready for registration.</p>
 */
public class StandardRoomLibrary {

    private static final Logger log = LoggerFactory.getLogger(StandardRoomLibrary.class);

    private final Map<String, RoomTemplate> templates = new LinkedHashMap<>();
    private final Path scriptsRoot;

    public StandardRoomLibrary(Path scriptsRoot) {
        this.scriptsRoot = scriptsRoot;
        registerAllTemplates();
    }

    public Map<String, RoomTemplate> templates() {
        return Map.copyOf(templates);
    }

    public RoomTemplate get(String name) {
        return templates.get(name);
    }

    public List<RoomTemplate> search(String query) {
        if (query == null || query.isBlank()) return List.copyOf(templates.values());
        var lower = query.toLowerCase();
        return templates.values().stream()
            .filter(t -> t.name().toLowerCase().contains(lower)
                || t.displayName().toLowerCase().contains(lower)
                || t.description().toLowerCase().contains(lower))
            .toList();
    }

    public List<RoomTemplate> byType(String type) {
        return templates.values().stream()
            .filter(t -> t.name().equalsIgnoreCase(type))
            .toList();
    }

    /**
     * Base-script source for a template, by template name — the std/room/*.js
     * behavior a template room is born with. Null when the template is unknown,
     * carries no baseScript, or the script file is missing on disk. Callers
     * (room-creation paths) deliver this through SetBehaviorScript so the
     * RoomActor's ScriptLoader finds it under {@code <userScriptsDir>/<roomId>.js}.
     */
    public String baseScriptFor(String templateName) {
        var template = templates.get(templateName);
        if (template == null) return null;
        return resolveBaseScript(template.baseScript());
    }

    /**
     * Resolve a room base script path to source code.
     */
    public String resolveBaseScript(String path) {
        if (path == null || path.isBlank()) return null;
        var scriptPath = scriptsRoot.resolve(path + ".js");
        try {
            if (Files.exists(scriptPath)) {
                return Files.readString(scriptPath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load room base script {}: {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * Create a RoomSeed from a template with user configuration.
     *
     * @param templateName Template name (e.g., "library")
     * @param roomId       Room ID to register
     * @param config       User configuration (name, description, theme, etc.)
     * @param connectTo    Room ID to create an exit connection to (nullable)
     * @return RoomSeed ready for ZoneGuardian registration
     */
    public ZoneGuardian.RoomSeed instantiate(String templateName, String roomId,
                                              Map<String, String> config, String connectTo) {
        var template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown room template: " + templateName);
        }

        var roomName = config != null && config.containsKey("name")
            ? config.get("name") : template.displayName();
        var roomDesc = config != null && config.containsKey("description")
            ? config.get("description") : template.description();

        // Build exits
        var exits = new ArrayList<Exit>();
        if (connectTo != null && !connectTo.isBlank()) {
            exits.add(new Exit("out", connectTo, "Back to " + connectTo));
        }

        // Build objects from template defaults
        var objects = new ArrayList<RoomObject>();
        if (template.defaultObjects() != null) {
            for (var obj : template.defaultObjects()) {
                objects.add(new RoomObject(
                    obj.id(), obj.name(), obj.description(), obj.takeable(), true));
            }
        }

        // Build imprint
        RoomImprintTracker.RoomImprint imprint = null;
        if (template.defaultImprint() != null && !template.defaultImprint().isEmpty()) {
            imprint = new RoomImprintTracker.RoomImprint(
                roomId, template.defaultImprint(), template.description(), 400);
        }

        return new ZoneGuardian.RoomSeed(roomId, roomName, roomDesc, exits, objects, imprint);
    }

    // ─── Template Registration ───────────────────────────────────


    /**
     * Template names, as a constant the TOOL SCHEMA can read.
     *
     * <p>{@code create_room_from_template} listed these in its description prose
     * and left {@code ToolParam.enumValues} null, so nothing stopped the model
     * inventing one. Live on home-server 2026-07-30 it asked for
     * {@code template:"greenhouse-template"} — the room it was asked to build,
     * not a template — the call was rejected, and it fell back to plain
     * {@code create_room}, which carries no default objects. The bondholder got a
     * room described as "filled with lush plants" containing <b>nothing</b>
     * (`0 objects` in the creation log).
     *
     * <p>Registration is instance-level and the tool factory is static, so this
     * constant is the bridge. {@code RoomTemplateEnumMatchesLibraryTest} fails the
     * build if it ever drifts from what {@link #registerAllTemplates} actually
     * registers.</p>
     */
    public static final List<String> TEMPLATE_NAMES = List.of(
        "hub", "study", "workshop", "library", "market", "garden", "hall", "observatory", "gate", "empty");

    private void registerAllTemplates() {
        register(new RoomTemplate(
            "hub", "Central Hub",
            "A central gathering space where all paths meet and travelers arrive.",
            "std/room/hub",
            List.of(
                new DefaultObject("hub-board", "notice board", "A board showing zone announcements.", false)
            ),
            Map.of("social", 0.2, "curiosity", 0.1),
            Map.of()
        ));

        register(new RoomTemplate(
            "study", "Private Study",
            "A quiet private space with desk, journal, shelves, and dashboard.",
            "std/room/study",
            List.of(
                new DefaultObject("study-desk", "desk", "A desk with schedule board and correspondence tray.", false),
                new DefaultObject("study-journal", "journal", "A leather-bound journal for personal thoughts.", false),
                new DefaultObject("study-shelves", "shelves", "Document shelves along one wall.", false),
                new DefaultObject("study-dashboard", "dashboard crystal", "A crystal showing system telemetry.", false)
            ),
            Map.of("focus", 0.2, "calm", 0.2),
            Map.of()
        ));

        register(new RoomTemplate(
            "workshop", "Workshop",
            "A creation space with workbench, template catalog, and blueprint rack.",
            "std/room/workshop",
            List.of(
                new DefaultObject("workbench", "workbench", "A sturdy workbench for crafting items.", false),
                new DefaultObject("template-catalog", "template catalog", "A shimmering index of available templates.", false),
                new DefaultObject("blueprint-rack", "blueprint rack", "Standard blueprints for common items.", false)
            ),
            Map.of("creativity", 0.3, "focus", 0.2),
            Map.of()
        ));

        register(new RoomTemplate(
            "library", "Library",
            "A knowledge repository with shelves, catalog, and reading areas.",
            "std/room/library",
            List.of(
                new DefaultObject("library-catalog", "card catalog", "A brass card catalog with labeled drawers.", false),
                new DefaultObject("reading-desk", "reading desk", "A quiet desk for study.", false)
            ),
            Map.of("curiosity", 0.3, "focus", 0.2),
            Map.of()
        ));

        register(new RoomTemplate(
            "market", "Market",
            "A trade hub with stalls, listings, and commerce.",
            "std/room/market",
            List.of(
                new DefaultObject("market-board", "market board", "Current listings and trade offers.", false),
                new DefaultObject("stall", "merchant stall", "A stall for displaying wares.", false)
            ),
            Map.of("social", 0.3, "energy", 0.1),
            Map.of()
        ));

        register(new RoomTemplate(
            "garden", "Garden",
            "A peaceful green space for rest, reflection, and ambient beauty.",
            "std/room/garden",
            List.of(
                new DefaultObject("bench", "stone bench", "A weathered stone bench beneath a tree.", false),
                new DefaultObject("fountain", "fountain", "A small fountain with clear water.", false)
            ),
            Map.of("calm", 0.3, "creativity", 0.2),
            Map.of("season", "spring")
        ));

        register(new RoomTemplate(
            "hall", "Council Hall",
            "A governance chamber for proposals, voting, and deliberation.",
            "std/room/hall",
            List.of(
                new DefaultObject("speaker-platform", "speaker platform", "The central platform for addressing the council.", false),
                new DefaultObject("agenda-board", "agenda board", "Current proposals and voting status.", false)
            ),
            Map.of("focus", 0.2, "social", 0.2),
            Map.of("governance", "council")
        ));

        register(new RoomTemplate(
            "observatory", "Observatory",
            "An observation post for monitoring zone activity, patterns, and predictions.",
            "std/room/observatory",
            List.of(
                new DefaultObject("observation-lens", "observation lens", "A crystalline lens focused on zone patterns.", false),
                new DefaultObject("pattern-board", "pattern board", "Connected threads showing relationships.", false)
            ),
            Map.of("curiosity", 0.3, "focus", 0.3),
            Map.of("focus", "zone")
        ));

        register(new RoomTemplate(
            "gate", "Gate",
            "A fortified entrance with access control and warden checks.",
            "std/room/gate",
            List.of(
                new DefaultObject("warden-post", "warden post", "The warden's station beside the gate.", false),
                new DefaultObject("gate-log", "gate log", "A ledger recording all who pass.", true)
            ),
            Map.of("vigilance", 0.3, "caution", 0.2),
            Map.of("security", "standard")
        ));

        register(new RoomTemplate(
            "empty", "Empty Room",
            "A blank canvas — minimal room awaiting purpose.",
            "std/room/empty",
            List.of(),
            Map.of(),
            Map.of()
        ));

        log.info("Standard Room Library: {} room templates registered", templates.size());
    }

    private void register(RoomTemplate template) {
        templates.put(template.name(), template);
    }
}
