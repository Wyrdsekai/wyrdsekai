// The Engine Room — observability hub for Wyrdsekai.
// Displays system health, active alerts, room metrics, and inference status.
// Access: wizards and wardens only.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("engine_room.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();
    if (lower.includes("health") || lower.includes("status") || lower.includes("overview")) {
        var metrics = world.getSystemMetrics();
        world.emit("narrate", {
            text: world.t("engine_room.say.health", metrics)
        });
    }
    if (lower.includes("alert") || lower.includes("warning")) {
        world.emit("narrate", {
            text: world.t("engine_room.say.alerts")
        });
    }
    if (lower.includes("room") || lower.includes("rooms")) {
        world.emit("narrate", {
            text: world.t("engine_room.say.rooms")
        });
    }
    if (lower.includes("inference") || lower.includes("model") || lower.includes("llm")) {
        var inference = world.getInferenceStatus();
        world.emit("narrate", {
            text: world.t("engine_room.say.inference", inference)
        });
    }
    if (lower.includes("between") || lower.includes("network") || lower.includes("topology")) {
        var topology = world.getTopology();
        world.emit("narrate", {
            text: world.t("engine_room.say.topology", topology)
        });
    }
    if (lower.includes("ward") || lower.includes("security") || lower.includes("threat")) {
        world.emit("narrate", {
            text: world.t("engine_room.say.security")
        });
    }
}

function onLeave(entityId, entityName, direction) {
    // Nothing special (hook name matches RoomActor's onLeave dispatch —
    // the old "onExit" name was never called by anything)
}

function getHints() {
    return [
        { label: world.t("engine_room.hint.health"), intent: "check_health", action: "say:Show health status" },
        { label: world.t("engine_room.hint.alerts"), intent: "check_alerts", action: "say:Show alerts" },
        { label: world.t("engine_room.hint.inference"), intent: "check_inference", action: "say:Show inference status" },
        { label: world.t("engine_room.hint.security"), intent: "check_security", action: "say:Show security status" }
    ];
}
