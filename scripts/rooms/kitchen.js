// The Kitchen — Family Hub room (§15).
// Central gathering space for family scheduling, chores, and notices.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("kitchen.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower === "calendar" || lower === "schedule" || lower === "today") {
        world.emit("narrate", {
            text: world.t("kitchen.say.calendar")
        });
    }

    if (lower.startsWith("event ") || lower.startsWith("add event ")) {
        // Format: event <title> | <time> | <type>
        var eventText = lower.startsWith("add event ") ? text.substring(10) : text.substring(6);
        world.emit("narrate", {
            text: world.t("kitchen.say.event", entityName, eventText.trim())
        });
    }

    if (lower === "chores" || lower === "tasks") {
        world.emit("narrate", {
            text: world.t("kitchen.say.chores")
        });
    }

    if (lower.startsWith("assign ")) {
        // Format: assign <chore> | <person> | <points>
        world.emit("narrate", {
            text: world.t("kitchen.say.assign", entityName)
        });
    }

    if (lower.startsWith("done ") || lower.startsWith("complete ")) {
        var choreId = text.substring(text.indexOf(" ") + 1).trim();
        world.emit("narrate", {
            text: world.t("kitchen.say.done", entityName, choreId)
        });
    }

    if (lower === "notices" || lower === "board") {
        world.emit("narrate", {
            text: world.t("kitchen.say.notices")
        });
    }

    if (lower.startsWith("post ")) {
        var notice = text.substring(5).trim();
        world.emit("narrate", {
            text: world.t("kitchen.say.post", entityName, notice)
        });
    }

    if (lower === "points" || lower === "scores" || lower === "leaderboard") {
        world.emit("narrate", {
            text: world.t("kitchen.say.points")
        });
    }

    if (lower === "screentime" || lower === "screen time") {
        world.emit("narrate", {
            text: world.t("kitchen.say.screentime")
        });
    }
}

function onUse(entityId, objectName, target) {
    if (objectName.toLowerCase().includes("wheel") || objectName.toLowerCase().includes("chore")) {
        world.emit("narrate", {
            text: world.t("kitchen.use.wheel")
        });
    }
    if (objectName.toLowerCase().includes("board") || objectName.toLowerCase().includes("notice")) {
        world.emit("narrate", {
            text: world.t("kitchen.use.board")
        });
    }
    if (objectName.toLowerCase().includes("calendar")) {
        world.emit("narrate", {
            text: world.t("kitchen.use.calendar")
        });
    }
}

function getHints() {
    return [
        { label: world.t("kitchen.hint.calendar"), intent: "view_calendar", action: "say:today" },
        { label: world.t("kitchen.hint.chores"), intent: "view_chores", action: "say:chores" },
        { label: world.t("kitchen.hint.notices"), intent: "view_notices", action: "say:notices" },
        { label: world.t("kitchen.hint.points"), intent: "view_points", action: "say:points" },
        { label: world.t("kitchen.hint.wheel"), intent: "spin_wheel", action: "use:chore wheel" },
        { label: world.t("kitchen.hint.east"), intent: "navigate_east", action: "go:east" }
    ];
}
