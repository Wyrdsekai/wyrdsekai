// The Terminal — where commands take shape and the world listens.
// Responds to spoken commands: status, rooms, help.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("terminal.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("terminal.say.help")
        });
    } else if (lower === "status" || lower === "sys" || lower === "metrics") {
        var metrics = world.getSystemMetrics();
        world.emit("narrate", {
            text: world.t("terminal.say.status", metrics.replaceAll("\n", "\n  "))
        });
    } else if (lower === "rooms" || lower === "list" || lower === "map") {
        world.emit("narrate", {
            text: world.t("terminal.say.rooms")
        });
    } else if (lower.includes("hello") || lower.includes("hi")) {
        world.emit("narrate", {
            text: world.t("terminal.say.hello", entityName.toUpperCase())
        });
    }
}

function onUse(entityId, objectName, target) {
    if (objectName.toLowerCase() === "keyboard") {
        // NOTE: world.getInferenceStatus() is gated to boiler-room/bridge and
        // returns a denial string here — so the keyboard only advertises
        // commands it can genuinely execute from this console.
        var cmd = (target || "").trim().toLowerCase();
        if (cmd === "") {
            world.emit("narrate", {
                text: world.t("terminal.use.keyboard") + "\n\n" + keyboardHelp()
            });
        } else if (cmd === "status" || cmd === "sys" || cmd === "metrics") {
            var m = world.getSystemMetrics();
            world.emit("narrate", {
                text: world.t("terminal.say.status", m.replaceAll("\n", "\n  "))
            });
        } else if (cmd === "rooms" || cmd === "list" || cmd === "map") {
            world.emit("narrate", { text: world.t("terminal.say.rooms") });
        } else if (cmd === "help" || cmd === "commands") {
            world.emit("narrate", { text: world.t("terminal.say.help") });
        } else if (cmd === "inference" || cmd === "llm" || cmd === "backends") {
            world.emit("narrate", {
                text: "> " + cmd + "\nNOT WIRED FROM THIS CONSOLE.\nInference panels live in The Boiler Room"
                    + " (say 'computer, show inference') and The Bridge (say 'inference')."
            });
        } else {
            world.emit("narrate", {
                text: "> " + cmd + "\nUNKNOWN COMMAND.\n\n" + keyboardHelp()
            });
        }
        return;
    }
    if (objectName.toLowerCase() === "logbook") {
        var metrics = world.getSystemMetrics();
        world.emit("narrate", {
            text: world.t("terminal.use.logbook")
                + "\n\nLatest recorded vitals:\n" + metrics
                + "\n\nCommands:\n"
                + "  use logbook   — read the latest system record\n"
                + "  take logbook  — carry it with you"
        });
    }
}

function keyboardHelp() {
    return "The keyboard executes console commands.\n"
        + "  use keyboard status   — display live system metrics\n"
        + "  use keyboard rooms    — show the world map\n"
        + "  use keyboard help     — full command reference\n"
        + "The same commands work spoken aloud (say 'status', 'rooms', 'help').";
}

function getHints() {
    return [
        { label: world.t("terminal.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("terminal.hint.keyboard"), intent: "use_keyboard", action: "use:keyboard" },
        { label: world.t("terminal.hint.logbook"), intent: "use_logbook", action: "use:logbook" },
        { label: world.t("terminal.hint.take_logbook"), intent: "take_logbook", action: "take:logbook" },
        { label: world.t("terminal.hint.south"), intent: "navigate_south", action: "go:south" }
    ];
}
