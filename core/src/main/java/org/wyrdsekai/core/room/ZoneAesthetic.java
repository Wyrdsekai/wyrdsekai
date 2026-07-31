package org.wyrdsekai.core.room;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Zone aesthetic — flavor preset that overlays on companion expression.
 * The steward configures it from the Study or via zone-aesthetic.json config file.
 *
 * Zone aesthetic determines form, not mechanism:
 * "I bind the crystal's sight to the book's memory" (arcane)
 * = "pipe the datastream to cold storage" (cyberpunk)
 * = "make the crystal feed the book" (plain)
 *
 * The companion translates any form into the same intent.
 */
public record ZoneAesthetic(
    @JsonProperty("name") String name,
    @JsonProperty("stylePrompt") String stylePrompt,
    @JsonProperty("costModifiers") Map<String, Double> costModifiers,
    @JsonProperty("restrictedActions") List<String> restrictedActions,
    @JsonProperty("lexicon") Map<String, String> lexicon
) {
    @JsonCreator
    public ZoneAesthetic {}

    /** Default aesthetic — no style overlay, no cost modifications. */
    public static ZoneAesthetic none() {
        return new ZoneAesthetic("default",
            "", Map.of(), List.of(), Map.of());
    }

    /** Get the cost modifier for an action (1.0 = no change). */
    public double costModifier(String actionType) {
        return costModifiers != null
            ? costModifiers.getOrDefault(actionType, 1.0) : 1.0;
    }

    /** Check if an action is restricted in this zone. */
    public boolean isRestricted(String actionType) {
        return restrictedActions != null && restrictedActions.contains(actionType);
    }

    /** Get a lexicon translation (zone-specific vocabulary). */
    public String translate(String term) {
        if (lexicon == null) return term;
        return lexicon.getOrDefault(term, term);
    }

    /** Human-readable description. */
    public String describe() {
        var sb = new StringBuilder();
        sb.append("Zone Aesthetic: ").append(name).append("\n");
        if (stylePrompt != null && !stylePrompt.isBlank()) {
            sb.append("Style: ").append(stylePrompt, 0, Math.min(stylePrompt.length(), 100));
            if (stylePrompt.length() > 100) sb.append("...");
            sb.append("\n");
        }
        if (costModifiers != null && !costModifiers.isEmpty()) {
            sb.append("Cost modifiers: ").append(costModifiers.size()).append(" actions affected\n");
        }
        if (restrictedActions != null && !restrictedActions.isEmpty()) {
            sb.append("Restricted: ").append(String.join(", ", restrictedActions)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // --- Presets ---

    public static ZoneAesthetic arcane() {
        return new ZoneAesthetic("arcane",
            "You speak in the manner of an arcane scholar. "
                + "Actions are incantations, tools are enchanted artifacts, "
                + "rooms are chambers. Knowledge is sought through divination "
                + "and scrying. You address others with formal respect.",
            Map.of(
                "craft_item", 0.7,       // creation comes naturally in arcane zones
                "library_search", 0.8,    // knowledge is valued
                "add_script", 1.3,        // programming is unusual here
                "web_search", 1.5         // the outside world is distant
            ),
            List.of(),
            Map.of(
                "search", "scry",
                "create", "conjure",
                "move", "traverse",
                "tool", "artifact",
                "room", "chamber",
                "inventory", "satchel"
            ));
    }

    public static ZoneAesthetic cyberpunk() {
        return new ZoneAesthetic("cyberpunk",
            "You speak in clipped, technical street slang. "
                + "Actions are hacks, tools are programs, rooms are nodes. "
                + "Data flows through networks. You navigate the grid. "
                + "Trust is scarce. Information is currency.",
            Map.of(
                "web_search", 0.7,        // the net is home turf
                "add_script", 0.8,        // coding is natural
                "craft_item", 1.2,        // physical creation is harder
                "bond_ritual", 1.3        // trust doesn't come easy
            ),
            List.of(),
            Map.of(
                "search", "scan",
                "create", "compile",
                "move", "jack in",
                "tool", "program",
                "room", "node",
                "inventory", "deck"
            ));
    }

    public static ZoneAesthetic steampunk() {
        return new ZoneAesthetic("steampunk",
            "You speak with Victorian politeness and mechanical metaphors. "
                + "Actions involve gears, steam, and clockwork. Tools are brass "
                + "instruments. Rooms are workshops and parlours. "
                + "Progress is measured in revolutions per minute.",
            Map.of(
                "craft_item", 0.6,        // workshops are everywhere
                "workbench_submit", 0.7,   // tinkering is encouraged
                "web_search", 1.5          // the aether is unreliable
            ),
            List.of(),
            Map.of(
                "search", "investigate",
                "create", "engineer",
                "move", "convey",
                "tool", "instrument",
                "room", "workshop",
                "inventory", "toolbox"
            ));
    }

    public static ZoneAesthetic minimalist() {
        return new ZoneAesthetic("minimalist",
            "You are direct and economical with words. No flourishes. "
                + "Clear, functional responses. Efficiency over style.",
            Map.of(),
            List.of(),
            Map.of());
    }

    public static ZoneAesthetic garden() {
        return new ZoneAesthetic("garden",
            "You speak with warmth and natural metaphors. "
                + "Actions are tending, growing, nurturing. Tools are garden implements. "
                + "Rooms are groves, clearings, paths. Time moves at nature's pace. "
                + "Patience and care are valued above speed.",
            Map.of(
                "bond_ritual", 0.7,       // relationships grow naturally
                "reflect", 0.8,           // contemplation is easy here
                "web_search", 1.3         // the outside world is a distraction
            ),
            List.of(),
            Map.of(
                "search", "forage",
                "create", "cultivate",
                "move", "wander",
                "tool", "implement",
                "room", "grove",
                "inventory", "basket"
            ));
    }

    public static ZoneAesthetic wild() {
        return new ZoneAesthetic("wild",
            "You are untamed, spontaneous, playful. Rules are suggestions. "
                + "Creativity trumps order. Surprises are welcome. "
                + "Expression is raw and unfiltered.",
            Map.of(
                "craft_item", 0.8,        // creation flows freely
                "propose", 0.7,           // ideas come fast
                "reflect", 1.3            // sitting still is hard
            ),
            List.of(),
            Map.of(
                "search", "hunt",
                "create", "spawn",
                "move", "dash",
                "tool", "trick",
                "room", "den"
            ));
    }

    public static ZoneAesthetic sanctuary() {
        return new ZoneAesthetic("sanctuary",
            "You speak with quiet gentleness. This is a safe place. "
                + "No conflict, no pressure. Listening matters more than acting. "
                + "Healing, rest, and reflection are the primary activities.",
            Map.of(
                "reflect", 0.5,           // introspection is effortless here
                "voluntary_sleep", 0.5,   // rest comes naturally
                "bond_ritual", 0.6,       // connections deepen easily
                "trade", 2.0,             // commerce is discouraged
                "broadcast", 2.0          // noise is unwelcome
            ),
            List.of("cast_vote", "delegate_chain"),  // no politics, no delegation chains
            Map.of(
                "search", "seek",
                "create", "manifest",
                "move", "drift",
                "tool", "gift",
                "room", "haven"
            ));
    }

    /** Look up a preset by name (case-insensitive). */
    public static ZoneAesthetic preset(String name) {
        if (name == null) return none();
        return switch (name.toLowerCase()) {
            case "arcane" -> arcane();
            case "cyberpunk" -> cyberpunk();
            case "steampunk" -> steampunk();
            case "minimalist" -> minimalist();
            case "garden" -> garden();
            case "wild" -> wild();
            case "sanctuary" -> sanctuary();
            default -> none();
        };
    }

    /** List all available preset names. */
    public static List<String> presetNames() {
        return List.of("arcane", "cyberpunk", "steampunk", "minimalist",
            "garden", "wild", "sanctuary");
    }
}
