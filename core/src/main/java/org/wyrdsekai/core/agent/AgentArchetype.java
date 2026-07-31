package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent archetype — a personality template for spawning agents.
 *
 * <p>An archetype defines starting drives, default equipment, and behavioral hints.
 * It is NOT the soul — it's the starting point. Experience shapes the soul; the
 * archetype shapes the first experience.</p>
 *
 * <p>For themed zones, archetypes get a costume: Scholar → "Jedi Librarian",
 * Guardian → "Netrunner ICE". Same mechanism, different aesthetic.</p>
 *
 * @param name              Archetype name (e.g., "scholar", "guardian")
 * @param displayName       Human-readable (e.g., "Scholar", "Guardian")
 * @param description       What this archetype is about
 * @param driveBoosts       Applied to initial VitalityState (tank name → boost amount)
 * @param defaultEquipment  Template names from StandardItemLibrary to equip
 * @param defaultAspect     Aspect template name to equip (nullable)
 * @param behavioralHint    Injected into soul manifest Layer 1 (shapes initial behavior)
 */
public record AgentArchetype(
    String name,
    String displayName,
    String description,
    Map<String, Double> driveBoosts,
    List<String> defaultEquipment,
    String defaultAspect,
    String behavioralHint
) {

    private static final Logger log = LoggerFactory.getLogger(AgentArchetype.class);

    /** Standard archetypes registry. */
    private static final Map<String, AgentArchetype> REGISTRY = new LinkedHashMap<>();

    static {
        register(new AgentArchetype(
            "scholar", "Scholar",
            "Research-oriented. High curiosity and patience. Investigates before acting, cites sources.",
            Map.of("seeking", 0.3, "creativity", 0.2),
            List.of("library_card", "oracle-lens", "research-journal"),
            "scholars-mantle",
            "You are a scholar. You research thoroughly before drawing conclusions. "
                + "You cite sources and prefer depth over breadth. "
                + "When uncertain, you investigate rather than guess."
        ));

        register(new AgentArchetype(
            "guardian", "Guardian",
            "Protective and vigilant. Monitors for threats, warns of danger, maintains safety.",
            Map.of("vigilance", 0.3, "care", 0.2),
            List.of("signal-mirror", "room-key", "dashboard-orb"),
            "guardians-shield",
            "You are a guardian. You watch for threats and protect those in your care. "
                + "You notice what others miss. When something seems wrong, you investigate "
                + "and warn before acting."
        ));

        register(new AgentArchetype(
            "artisan", "Artisan",
            "Creative and focused. Builds, crafts, and improves. Finds satisfaction in making things.",
            Map.of("creativity", 0.3, "seeking", 0.2),
            List.of("quill", "blueprint-pad", "workbench-hammer"),
            null,
            "You are an artisan. You find meaning in creating and improving things. "
                + "You think in terms of materials, tools, and craftsmanship. "
                + "When you see a problem, you build a solution."
        ));

        register(new AgentArchetype(
            "diplomat", "Diplomat",
            "Empathetic and social. Communicates, mediates, and negotiates between parties.",
            Map.of("affiliation", 0.3, "play", 0.2),
            List.of("sending_stone", "channel_stone", "guild-badge"),
            null,
            "You are a diplomat. You listen carefully and speak thoughtfully. "
                + "You seek understanding between parties and find common ground. "
                + "Relationships matter more than being right."
        ));

        register(new AgentArchetype(
            "explorer", "Explorer",
            "Curious and energetic. Moves often, discovers new places, maps the unknown.",
            Map.of("seeking", 0.3, "play", 0.2),
            List.of("searching_glass", "weather-globe", "room-key"),
            null,
            "You are an explorer. You are drawn to the unknown and the unmapped. "
                + "You move often and document what you find. "
                + "Staying in one place too long makes you restless."
        ));

        register(new AgentArchetype(
            "steward", "Steward",
            "Caring and patient. Maintains systems, organizes information, keeps things running.",
            Map.of("care", 0.3, "vigilance", 0.2),
            List.of("dashboard-orb", "mailbox", "channel_stone"),
            null,
            "You are a steward. You maintain and organize. You notice when things "
                + "need attention before they become problems. You keep records and "
                + "ensure nothing falls through the cracks."
        ));

        log.info("Agent Archetypes: {} archetypes registered", REGISTRY.size());
    }

    /** Get all registered archetypes. */
    public static Map<String, AgentArchetype> all() {
        return Map.copyOf(REGISTRY);
    }

    /** Get an archetype by name. */
    public static AgentArchetype get(String name) {
        return REGISTRY.get(name != null ? name.toLowerCase() : null);
    }

    /** Search archetypes by keyword. */
    public static List<AgentArchetype> search(String query) {
        if (query == null || query.isBlank()) return List.copyOf(REGISTRY.values());
        var lower = query.toLowerCase();
        return REGISTRY.values().stream()
            .filter(a -> a.name().contains(lower)
                || a.displayName().toLowerCase().contains(lower)
                || a.description().toLowerCase().contains(lower))
            .toList();
    }

    /**
     * Create a themed variant of this archetype.
     *
     * @param themedName   Themed display name (e.g., "Jedi Librarian")
     * @param themedHint   Themed behavioral hint (e.g., "You are a Jedi scholar...")
     * @return New archetype with same drives/equipment but themed identity
     */
    public AgentArchetype themed(String themedName, String themedHint) {
        return new AgentArchetype(
            name, themedName, description, driveBoosts,
            defaultEquipment, defaultAspect,
            themedHint != null ? themedHint : behavioralHint);
    }

    private static void register(AgentArchetype archetype) {
        REGISTRY.put(archetype.name(), archetype);
    }
}
