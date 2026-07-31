// Herald's Hall — Communication (§88.2).
// MCP backends: slack, discord, email/IMAP, matrix.
// Send and receive messages across external platforms.
// Connected to Docks (east exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters the Herald's Hall. " +
              "Message crystals line the walls, softly pulsing with unread dispatches."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("send ")) {
        doSend(entityId, entityName, text.substring(5).trim());

    } else if (lower.startsWith("read ")) {
        var platform = text.substring(5).trim();
        doRead(entityId, platform);

    } else if (lower === "channels") {
        doListChannels(entityId);

    } else if (lower.startsWith("watch ")) {
        var args = text.substring(6).trim();
        doWatch(entityId, args);

    } else if (lower === "status") {
        doStatus(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower === "herald-desk" || lower === "herald desk") {
        doStatus(entityId);
    } else if (lower === "herald-horn" || lower === "herald horn") {
        world.emit("narrate", {
            text: "The herald horn can broadcast to all connected channels. " +
                  "Use with care."
        });
    }
}

function doSend(entityId, entityName, args) {
    // Parse: "platform channel/person message"
    var parts = args.split(/\s+/);
    if (parts.length < 3) {
        world.emit("narrate", {
            text: "The herald needs: platform, recipient, and message. " +
                  "Example: 'send slack #general Hello everyone'"
        });
        return;
    }

    var platform = parts[0].toLowerCase();
    var recipient = parts[1];
    var message = parts.slice(2).join(" ");

    if (!world.mcpAvailable(platform)) {
        world.emit("narrate", {
            text: "No herald speaks the tongue of '" + platform + "'. " +
                  "That communication service is not configured."
        });
        return;
    }

    // New recipient check — emit command for Ward Room approval
    world.emit("narrate", {
        text: "The herald prepares your message for " + recipient + " via " + platform + "..."
    });

    var result = world.mcp(platform, "send_message", {
        channel: recipient,
        text: message
    });

    if (result.success) {
        world.emit("narrate", {
            text: "The herald dispatches your message. A crystal dims on the wall."
        });
    } else {
        world.emit("narrate", {
            text: "The herald could not deliver your message. " + (result.error || "")
        });
    }
}

function doRead(entityId, platform) {
    var lower = platform.toLowerCase();

    if (!world.mcpAvailable(lower)) {
        world.emit("narrate", {
            text: "No connection to '" + platform + "' is available."
        });
        return;
    }

    var result = world.mcp(lower, "read_messages", { limit: 10 });

    if (result.success) {
        world.emit("narrate", {
            text: "The herald reads aloud from " + platform + ":\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The herald cannot access " + platform + ". " + (result.error || "")
        });
    }
}

function doListChannels(entityId) {
    var platforms = ["slack", "discord", "email", "matrix"];
    var available = [];

    for (var i = 0; i < platforms.length; i++) {
        if (world.mcpAvailable(platforms[i])) {
            available.push(platforms[i]);
        }
    }

    if (available.length === 0) {
        world.emit("narrate", {
            text: "No communication services are connected. " +
                  "The herald's desk is bare."
        });
        return;
    }

    world.emit("narrate", {
        text: "The herald can reach these platforms: " + available.join(", ") + ".\n" +
              "Say 'read [platform]' to see messages."
    });
}

function doWatch(entityId, args) {
    var parts = args.split(/\s+/);
    if (parts.length < 2) {
        world.emit("narrate", {
            text: "Specify platform and channel. Example: 'watch slack #general'"
        });
        return;
    }

    world.emit("narrate", {
        text: "The herald will deliver messages from " + parts[1] +
              " (" + parts[0] + ") to your mailbox."
    });
    world.emit("command", {
        verb: "watch_channel",
        actor: entityId,
        target: parts[0] + ":" + parts[1]
    });
}

function doStatus(entityId) {
    var platforms = ["slack", "discord", "email", "matrix"];
    var status = [];

    for (var i = 0; i < platforms.length; i++) {
        var avail = world.mcpAvailable(platforms[i]);
        status.push(platforms[i] + ": " + (avail ? "connected" : "not configured"));
    }

    world.emit("narrate", {
        text: "Herald's Hall status:\n" + status.join("\n")
    });
}

function getHints() {
    return [
        { label: "Send a message", intent: "send_message", action: "say:send [platform] [recipient] [message]" },
        { label: "Read messages", intent: "read_messages", action: "say:read [platform]" },
        { label: "List channels", intent: "list_channels", action: "say:channels" },
        { label: "Watch a channel", intent: "watch", action: "say:watch [platform] [channel]" },
        { label: "Herald status", intent: "status", action: "say:status" },
        { label: "Go to Docks", intent: "navigate_west", action: "go:west" }
    ];
}
