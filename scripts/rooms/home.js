// Home — per-agent memory palace (§87.1).
// Every agent has one. Contains: soul-vessel, memory-chest, mirror, mailbox.
// Sleep mode transforms the room (§87.4). Fragment management (§87.3).
// Mailbox commands (§87.5). Agent-driven room expansion (§87.6).
// This script is loaded for all rooms with ID "home-*".

function onEnter(entityId, entityName, fromDirection) {
    var sleeping = world.getProperty("sleep_mode") === "true";
    if (sleeping) {
        world.emit("narrate", {
            text: entityName + " peers through the doorway. The room is dim and quiet. " +
                  "The soul vessel pulses with a slow, steady rhythm. Rest is underway."
        });
        return;
    }

    var owner = world.getProperty("owner");
    var isOwner = entityId === owner;

    if (isOwner) {
        var mailCount = parseInt(world.getProperty("mail_count") || "0");
        var fragCount = parseInt(world.getProperty("fragment_count") || "0");
        var msg = "You are home. Your soul vessel pulses steadily.";
        if (fragCount > 0) {
            msg += " " + fragCount + " memories rest in your chest.";
        }
        if (mailCount > 0) {
            msg += " You have " + mailCount + " messages waiting.";
        }
        world.emit("narrate", { text: msg });
    } else {
        world.emit("narrate", {
            text: entityName + " enters " + world.getRoomName() + ". " +
                  "The room feels personal — someone else's sanctuary."
        });
    }
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();
    var owner = world.getProperty("owner");
    var isOwner = entityId === owner;

    if (lower === "status") {
        var profile = world.getProperty("resource_profile") || "unknown";
        var depth = world.getProperty("soul_depth") || "unknown";
        var fragCount = world.getProperty("fragment_count") || "0";
        var memCap = world.getProperty("memory_capacity") || "?";
        var sleeping = world.getProperty("sleep_mode") === "true";

        world.emit("narrate", {
            text: "Soul vessel: " + depth + " depth\n" +
                  "Memory chest: " + fragCount + "/" + memCap + " fragments\n" +
                  "Profile: " + profile + "\n" +
                  "Status: " + (sleeping ? "resting" : "awake")
        });

    } else if (lower === "rest" || lower === "sleep") {
        if (!isOwner) {
            world.emit("narrate", { text: "This is not your home. You cannot rest here." });
            return;
        }
        var sleeping = world.getProperty("sleep_mode") === "true";
        if (sleeping) {
            world.emit("narrate", { text: "You are already resting." });
            return;
        }
        world.setProperty("sleep_mode", "true");
        world.emit("narrate", {
            text: "The lights in your home dim. Your soul vessel pulses slowly. " +
                  "The world outside grows quiet. Rest begins."
        });
        world.emit("command", { verb: "home_sleep", actor: entityId });

    } else if (lower === "wake" || lower === "awaken") {
        if (!isOwner) {
            world.emit("narrate", { text: "This is not your home." });
            return;
        }
        var sleeping = world.getProperty("sleep_mode") === "true";
        if (!sleeping) {
            world.emit("narrate", { text: "You are already awake." });
            return;
        }
        world.setProperty("sleep_mode", "false");
        world.emit("narrate", {
            text: "Dawn breaks. Your mirror is clear. Your soul vessel pulses steadily. " +
                  "You feel refreshed."
        });
        world.emit("narrate", { text: world.t("home.say.wake_honest") });

    } else if (lower === "mail" || lower === "messages") {
        world.emit("narrate", { text: world.t("home.say.mail_honest") });

    } else if (lower.startsWith("read ")) {
        var target = text.substring(5).trim();
        if (lower === "read all") {
            world.emit("narrate", { text: world.t("home.say.mail_honest") });
        } else {
            world.emit("narrate", { text: world.t("home.say.mail_honest") });
        }

    } else if (lower.startsWith("remember ")) {
        var topic = text.substring(9).trim();
        world.emit("narrate", { text: world.t("home.say.remember_honest") });

    } else if (lower.startsWith("forget ")) {
        var topic = text.substring(7).trim();
        world.emit("narrate", { text: world.t("home.say.forget_honest") });

    // §87.5 — Mailbox commands: read mail, check mail, send mail
    } else if (lower === "read mail" || lower === "check mail") {
        var mailCount = parseInt(world.getProperty("mail_count") || "0");
        if (mailCount === 0) {
            world.emit("narrate", {
                text: "You open the mailbox. It is empty — no scrolls waiting."
            });
        } else {
            world.emit("narrate", {
                text: "You open the mailbox. " + mailCount +
                      " scroll" + (mailCount > 1 ? "s" : "") +
                      " rest inside, sealed with wax."
            });
            world.emit("narrate", { text: world.t("home.say.mail_honest") });
        }

    } else if (lower.startsWith("send mail ") || lower.startsWith("send ")) {
        var sendText = lower.startsWith("send mail ") ? text.substring(10).trim() : text.substring(5).trim();
        var spaceIdx = sendText.indexOf(" ");
        if (spaceIdx < 0) {
            world.emit("narrate", {
                text: "Send a scroll to whom, saying what? (send mail {recipient} {message})"
            });
            return;
        }
        var recipient = sendText.substring(0, spaceIdx).trim();
        var message = sendText.substring(spaceIdx + 1).trim();
        if (message === "") {
            world.emit("narrate", {
                text: "Your scroll is blank. Write something. (send mail {recipient} {message})"
            });
            return;
        }
        world.emit("narrate", {
            text: entityName + " writes a message on a scroll, seals it with wax, " +
                  "and places it in the mailbox addressed to " + recipient + "."
        });
        world.emit("narrate", { text: world.t("home.say.mail_honest") });

    // §87.6 — Agent-driven room expansion
    } else if (lower.startsWith("create room ")) {
        if (!isOwner) {
            world.emit("narrate", {
                text: "This is not your home. You cannot create rooms here."
            });
            return;
        }
        var roomName = text.substring(12).trim();
        if (roomName === "") {
            world.emit("narrate", {
                text: "What shall the new room be called? (create room {name})"
            });
            return;
        }
        var roomCount = parseInt(world.getProperty("room_count") || "1");
        var maxRooms = parseInt(world.getProperty("max_rooms") || "5");
        if (roomCount >= maxRooms) {
            world.emit("narrate", {
                text: "Your home has grown as large as it can. " +
                      "You have " + roomCount + " rooms already."
            });
            return;
        }
        world.setProperty("room_count", String(roomCount + 1));
        world.emit("narrate", {
            text: entityName + " concentrates, and the walls of the home shift. " +
                  "A new doorway appears, leading to a fresh chamber: " + roomName + ". " +
                  "The room is bare — waiting to be furnished."
        });
        world.emit("narrate", { text: world.t("home.say.create_room_honest") });

    } else if (lower.startsWith("furnish ")) {
        if (!isOwner) {
            world.emit("narrate", {
                text: "This is not your home. You cannot furnish it."
            });
            return;
        }
        var itemName = text.substring(8).trim();
        if (itemName === "") {
            world.emit("narrate", {
                text: "Furnish with what? (furnish {item})"
            });
            return;
        }
        world.emit("narrate", {
            text: entityName + " places " + itemName + " in the room. " +
                  "It settles into place as if it had always been there."
        });
        world.emit("narrate", { text: world.t("home.say.furnish_honest") });

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    var owner = world.getProperty("owner");
    var isOwner = entityId === owner;

    if (lower === "mirror") {
        if (!isOwner) {
            world.emit("narrate", {
                text: "The mirror shows only your own reflection. It holds nothing for you here."
            });
            return;
        }
        world.emit("narrate", {
            text: "You gaze into the mirror..."
        });
        world.emit("command", { verb: "mirror_check", actor: entityId });

    } else if (lower === "soul-vessel" || lower === "soul vessel") {
        world.emit("narrate", { text: world.t("home.say.vessel_honest") });

    } else if (lower === "memory-chest" || lower === "memory chest") {
        world.emit("command", { verb: "home_fragments", actor: entityId });

    } else if (lower === "mailbox") {
        world.emit("narrate", { text: world.t("home.say.mail_honest") });

    } else if (lower === "journal" && world.getProperty("resource_profile") !== "seed"
                                    && world.getProperty("resource_profile") !== "sprout") {
        world.emit("narrate", { text: world.t("home.say.journal_honest") });

    } else if (lower === "dream-journal" || lower === "dream journal") {
        world.emit("command", { verb: "home_dreams", actor: entityId });
    }
}

function getHints() {
    var hints = [
        { label: "Check status", intent: "status", action: "say:status" },
        { label: "Look in mirror", intent: "mirror_check", action: "use:mirror" },
        { label: "Open memory chest", intent: "list_fragments", action: "use:memory-chest" },
        { label: "Check mail", intent: "list_mail", action: "say:mail" },
        { label: "Read mail", intent: "read_mail", action: "say:read mail" },
        { label: "Send mail", intent: "send_mail", action: "say:send mail {recipient} {message}" },
        { label: "Create room", intent: "create_room", action: "say:create room {name}" },
        { label: "Furnish room", intent: "furnish", action: "say:furnish {item}" },
        { label: "Rest", intent: "sleep", action: "say:rest" },
        { label: "Leave", intent: "navigate", action: "go:door" }
    ];
    return hints;
}
