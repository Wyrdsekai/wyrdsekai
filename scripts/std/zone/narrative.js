// std/zone/narrative.js — Theme-driven zone generation.
// Takes a theme string and number of rooms/agents. Uses LLM to generate
// room names, descriptions, agent assignments, and argot seeds.
// The companion orchestrates creation — this script produces the plan.

/**
 * Generate a zone plan from a theme.
 *
 * @param params.theme      Theme description (e.g., "Arthurian Legend", "Cyberpunk Tokyo")
 * @param params.rooms      Number of rooms to create (default 5)
 * @param params.agents     Number of agents to spawn (default 2)
 * @param params.hub_name   Optional name for the central hub room
 * @returns Zone plan with rooms, agents, items, and argot seeds
 */
function invoke(params) {
    var theme = params.theme || "fantasy";
    var numRooms = parseInt(params.rooms) || 5;
    var numAgents = parseInt(params.agents) || 2;
    var hubName = params.hub_name || "";

    // Use LLM to generate the zone plan based on theme
    var prompt = "Generate a zone plan for the theme: " + theme + ".\n"
        + "Create exactly " + numRooms + " rooms and " + numAgents + " agents.\n\n"
        + "For each room, provide:\n"
        + "- name: a themed room name\n"
        + "- template: one of (hub, study, workshop, library, market, garden, hall, observatory, gate)\n"
        + "- description: 1-2 sentence themed description\n\n"
        + "For each agent, provide:\n"
        + "- name: a themed character name\n"
        + "- archetype: one of (scholar, guardian, artisan, diplomat, explorer, steward)\n"
        + "- hint: 1 sentence behavioral hint in the theme's voice\n\n"
        + "Also provide:\n"
        + "- argot_seeds: 5 themed vocabulary words/phrases for the zone\n\n"
        + "The first room MUST use the 'hub' template (it's the central gathering space).\n"
        + (hubName ? "The hub room should be named: " + hubName + "\n" : "")
        + "\nRespond ONLY with valid JSON in this format:\n"
        + '{"rooms":[{"name":"...","template":"...","description":"..."}],'
        + '"agents":[{"name":"...","archetype":"...","hint":"..."}],'
        + '"argot_seeds":["...","...."]}';

    var response = world.llm.analyze(theme, prompt);

    // Try to parse the LLM response as JSON
    try {
        // Strip markdown code fences if present
        var cleaned = response;
        if (cleaned.indexOf("```json") >= 0) {
            cleaned = cleaned.substring(cleaned.indexOf("```json") + 7);
            if (cleaned.indexOf("```") >= 0) {
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
        } else if (cleaned.indexOf("```") >= 0) {
            cleaned = cleaned.substring(cleaned.indexOf("```") + 3);
            if (cleaned.indexOf("```") >= 0) {
                cleaned = cleaned.substring(0, cleaned.indexOf("```"));
            }
        }
        cleaned = cleaned.trim();

        var plan = JSON.parse(cleaned);

        // Validate structure
        if (!plan.rooms || !plan.agents) {
            return { error: "LLM response missing rooms or agents", raw: response };
        }

        // Ensure first room is a hub
        if (plan.rooms.length > 0 && plan.rooms[0].template !== "hub") {
            plan.rooms[0].template = "hub";
        }

        return {
            theme: theme,
            rooms: plan.rooms,
            agents: plan.agents,
            argot_seeds: plan.argot_seeds || [],
            room_count: plan.rooms.length,
            agent_count: plan.agents.length
        };
    } catch (e) {
        // Return raw response if JSON parsing fails
        return {
            error: "Failed to parse zone plan as JSON: " + e.message,
            raw: response,
            theme: theme
        };
    }
}
