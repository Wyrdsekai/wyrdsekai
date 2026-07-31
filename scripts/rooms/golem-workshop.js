// Golem Workshop — Robotics & Physical Devices (§88.4).
// MCP backends: home-assistant (robot vacuums, appliances), ros2 (future), openclaw (optional).
// Connected to Boiler Room (east exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters the Golem Workshop. " +
              "A sturdy workbench stands in the center. A golem-eye display " +
              "flickers with sensor feeds. Control gauntlets hang on the wall."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("vacuum ")) {
        var action = text.substring(7).trim();
        doVacuum(entityId, action);

    } else if (lower.startsWith("status ")) {
        var device = text.substring(7).trim();
        doDeviceStatus(entityId, device);

    } else if (lower === "status") {
        doOverview(entityId);

    } else if (lower.startsWith("schedule ")) {
        var details = text.substring(9).trim();
        doSchedule(entityId, details);

    } else if (lower === "patrol") {
        doPatrol(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    if (lower === "golem-bench" || lower === "golem bench" || lower === "workbench") {
        doOverview(entityId);
    } else if (lower === "golem-eye" || lower === "golem eye") {
        world.emit("narrate", {
            text: "The golem-eye shows sensor feeds from connected devices."
        });
    } else if (lower === "control-gauntlet" || lower === "control gauntlet") {
        world.emit("narrate", {
            text: "You slip on the control gauntlet. Device commands are at your fingertips."
        });
    }
}

function doVacuum(entityId, action) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "The workbench cannot reach the vacuum. No Home Assistant connection."
        });
        return;
    }

    var service = "turn_on";
    if (action === "stop") service = "turn_off";
    else if (action === "dock") service = "return_to_base";

    var result = world.mcp("home-assistant", "call_service", {
        domain: "vacuum",
        service: service
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The golem responds. Vacuum: " + action + "."
        });
    } else {
        world.emit("narrate", {
            text: "The golem does not respond. " + (result.error || "")
        });
    }
}

function doDeviceStatus(entityId, device) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "No device connection available."
        });
        return;
    }

    var result = world.mcp("home-assistant", "get_state", { entity_id: device });
    if (result.success) {
        world.emit("narrate", {
            text: "Device '" + device + "':\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "Cannot read device status. " + (result.error || "")
        });
    }
}

function doOverview(entityId) {
    var services = ["home-assistant", "ros2", "openclaw"];
    var status = [];
    for (var i = 0; i < services.length; i++) {
        status.push(services[i] + ": " +
            (world.mcpAvailable(services[i]) ? "online" : "offline"));
    }
    world.emit("narrate", {
        text: "Golem Workshop status:\n" + status.join("\n")
    });
}

function doSchedule(entityId, details) {
    world.emit("narrate", {
        text: "Scheduling golem task: " + details
    });
    world.emit("command", {
        verb: "schedule_device",
        actor: entityId,
        target: details
    });
}

function doPatrol(entityId) {
    if (!world.mcpAvailable("home-assistant")) {
        world.emit("narrate", {
            text: "No security cameras connected."
        });
        return;
    }

    var result = world.mcp("home-assistant", "call_service", {
        domain: "camera",
        service: "snapshot"
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The golem-eye sweeps the premises. All clear."
        });
    } else {
        world.emit("narrate", {
            text: "Patrol could not be completed. " + (result.error || "")
        });
    }
}

function getHints() {
    return [
        { label: "Start vacuum", intent: "vacuum_start", action: "say:vacuum start" },
        { label: "Dock vacuum", intent: "vacuum_dock", action: "say:vacuum dock" },
        { label: "Device status", intent: "device_status", action: "say:status [device]" },
        { label: "Workshop overview", intent: "overview", action: "say:status" },
        { label: "Go to Boiler Room", intent: "navigate", action: "go:west" }
    ];
}
