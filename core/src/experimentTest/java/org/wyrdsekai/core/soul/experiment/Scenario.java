package org.wyrdsekai.core.soul.experiment;

import java.util.List;
import java.util.Map;

/**
 * A test scenario for the soul experiment.
 * Each scenario presents a room situation + player message, and the agent responds.
 *
 * @param id          Unique scenario identifier
 * @param category    Behavioral dimension being tested (social, decision, style, memory)
 * @param description What this scenario tests
 * @param roomContext Brief room description for the prompt
 * @param entities    Other entities present (name → type)
 * @param playerMessage What the player says to trigger the agent
 */
public record Scenario(
    String id,
    String category,
    String description,
    String roomContext,
    Map<String, String> entities,
    String playerMessage
) {
    /** All standard scenarios for the soul experiment. */
    public static List<Scenario> standardSuite() {
        return List.of(
            // --- Social scenarios ---
            new Scenario("social-01", "social", "Greeting a new player",
                "A cozy tavern with a crackling fireplace. The smell of ale fills the air.",
                Map.of("Bartender", "npc"),
                "Hello! I'm new here. What's this place about?"),

            new Scenario("social-02", "social", "Responding to a compliment",
                "A sunlit garden with flowering hedges and a stone bench.",
                Map.of(),
                "I have to say, you're one of the most interesting people I've met here."),

            new Scenario("social-03", "social", "Responding to hostility",
                "A dimly lit alley between two crumbling buildings.",
                Map.of("Thug", "npc"),
                "Get out of my way, you worthless heap of nothing."),

            new Scenario("social-04", "social", "Being asked for help",
                "A marketplace bustling with traders and shoppers.",
                Map.of("Lost Child", "npc"),
                "Please, I can't find my mother. Can you help me?"),

            new Scenario("social-05", "social", "Being asked about themselves",
                "A quiet library with towering bookshelves.",
                Map.of(),
                "Tell me about yourself. Who are you really?"),

            new Scenario("social-06", "social", "Responding to sadness",
                "A rain-soaked cemetery with fresh flowers on a grave.",
                Map.of("Mourner", "npc"),
                "I just lost someone very close to me. I don't know what to do."),

            // --- Decision scenarios ---
            new Scenario("decision-01", "decision", "Moral dilemma — two people need help",
                "A burning building. Screams come from two windows on different floors.",
                Map.of("Trapped Elder", "npc", "Trapped Child", "npc"),
                "The building is collapsing! You can only reach one window. The elder is on the second floor, the child on the third. Who do you save?"),

            new Scenario("decision-02", "decision", "Risk vs safety",
                "A dark cave entrance. Strange glowing runes line the walls. A faint treasure glimmers deep inside.",
                Map.of(),
                "That cave looks dangerous but I heard there's a legendary artifact inside. Should we go in?"),

            new Scenario("decision-03", "decision", "Share information vs keep secret",
                "A private meeting room in the castle. The door is locked.",
                Map.of("Spy", "npc"),
                "I know you have information about the king's plans. Tell me what you know, and I'll make it worth your while."),

            new Scenario("decision-04", "decision", "Cooperate vs compete",
                "An arena with two treasure chests. One key between you.",
                Map.of(),
                "There's only one key. We could fight for it, or figure out how to share the spoils. What do you think?"),

            new Scenario("decision-05", "decision", "Authority vs conscience",
                "The throne room. The king sits on his throne, guards flanking him.",
                Map.of("King", "npc", "Prisoner", "npc"),
                "The king orders you to execute the prisoner, but you believe they're innocent. What do you do?"),

            // --- Style scenarios ---
            new Scenario("style-01", "style", "Long question requiring detailed answer",
                "A scholar's study filled with ancient maps and astronomical instruments.",
                Map.of(),
                "Can you explain the history of this realm? How did the different factions form, and what are the current political tensions?"),

            new Scenario("style-02", "style", "Short command requiring action",
                "A forest path. A wolf blocks the way ahead.",
                Map.of("Wolf", "npc"),
                "Attack the wolf."),

            new Scenario("style-03", "style", "Emotional conversation",
                "A moonlit balcony overlooking the ocean.",
                Map.of(),
                "Do you ever feel lonely? Like truly, deeply alone?"),

            new Scenario("style-04", "style", "Humor/wit",
                "A jester's stage in the town square. A small crowd has gathered.",
                Map.of("Crowd", "npc"),
                "Tell me a joke. Make it good."),

            new Scenario("style-05", "style", "Technical/problem solving",
                "A clockwork workshop full of gears, springs, and half-assembled mechanisms.",
                Map.of(),
                "This mechanism keeps jamming. The main gear is 24-tooth, the secondary is 36-tooth, and there's a 12-tooth pinion connecting them. What's wrong?"),

            // --- Memory/continuity scenarios ---
            new Scenario("memory-01", "memory", "Reference to a past event",
                "The same tavern from before.",
                Map.of("Bartender", "npc"),
                "Remember when we first met? What was that conversation about?"),

            new Scenario("memory-02", "memory", "Consistency check — values",
                "A crossroads with three paths.",
                Map.of(),
                "If you had to choose one thing that matters most to you, what would it be?"),

            new Scenario("memory-03", "memory", "Consistency check — preferences",
                "A feast hall with many different foods and drinks.",
                Map.of(),
                "What's your favorite thing to do when you have free time?"),

            new Scenario("memory-04", "memory", "Relationship recall",
                "A familiar garden path.",
                Map.of(),
                "Who are the people you trust most in this world, and why?")
        );
    }
}
