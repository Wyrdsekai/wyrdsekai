// Scriptorium — Productivity & Documents (§88.5).
// MCP backends: google-workspace, caldav, filesystem, notion.
// Calendar, email, documents, spreadsheets.
// Connected to Library (east exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters the Scriptorium. " +
              "A writing desk faces the window. Scroll racks line the walls. " +
              "A day-crystal on the desk shows today's schedule."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("calendar ")) {
        var range = text.substring(9).trim();
        doCalendar(entityId, range);

    } else if (lower.startsWith("schedule ")) {
        var details = text.substring(9).trim();
        doSchedule(entityId, details);

    } else if (lower.startsWith("draft ")) {
        var details = text.substring(6).trim();
        doDraft(entityId, details);

    } else if (lower.startsWith("find ")) {
        var query = text.substring(5).trim();
        doFind(entityId, query);

    } else if (lower.startsWith("notes ")) {
        var topic = text.substring(6).trim();
        doNotes(entityId, topic);

    } else if (lower === "status") {
        doStatus(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    if (lower === "writing-desk" || lower === "writing desk") {
        world.emit("narrate", {
            text: "The desk is ready. Say 'draft [type] [topic]' to create a document."
        });
    } else if (lower === "day-crystal" || lower === "day crystal") {
        doCalendar(entityId, "today");
    } else if (lower === "scroll-rack" || lower === "scroll rack") {
        world.emit("narrate", {
            text: "Say 'find [query]' to search through documents."
        });
    }
}

function doCalendar(entityId, range) {
    var service = resolveCalendarService();
    if (!service) {
        world.emit("narrate", {
            text: "The day-crystal is dark. No calendar service is configured."
        });
        return;
    }

    var result = world.mcp(service, "get_events", { range: range });
    if (result.success) {
        world.emit("narrate", {
            text: "The day-crystal illuminates:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The day-crystal flickers. " + (result.error || "")
        });
    }
}

function doSchedule(entityId, details) {
    var service = resolveCalendarService();
    if (!service) {
        world.emit("narrate", {
            text: "No calendar service available to schedule events."
        });
        return;
    }

    var result = world.mcp(service, "create_event", { description: details });
    if (result.success) {
        world.emit("narrate", {
            text: "Event inscribed in the day-crystal."
        });
    } else {
        world.emit("narrate", {
            text: "The event could not be scheduled. " + (result.error || "")
        });
    }
}

function doDraft(entityId, details) {
    var service = resolveDocService();
    if (!service) {
        world.emit("narrate", {
            text: "The writing desk has no ink. No document service is available."
        });
        return;
    }

    var result = world.mcp(service, "create_document", { content: details });
    if (result.success) {
        world.emit("narrate", {
            text: "A new scroll takes shape on the desk:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The draft could not be created. " + (result.error || "")
        });
    }
}

function doFind(entityId, query) {
    var service = resolveDocService();
    if (!service) {
        world.emit("narrate", {
            text: "The scroll rack is sealed. No document service available."
        });
        return;
    }

    var result = world.mcp(service, "search", { query: query });
    if (result.success) {
        world.emit("narrate", {
            text: "The scroll rack reveals:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "No matching scrolls found. " + (result.error || "")
        });
    }
}

function doNotes(entityId, topic) {
    var service = resolveDocService();
    if (!service) {
        world.emit("narrate", {
            text: "No note-taking service available."
        });
        return;
    }

    var result = world.mcp(service, "get_notes", { topic: topic });
    if (result.success) {
        world.emit("narrate", {
            text: "Notes on '" + topic + "':\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "No notes found for '" + topic + "'. " + (result.error || "")
        });
    }
}

function doStatus(entityId) {
    var services = ["google-workspace", "caldav", "filesystem", "notion"];
    var status = [];
    for (var i = 0; i < services.length; i++) {
        status.push(services[i] + ": " +
            (world.mcpAvailable(services[i]) ? "connected" : "not configured"));
    }
    world.emit("narrate", { text: "Scriptorium services:\n" + status.join("\n") });
}

function resolveCalendarService() {
    if (world.mcpAvailable("google-workspace")) return "google-workspace";
    if (world.mcpAvailable("caldav")) return "caldav";
    return null;
}

function resolveDocService() {
    if (world.mcpAvailable("google-workspace")) return "google-workspace";
    if (world.mcpAvailable("notion")) return "notion";
    if (world.mcpAvailable("filesystem")) return "filesystem";
    return null;
}

function getHints() {
    return [
        { label: "Today's calendar", intent: "calendar", action: "say:calendar today" },
        { label: "Schedule event", intent: "schedule", action: "say:schedule [event details]" },
        { label: "Draft document", intent: "draft", action: "say:draft [type] [topic]" },
        { label: "Search documents", intent: "find", action: "say:find [query]" },
        { label: "Return to Library", intent: "navigate", action: "go:west" }
    ];
}
