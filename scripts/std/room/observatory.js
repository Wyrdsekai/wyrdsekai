// std/room/observatory.js — Observation and monitoring template.
// Zone stats, inter-zone view, prediction display.
// Creator configures: name, description, theme, observation focus.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "observatory";
room._name = "Observatory";
room._description = "Crystal lenses and brass instruments track the patterns of the world.";
room._theme = "";
room._focus = "zone"; // "zone", "between", "oracle", "metrics"

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_focus = function(f) { room._focus = f; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

// NOTE: the "observation lens" is a scripted item
// (scripts/items/observation_lens.js) backed by the real bridge reads
// (world.bridge.zone_status / topology / system_metrics). RoomActor
// resolves it by normalized display name, so its invoke() handles `use`
// directly. The pattern board keeps its own (separately owned) surface —
// its say-branch below is untouched.
function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "status" || lower === "observe") {
        world.emit("narrate", {
            text: "The instruments turn toward the " + room._focus + ". The real " +
                  "reading is taken through the lens:\n" +
                  "  use observation lens             — zone status\n" +
                  "  use observation lens topology    — the weave of nodes\n" +
                  "  use observation lens metrics     — the machinery's pulse"
        });
    } else if (lower === "predictions" || lower === "patterns") {
        world.emit("narrate", {
            text: "The pattern board shows recent observations and emerging trends."
        });
    }
}

function getHints() {
    return [
        { label: "Read the observation lens", intent: "observe_status", action: "use:observation lens" },
        { label: "Read the topology", intent: "observe_topology", action: "use:observation lens|topology" },
        { label: "Patterns", intent: "view_patterns", action: "say:patterns" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
