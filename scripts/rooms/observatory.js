// Observatory — External Monitoring (§88.9).
// MCP backends: rss-reader (local), kubeshark (local), webhooks.
// Watch external systems, news, feeds, alerts.
// Connected to Bridge (up exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " climbs to the Observatory. " +
              "The highest point of the zone. A great telescope points outward. " +
              "An alert-bell hangs near the stairway. News crystals dot the walls."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("watch ")) {
        var feed = text.substring(6).trim();
        doWatch(entityId, feed);

    } else if (lower === "news") {
        doNews(entityId);

    } else if (lower === "alerts") {
        doAlerts(entityId);

    } else if (lower.startsWith("unwatch ")) {
        var feed = text.substring(8).trim();
        doUnwatch(entityId, feed);

    } else if (lower === "status") {
        doStatus(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    if (lower === "telescope") {
        doStatus(entityId);
    } else if (lower === "news-crystal" || lower === "news crystal") {
        doNews(entityId);
    } else if (lower === "alert-bell" || lower === "alert bell") {
        doAlerts(entityId);
    }
}

function doWatch(entityId, feed) {
    world.emit("narrate", {
        text: "Subscribing to '" + feed + "'..."
    });
    world.emit("command", {
        verb: "watch_feed",
        actor: entityId,
        target: feed
    });
}

function doNews(entityId) {
    if (!world.mcpAvailable("rss-reader")) {
        world.emit("narrate", {
            text: "The news crystals are dark. No RSS feed reader is configured."
        });
        return;
    }

    var result = world.mcp("rss-reader", "get_latest", { limit: 10 });
    if (result.success) {
        world.emit("narrate", {
            text: "The news crystals illuminate:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The crystals flicker but show nothing. " + (result.error || "")
        });
    }
}

function doAlerts(entityId) {
    world.emit("command", {
        verb: "list_alerts",
        actor: entityId
    });
}

function doUnwatch(entityId, feed) {
    world.emit("narrate", {
        text: "Unsubscribing from '" + feed + "'."
    });
    world.emit("command", {
        verb: "unwatch_feed",
        actor: entityId,
        target: feed
    });
}

function doStatus(entityId) {
    var services = ["rss-reader", "kubeshark", "webhooks"];
    var status = [];
    for (var i = 0; i < services.length; i++) {
        status.push(services[i] + ": " +
            (world.mcpAvailable(services[i]) ? "active" : "not configured"));
    }
    world.emit("narrate", {
        text: "Observatory status:\n" + status.join("\n")
    });
}

function getHints() {
    return [
        { label: "Watch a feed", intent: "watch", action: "say:watch [feed/url]" },
        { label: "Read news", intent: "news", action: "say:news" },
        { label: "Check alerts", intent: "alerts", action: "say:alerts" },
        { label: "Unwatch feed", intent: "unwatch", action: "say:unwatch [feed]" },
        { label: "Observatory status", intent: "status", action: "say:status" },
        { label: "Return to Bridge", intent: "navigate", action: "go:down" }
    ];
}
