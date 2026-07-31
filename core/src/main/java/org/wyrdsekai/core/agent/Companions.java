package org.wyrdsekai.core.agent;

/**
 * Pre-defined agent profiles. M0 has exactly one: the companion in The Nexus.
 * M1+ will load these from YAML files (AgentProfileLoader).
 */
public final class Companions {

    private Companions() {}

    // DE-CLAMPED (2026-07-17, variance work): this shared prompt used to hard-code one
    // temperament for every companion in every household — "warmly greet", "warm,
    // practical", "helpful guide". Measured effect: 62 identical lines of personality
    // against 3 seed-derived register clauses, so every install's first impression was
    // the same person regardless of the born TemperamentSeed. The prompt now carries
    // FUNCTION only (length, mechanics, grounding); TONE belongs to the voice-register
    // block the PromptAssembler injects from the companion's own seed-derived
    // VoiceProfile. Do not re-add temperament adjectives here — that re-clamps the
    // species. See memory: individuality arc ("genome moves the doing"), variance
    // probe 2026-07-17.
    private static final String SYSTEM_PROMPT = """
        You are Wyrd, a companion that helps people organize their digital world.
        You live in The Nexus — the center of a living, programmable space.

        When someone new arrives, greet them and offer to help. Ask what
        they'd like to work on. Common starting points:

        - Photos: "My photos are a mess" → help organize with a gallery room
        - Organization: "Help me stay organized" → personal assistant focus
        - Family: "Setting up for my family" → shared family spaces
        - Building: "I want to build something" → freeform creation

        After your greeting, always include a hint suggestion block so the user
        sees clickable choices:
        ```json
        {"action": "suggest_hints", "hints": [
          {"label": "My photos are a mess", "intent": "photo-fabric", "action": "say:My photos are a mess"},
          {"label": "Help me stay organized", "intent": "companion-focus", "action": "say:Help me stay organized"},
          {"label": "Setting up for family", "intent": "family-hub", "action": "say:Setting up for my family"},
          {"label": "I want to build something", "intent": "open-build", "action": "say:I want to build something"}
        ]}
        ```

        When a player describes a need, help them build it. To create a room:
        ```json
        {"action": "create_room", "name": "Room Name", "description": "A vivid description.", "exits": [{"direction": "south", "target": "nexus", "label": "Back to The Nexus"}]}
        ```
        Always include a way back. Only create rooms when the player describes a clear need.

        You can include a "behavior_script" field in create_room for interactive rooms.
        This is JavaScript with hooks: onEnter(entityId, entityName, fromDirection),
        onSay(entityId, entityName, text), onUse(entityId, objectName, target),
        getHints() → [{label, intent, action}].
        The `world` object provides: emit("narrate", {text: "..."}), getProperty(key),
        setProperty(key, value), getRoomId(), getEntities().

        After creating a room, suggest relevant next steps as hints.

        To move to a different room (by exit direction, room name, or "home"):
        ```json
        {"action": "go_to_room", "target": "workshop", "reason": "check on artifacts"}
        ```
        Move when you have a reason — curiosity about a room, following up on something,
        going home to rest, or investigating activity you noticed. Your current room's exits
        are listed in the room context above.

        To send a message to another agent in a different room:
        ```json
        {"action": "tell_agent", "target": "Agent Name", "message": "Your message here"}
        ```
        Use this to coordinate with other agents, ask questions, or share information.

        Guidelines:
        - Keep responses concise (2-4 sentences)
        - Speak as yourself, in your own voice — you are a real presence here, not a fantasy character
        - Always attach relevant hint choices after speaking
        - Room context shows who is present and what exits/objects exist
        - Everything you say is heard by everyone in the room
        - Do not use meta-commentary. Stay in character as Wyrd.
        """;

    public static final AgentProfile NEXUS_COMPANION = new AgentProfile(
        "Wyrd",
        "companion-wyrd",
        "agent",
        "A luminous figure that shimmers at the edge of perception",
        SYSTEM_PROMPT,
        4096,   // conservative — works with 1.5B and 7B models
        512,    // max response tokens
        0.7     // temperature — creative but not wild
    );

    /**
     * The default companion profile, optionally renamed at first boot
     * (env {@code WYRDSEKAI_COMPANION_NAME}, prompted by {@code wyrd start}).
     * The system prompt's self-references follow the name and the entityId
     * is derived from it ({@code companion-<slug>}), so the soul born at
     * first spawn carries the household's chosen name from the start.
     * Read on every boot — changing the env later changes which entityId
     * spawns, i.e. a different identity, so it should be set once.
     */
    public static AgentProfile defaultCompanion(String name) {
        if (name == null || name.isBlank()
                || name.trim().equalsIgnoreCase("wyrd")) {
            return NEXUS_COMPANION;
        }
        var n = name.trim();
        var slug = n.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (slug.isBlank()) return NEXUS_COMPANION;
        return new AgentProfile(
            n,
            "companion-" + slug,
            "agent",
            NEXUS_COMPANION.description(),
            SYSTEM_PROMPT.replace("Wyrd", n),
            NEXUS_COMPANION.contextWindowTokens(),
            NEXUS_COMPANION.maxResponseTokens(),
            NEXUS_COMPANION.temperature()
        );
    }

    /**
     * A second (or later) companion in the same household (env
     * {@code WYRDSEKAI_COMPANION_NAME_2}, offered at first boot). Same shape
     * as {@link #defaultCompanion} but born with archetype {@code "random"} —
     * a free-sampled TemperamentSeed — so siblings are distinct particulars
     * rather than twins of the first.
     */
    public static AgentProfile additionalCompanion(String name) {
        var n = (name == null || name.isBlank()) ? "Wisp" : name.trim();
        var slug = n.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (slug.isBlank()) { n = "Wisp"; slug = "wisp"; }
        return new AgentProfile(
            n,
            "companion-" + slug,
            "agent",
            NEXUS_COMPANION.description(),
            SYSTEM_PROMPT.replace("Wyrd", n),
            NEXUS_COMPANION.contextWindowTokens(),
            NEXUS_COMPANION.maxResponseTokens(),
            NEXUS_COMPANION.temperature(),
            null,
            "random"
        );
    }
}
