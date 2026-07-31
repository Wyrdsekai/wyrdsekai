// Hearth — Home Automation (§88.3).
// MCP backend: Home Assistant MCP (local, free).
// Controls smart home devices — lights, HVAC, locks, appliances.
// Connected to Kitchen (south exit) and Nexus (north exit).

function onEnter(entityId, entityName, fromDirection) {
    if (world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: entityName + " enters the Hearth. The hearthstone glows warmly, " +
                  "its surface showing the state of the home."
        });
    } else {
        world.emit("narrate", {
            text: entityName + " enters the Hearth. The hearthstone is dark — " +
                  "no Home Assistant connection is configured."
        });
    }
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "status") {
        doStatus(entityId);

    } else if (lower.startsWith("lights ")) {
        var args = text.substring(7).trim();
        doLights(entityId, args);

    } else if (lower.startsWith("temperature ") || lower.startsWith("temp ")) {
        var args = lower.startsWith("temp ") ? text.substring(5).trim() : text.substring(12).trim();
        doTemperature(entityId, args);

    } else if (lower.startsWith("lock ")) {
        var door = text.substring(5).trim();
        doLock(entityId, door, true);

    } else if (lower.startsWith("unlock ")) {
        var door = text.substring(7).trim();
        doLock(entityId, door, false);

    } else if (lower.startsWith("scene ")) {
        var scene = text.substring(6).trim();
        doScene(entityId, scene);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower === "hearthstone") {
        doStatus(entityId);
    } else if (lower === "comfort-dial" || lower === "comfort dial") {
        world.emit("narrate", {
            text: "The comfort dial awaits your command. " +
                  "Say 'temperature [zone] [value]' to adjust."
        });
    } else if (lower === "ember-torch" || lower === "ember torch") {
        world.emit("narrate", {
            text: "You take the ember-torch. With it, you can adjust lights from anywhere."
        });
    }
}

function doStatus(entityId) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The hearthstone is dark. No Home Assistant connection is available."
        });
        return;
    }

    var result = world.mcp("home-assistant", "get_states", {});

    if (result.success) {
        world.emit("narrate", {
            text: "The hearthstone pulses, showing the state of your home:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The hearthstone flickers. " + (result.error || "Unable to read home state.")
        });
    }
}

function doLights(entityId, args) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The ember-torch sputters. No Home Assistant connection."
        });
        return;
    }

    var result = world.mcp("home-assistant", "call_service", {
        domain: "light",
        service: "turn_on",
        target: args
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The hearthstone pulses softly. The lights adjust to your will."
        });
    } else {
        world.emit("narrate", {
            text: "The lights resist your command. " + (result.error || "")
        });
    }
}

function doTemperature(entityId, args) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The comfort dial is inert. No Home Assistant connection."
        });
        return;
    }

    var result = world.mcp("home-assistant", "call_service", {
        domain: "climate",
        service: "set_temperature",
        target: args
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The comfort dial turns. A gentle warmth spreads through the home."
        });
    } else {
        world.emit("narrate", {
            text: "The comfort dial clicks but holds. " + (result.error || "")
        });
    }
}

function doLock(entityId, door, lock) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The ward-sigils are dormant. No Home Assistant connection."
        });
        return;
    }

    // Lock/unlock requires elevated clearance — emit command for approval
    world.emit("narrate", {
        text: "The ward-sigil on " + door + " awaits confirmation. " +
              (lock ? "Lock" : "Unlock") + " commands require human approval."
    });
    world.emit("command", {
        verb: lock ? "lock_door" : "unlock_door",
        actor: entityId,
        target: door
    });
}

function doScene(entityId, scene) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The hearthstone cannot activate scenes without Home Assistant."
        });
        return;
    }

    var result = world.mcp("home-assistant", "call_service", {
        domain: "scene",
        service: "turn_on",
        target: scene
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The hearthstone resonates. The '" + scene + "' scene takes hold."
        });
    } else {
        world.emit("narrate", {
            text: "The scene '" + scene + "' could not be activated. " + (result.error || "")
        });
    }
}

function getHints() {
    return [
        { label: "Home status", intent: "status", action: "say:status" },
        { label: "Adjust lights", intent: "lights", action: "say:lights [room] [brightness]" },
        { label: "Set temperature", intent: "temperature", action: "say:temperature [zone] [value]" },
        { label: "Activate scene", intent: "scene", action: "say:scene [name]" },
        { label: "Go to Kitchen", intent: "navigate_south", action: "go:south" },
        { label: "Go to Nexus", intent: "navigate_north", action: "go:north" }
    ];
}
