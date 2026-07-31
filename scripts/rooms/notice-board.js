// The Notice Board — Family announcements and chore tracking room.
// A central place for posting notices, assigning chores, and tracking completion.
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

function loadNotices() {
    var raw = world.getProperty("board.notices");
    return raw ? JSON.parse(raw) : [];
}

function saveNotices(notices) {
    world.setProperty("board.notices", JSON.stringify(notices));
}

function loadChores() {
    var raw = world.getProperty("board.chores");
    return raw ? JSON.parse(raw) : [];
}

function saveChores(chores) {
    world.setProperty("board.chores", JSON.stringify(chores));
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("notice-board.enter", entityName)
    });
    var notices = loadNotices();
    var chores = loadChores();
    var pinnedCount = notices.filter(function(n) { return n.pinned; }).length;
    var activeChores = chores.filter(function(c) { return !c.done; }).length;
    if (pinnedCount > 0 || activeChores > 0) {
        world.emit("narrate", {
            text: world.t("notice-board.enter.summary", pinnedCount, activeChores)
        });
    }
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("post ")) {
        var message = text.substring(5).trim();
        if (message.length === 0) {
            world.emit("narrate", {
                text: world.t("notice-board.say.post_usage")
            });
            return;
        }
        var notices = loadNotices();
        notices.push({ message: message, postedBy: entityName, pinned: false });
        saveNotices(notices);
        world.log("Notice posted by " + entityName + ": " + message);
        world.emit("narrate", {
            text: world.t("notice-board.say.posted", entityName, message)
        });
    }

    if (lower.startsWith("assign ")) {
        // Format: assign <chore> to <person>
        var assignText = text.substring(7).trim();
        var toIndex = assignText.toLowerCase().indexOf(" to ");
        if (toIndex < 0) {
            world.emit("narrate", {
                text: world.t("notice-board.say.assign_usage")
            });
            return;
        }
        var choreName = assignText.substring(0, toIndex).trim();
        var person = assignText.substring(toIndex + 4).trim();
        var chores = loadChores();
        chores.push({ name: choreName, assignedTo: person, assignedBy: entityName, done: false });
        saveChores(chores);
        world.log("Chore assigned by " + entityName + ": " + choreName + " -> " + person);
        world.emit("narrate", {
            text: world.t("notice-board.say.assigned", entityName, choreName, person)
        });
    }

    if (lower === "list chores") {
        var chores = loadChores();
        var activeChores = chores.filter(function(c) { return !c.done; });
        if (activeChores.length === 0) {
            world.emit("narrate", {
                text: world.t("notice-board.say.chores_empty")
            });
        } else {
            var listing = activeChores.map(function(c, i) {
                return (i + 1) + ". " + c.name + " -> " + c.assignedTo;
            }).join("\n");
            world.emit("narrate", {
                text: world.t("notice-board.say.chores_list", listing)
            });
        }
    }

    if (lower.startsWith("done ")) {
        var choreName = text.substring(5).trim();
        var chores = loadChores();
        var found = false;
        for (var i = 0; i < chores.length; i++) {
            if (chores[i].name.toLowerCase() === choreName.toLowerCase() && !chores[i].done) {
                chores[i].done = true;
                found = true;
                saveChores(chores);
                world.log("Chore completed by " + entityName + ": " + chores[i].name);
                world.emit("narrate", {
                    text: world.t("notice-board.say.chore_done", entityName, chores[i].name)
                });
                break;
            }
        }
        if (!found) {
            world.emit("narrate", {
                text: world.t("notice-board.say.chore_not_found", choreName)
            });
        }
    }

    if (lower === "notices") {
        var notices = loadNotices();
        if (notices.length === 0) {
            world.emit("narrate", {
                text: world.t("notice-board.say.notices_empty")
            });
        } else {
            var listing = notices.map(function(n, i) {
                var prefix = n.pinned ? "[PINNED] " : "";
                return (i + 1) + ". " + prefix + n.message + " (by " + n.postedBy + ")";
            }).join("\n");
            world.emit("narrate", {
                text: world.t("notice-board.say.notices_list", listing)
            });
        }
    }
}

function getHints() {
    var notices = loadNotices();
    var chores = loadChores();
    var noticeCount = notices.length;
    var activeChoreCount = chores.filter(function(c) { return !c.done; }).length;
    return [
        { label: world.t("notice-board.hint.notices", noticeCount), intent: "view_notices", action: "say:notices" },
        { label: world.t("notice-board.hint.chores", activeChoreCount), intent: "list_chores", action: "say:list chores" },
        { label: world.t("notice-board.hint.post"), intent: "post_notice", action: "say:post " },
        { label: world.t("notice-board.hint.assign"), intent: "assign_chore", action: "say:assign " }
    ];
}
