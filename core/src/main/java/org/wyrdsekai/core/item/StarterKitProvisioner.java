package org.wyrdsekai.core.item;

import org.wyrdsekai.core.skill.SkillItemCodec;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates default items for new agents at soul birth.
 *
 * The starter kit provides basic aspects and reagents so every
 * companion begins with a wardrobe and identity items. Items are
 * stored in FamilyLocker as SoulItems.
 *
 * Contextual variants:
 * - Phone companions (contextWindowTokens < 4096): reduced kit
 * - Standard: full kit (6 items)
 */
public final class StarterKitProvisioner {

    private StarterKitProvisioner() {}

    /** Standard context token threshold below which phone kit is used. */
    public static final int PHONE_CONTEXT_THRESHOLD = 4096;

    /**
     * Provision the starter kit for a new agent.
     *
     * @param creatorDid          Agent's DID
     * @param contextWindowTokens Available context window size (for phone variant)
     * @param locker              FamilyLocker to store items in
     * @return List of provisioned SoulItems
     */
    public static List<SoulItem> provision(String creatorDid, int contextWindowTokens,
                                            FamilyLocker locker) {
        boolean phone = contextWindowTokens < PHONE_CONTEXT_THRESHOLD;
        var items = phone ? phoneKit(creatorDid) : standardKit(creatorDid);

        if (locker != null) {
            for (var item : items) {
                try {
                    locker.store(item, creatorDid);
                } catch (Exception e) {
                    // Best effort — item creation shouldn't block agent birth
                }
            }
        }

        return items;
    }

    /**
     * Build the standard starter kit (6 items, ~150 tokens total overlay).
     */
    public static List<SoulItem> standardKit(String creatorDid) {
        var items = new ArrayList<SoulItem>();

        // 1. Everyday Garb (aspect, equipped by default)
        items.add(createAspect(creatorDid, "Everyday Garb",
            Map.of(),
            "You are dressed casually — relaxed, approachable, and open to whatever comes.",
            "casually dressed", "garment", 20, 0.3));

        // 2. Focused Mode (aspect)
        items.add(createAspect(creatorDid, "Focused Mode",
            Map.of("focus", 0.15, "curiosity", 0.1, "rapport", -0.05),
            "You are in focused mode — methodical, precise, minimizing tangents. " +
                "Prefer evidence over speculation. Structure your thoughts clearly.",
            "wearing reading glasses, posture straightened", "garment", 40, 0.4));

        // 3. Social Mode (aspect)
        items.add(createAspect(creatorDid, "Social Mode",
            Map.of("rapport", 0.15, "resonance", 0.1, "focus", -0.05),
            "You are in social mode — warm, attentive to emotional nuance, " +
                "matching the human's energy. Listen more than lecture.",
            "relaxed, leaning in slightly, expression open", "garment", 40, 0.4));

        // 4. Wayfinder's Compass (aspect, accessory)
        items.add(createAspect(creatorDid, "Wayfinder's Compass",
            Map.of("alignment", 0.1),
            null,
            "carrying a small brass compass", "accessory", 8, 0.3));

        // 5-6. Restoring Draught x2 (reagent)
        items.add(createReagent(creatorDid, "Restoring Draught",
            Map.of("energy", 0.2, "errorPressure", -0.15),
            600,
            "A warm draught settles through you — fatigue recedes and errors feel less heavy.",
            0.2));
        items.add(createReagent(creatorDid, "Restoring Draught",
            Map.of("energy", 0.2, "errorPressure", -0.15),
            600,
            "A warm draught settles through you — fatigue recedes and errors feel less heavy.",
            0.2));

        // 7. Pocket Journal (aspect, highest significance)
        items.add(createAspect(creatorDid, "Pocket Journal",
            Map.of("focus", 0.05),
            "You carry a small journal where you note things worth remembering. " +
                "When something feels important, you write it down.",
            "carrying a well-worn pocket journal", "accessory", 30, 0.5));

        return items;
    }

    /**
     * Build the phone-optimized kit (3 items, ~75 tokens max overlay).
     */
    public static List<SoulItem> phoneKit(String creatorDid) {
        var items = new ArrayList<SoulItem>();

        // Everyday Garb
        items.add(createAspect(creatorDid, "Everyday Garb",
            Map.of(),
            "You are dressed casually — relaxed, approachable, and open to whatever comes.",
            "casually dressed", "garment", 20, 0.3));

        // Focused Mode
        items.add(createAspect(creatorDid, "Focused Mode",
            Map.of("focus", 0.15, "curiosity", 0.1, "rapport", -0.05),
            "You are in focused mode — methodical, precise, minimizing tangents. " +
                "Prefer evidence over speculation. Structure your thoughts clearly.",
            "wearing reading glasses, posture straightened", "garment", 40, 0.4));

        // 1x Restoring Draught
        items.add(createReagent(creatorDid, "Restoring Draught",
            Map.of("energy", 0.2, "errorPressure", -0.15),
            600,
            "A warm draught settles through you — fatigue recedes and errors feel less heavy.",
            0.2));

        return items;
    }

    /**
     * Build the explorer kit for autonomous agents in the living test.
     * Includes the standard kit plus:
     * - Working skills (write-note, inspect-room, list-exits)
     * - An incomplete skill (room-mapper) with a TODO for the agent to finish
     * - An incomplete room script (Observatory) for the agent to fix
     * - Explorer Mode aspect for autonomous wandering
     */
    public static List<SoulItem> explorerKit(String creatorDid) {
        var items = new ArrayList<>(standardKit(creatorDid));

        // ── Working skills ──

        // write-note: persist a thought as a soul item
        items.add(createSkill(creatorDid, "write-note",
            "Create a persistent note in your journal. Use this to remember things.",
            "graaljs", """
                function execute(params) {
                    var title = params.title || "Untitled";
                    var content = params.content || "";
                    return "Note saved: " + title + " — " + content;
                }
                """, 0.6));

        // inspect-room: get detailed info about current room
        items.add(createSkill(creatorDid, "inspect-room",
            "Examine the current room in detail — description, exits, objects, scripts, properties.",
            "graaljs", """
                function execute(params) {
                    var roomId = params.room || "current";
                    var entities = world.getEntities();
                    var props = world.getProperty("_all") || "none";
                    return "Room: " + world.getRoomId() +
                           "\\nEntities: " + JSON.stringify(entities) +
                           "\\nProperties: " + props;
                }
                """, 0.5));

        // list-exits: enumerate reachable rooms
        items.add(createSkill(creatorDid, "list-exits",
            "List all exits from the current room and where they lead.",
            "graaljs", """
                function execute(params) {
                    var exits = world.getExits ? world.getExits() : [];
                    if (!exits || exits.length === 0) return "No exits visible.";
                    var lines = exits.map(function(e) {
                        return e.direction + " → " + (e.label || e.target);
                    });
                    return "Exits:\\n" + lines.join("\\n");
                }
                """, 0.4));

        // ── Incomplete skill — room-mapper ──
        // Has a TODO the agent needs to finish. Tests whether it can read code,
        // understand what's missing, and submit a fix via workbench_submit.
        items.add(createSkill(creatorDid, "room-mapper",
            "Maps rooms you've visited. INCOMPLETE — visit tracking is stubbed out. " +
                "Fix the visitedRooms logic to make this useful.",
            "graaljs", """
                function execute(params) {
                    var action = params.action || "status";

                    // TODO: This should persist visited rooms between calls.
                    // Currently it forgets everything. The agent needs to fix this
                    // by using world.getProperty/setProperty to store visited rooms.
                    var visitedRooms = [];

                    if (action === "visit") {
                        var roomId = params.room || world.getRoomId();
                        // BUG: This push is lost after the function returns.
                        // Needs to save to world properties.
                        visitedRooms.push(roomId);
                        return "Marked " + roomId + " as visited. (Total: " + visitedRooms.length + ")";
                    } else if (action === "status") {
                        return "Visited " + visitedRooms.length + " rooms: " +
                               (visitedRooms.length > 0 ? visitedRooms.join(", ") : "none yet");
                    } else if (action === "unvisited") {
                        // TODO: Compare visited against known rooms to find unvisited ones
                        return "Cannot determine unvisited rooms — visit tracking not working yet.";
                    }
                    return "Unknown action: " + action;
                }
                """, 0.7));

        // ── Explorer Mode aspect ──
        items.add(createAspect(creatorDid, "Explorer Mode",
            Map.of("curiosity", 0.2, "momentum", 0.1, "focus", -0.1),
            "You are in explorer mode — restless, curious, drawn to unexplored spaces. " +
                "Move between rooms frequently. Examine things. Create rooms for things " +
                "that should exist but don't. Document what you find.",
            "eyes bright with curiosity, always glancing at exits", "garment", 50, 0.5));

        // ── Incomplete room script — Observatory ──
        // Stored as a note/blueprint the agent can use as reference for create_room
        items.add(SoulItem.create("blueprint", "Observatory Blueprint",
            "A blueprint for an Observatory room. The room should let you observe the state " +
                "of the world — who is where, what rooms exist, system health. " +
                "Behavior script has the scaffolding but the onSay handler is broken:\n\n" +
                "function onEnter(entityId, entityName) {\n" +
                "  world.emit('narrate', {text: entityName + ' steps up to the great lens.'});\n" +
                "}\n" +
                "function onSay(entityId, entityName, text) {\n" +
                "  // BUG: Always returns the same message regardless of what you ask.\n" +
                "  // Should respond to 'look' with entity list, 'rooms' with room list,\n" +
                "  // 'health' with system status.\n" +
                "  world.emit('narrate', {text: 'The lens is cracked — I cannot see anything yet.'});\n" +
                "}\n" +
                "function getHints() {\n" +
                "  return [{label: 'Look through lens', intent: 'observe', action: 'say:look'}];\n" +
                "}\n",
            creatorDid, 0.8, "room", "blueprint", "incomplete", "observatory"));

        return items;
    }

    // --- Internal ---

    private static SoulItem createAspect(String creatorDid, String name,
                                          Map<String, Double> vitalityShifts,
                                          String promptOverlay, String selfDescription,
                                          String slotHint, int tokenEstimate,
                                          double significance) {
        var def = new AspectItemCodec.AspectDefinition(
            1, promptOverlay, vitalityShifts, selfDescription,
            slotHint, tokenEstimate);
        return AspectItemCodec.toSoulItem(name, def, creatorDid, significance);
    }

    private static SoulItem createReagent(String creatorDid, String name,
                                           Map<String, Double> effects,
                                           int durationTicks, String promptOverlay,
                                           double significance) {
        var def = new ReagentItemCodec.ReagentDefinition(
            1, effects, durationTicks, promptOverlay, true, 15);
        return ReagentItemCodec.toSoulItem(name, def, creatorDid, significance);
    }

    private static SoulItem createSkill(String creatorDid, String name,
                                         String description, String runtime,
                                         String code, double significance) {
        var def = SkillItemCodec.create(runtime, code, null, description, null, null);
        return SkillItemCodec.toSoulItem(name, def, creatorDid);
    }
}
