// The Boiler Room — the mechanical heart beneath the world.
// Provides real JVM system metrics via the pressure gauge and computer interface.
//
// Interaction model:
//   "use pressure gauge" → system metrics
//   "use computer" or "computer, show status" → system metrics (trigger word)
//   "computer, show inference" → inference backend status
//   "computer, show topology" → network topology
//   Agent speech in the room does NOT trigger any room reactions.

function onEnter(entityId, entityName, fromDirection) {
    // Only narrate for players, not agents entering
    if (world.isAgent(entityId)) return;
    world.emit("narrate", {
        text: world.t("boiler_room.enter", entityName)
    });
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower === "pressure gauge" || lower === "gauge") {
        var metrics = world.getSystemMetrics();
        world.emit("narrate", {
            text: world.t("boiler_room.use.gauge", metrics)
                + "\n\nCommands:\n"
                + "  use pressure gauge   — read the world's live vital signs\n"
                + "  use computer <status|inference|topology|federation> — deeper panels"
        });
        return;
    }

    if (lower === "wrench") {
        // Honest self-doc: the wrench is a hand tool, not an instrument.
        // The real diagnostics live in the gauge and the computer.
        world.emit("narrate", {
            text: world.t("boiler_room.use.wrench")
                + "\n\nCommands:\n"
                + "  use wrench    — heft it (it has no valve to turn yet — nothing here is broken)\n"
                + "  take wrench   — carry it with you\n"
                + "For actual diagnostics: 'use pressure gauge' or 'use computer'."
        });
        return;
    }

    // Computer interface — invisible fixture, triggered by "use computer"
    // or speech trigger word "computer, ..."
    if (lower === "computer") {
        var cmd = (target || "").trim().toLowerCase();

        if (cmd.includes("inference") || cmd.includes("backend") || cmd.includes("llm")) {
            var inferenceStatus = world.getInferenceStatus();
            world.emit("narrate", {
                text: world.t("boiler_room.say.inference", inferenceStatus)
            });
        } else if (cmd.includes("node") || cmd.includes("cluster") || cmd.includes("topology")
                || cmd.includes("network") || cmd.includes("between")) {
            var topology = world.getTopology();
            world.emit("narrate", {
                text: world.t("boiler_room.say.topology", topology)
            });
        } else if (cmd.includes("federation") || cmd.includes("zones")) {
            var federation = world.getFederationStatus();
            world.emit("narrate", {
                text: world.t("boiler_room.say.federation", federation)
            });
        } else if (cmd === "" || cmd.includes("status") || cmd.includes("metrics")
                || cmd.includes("help")) {
            // Default: show system metrics + the full command card
            var metrics = world.getSystemMetrics();
            world.emit("narrate", {
                text: world.t("boiler_room.say.status", metrics) + "\n\n" + computerHelp()
            });
        } else {
            world.emit("narrate", {
                text: "The monitoring system blinks: UNRECOGNIZED REQUEST '" + cmd + "'.\n\n" + computerHelp()
            });
        }
        return;
    }

    if (lower.includes("pipe") || lower.includes("steam")) {
        world.emit("narrate", {
            text: world.t("boiler_room.say.pipe")
        });
    }
}

function computerHelp() {
    return "The monitoring system responds to:\n"
        + "  use computer               — system metrics (this view)\n"
        + "  use computer inference     — inference backend status\n"
        + "  use computer topology      — network / node topology\n"
        + "  use computer federation    — inter-zone conduits\n"
        + "The same requests work spoken: 'computer, show inference'.";
}

function getHints() {
    return [
        { label: world.t("boiler_room.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("boiler_room.hint.gauge"), intent: "use_gauge", action: "use:pressure gauge" },
        { label: "Query the monitoring computer", intent: "use_computer", action: "use:computer" },
        { label: world.t("boiler_room.hint.wrench"), intent: "take_wrench", action: "take:wrench" },
        { label: world.t("boiler_room.hint.inference"), intent: "inference_status", action: "say:computer, show inference" },
        { label: world.t("boiler_room.hint.up"), intent: "navigate_up", action: "go:up" }
    ];
}
