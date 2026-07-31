// std/room/gate.js — Access control and entry point template.
// Warden checks, key validation, quarantine.
// Creator configures: name, description, theme, security level.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "gate";
room._name = "The Gate";
room._description = "A fortified entrance. The warden's post stands beside heavy doors.";
room._theme = "";
room._security = "standard"; // "open", "standard", "restricted", "sealed"

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_security = function(s) { room._security = s; };

// The gate log is REAL: every passage through onEnter is appended to the
// room property "gate.log" (world.setProperty persists across script
// re-evaluation), and `use gate log` / say 'log' reads it back. The warden
// post is a scripted item (scripts/items/warden_post.js — ward + audit
// reads); RoomActor resolves it by normalized display name, so its
// invoke() pre-empts onUse here.

function loadGateLog() {
    var raw = world.getProperty("gate.log");
    return raw ? JSON.parse(raw) : [];
}

function saveGateLog(entries) {
    world.setProperty("gate.log", JSON.stringify(entries));
}

function onEnter(entityId, entityName, fromDirection) {
    var secText = "";
    if (room._security === "restricted") secText = " The wards pulse with scrutiny.";
    else if (room._security === "sealed") secText = " The doors are sealed. Only key-holders pass.";

    // Record the passage — the gate log's whole purpose.
    try {
        var entries = loadGateLog();
        entries.push({
            name: entityName,
            from: fromDirection || "?",
            at: new Date().toISOString()
        });
        if (entries.length > 50) entries = entries.slice(entries.length - 50);
        saveGateLog(entries);
    } catch (e) {
        // A torn page must not block the door.
    }

    world.emit("narrate", {
        text: entityName + " approaches " + room._name + ". " + room._description + secText
    });
}

function renderGateLog() {
    var entries = [];
    try { entries = loadGateLog(); } catch (e) { entries = []; }
    if (entries.length === 0) {
        return "The gate log lies open at a blank page — no passages recorded yet.";
    }
    var lines = ["The gate log, most recent passages last (" + entries.length + " on record):"];
    var start = Math.max(0, entries.length - 10);
    for (var i = start; i < entries.length; i++) {
        var e = entries[i];
        lines.push("  " + (e.at || "?") + "  " + (e.name || "?")
            + (e.from && e.from !== "?" ? "  (from " + e.from + ")" : ""));
    }
    lines.push("");
    lines.push("Commands: use gate log — this reading | say 'log' — the same | say 'security' — the gate's level");
    return lines.join("\n");
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "status" || lower === "security") {
        world.emit("narrate", {
            text: "Security level: " + room._security + ". The warden watches quietly.\n" +
                  "For the standing wards and recent events: 'use warden post'. " +
                  "For who has passed: 'use gate log'."
        });
    } else if (lower === "log" || lower === "gate log" || lower === "passages") {
        world.emit("narrate", { text: renderGateLog() });
    }
}

function onUse(entityId, objectName, target) {
    var name = (objectName || "").toLowerCase();
    // "warden post" resolves to its scripted item and never lands here.
    if (name.indexOf("log") >= 0 || name === "ledger") {
        world.emit("narrate", { text: renderGateLog() });
    }
}

function getHints() {
    return [
        { label: "Check security", intent: "check_security", action: "say:security" },
        { label: "Read the gate log", intent: "read_log", action: "say:log" },
        { label: "Consult the warden post", intent: "warden", action: "use:warden post" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
