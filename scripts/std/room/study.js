// std/room/study.js — Private quarters template.
// Journal, desk, shelves, dashboard. Per-player. Ward-protected.
// Creator configures: name, owner, theme.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "study";
room._name = "Private Study";
room._description = "A quiet room with a desk, journal, and shelves. Your private space.";
room._theme = "";
room._owner = "";

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_owner = function(o) { room._owner = o; };

function onEnter(entityId, entityName, fromDirection) {
    var text = entityName + " enters " + room._name + ". " + room._description;
    world.emit("narrate", { text: text });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("journal ")) {
        var entry = text.substring(8).trim();
        world.emit("narrate", {
            text: "*" + entityName + " writes in the journal.* " + entry
        });
    }
}

function onUse(entityId, objectName, target) {
    var name = (objectName || "").toLowerCase();
    if (name === "desk" || name === "study-desk") {
        world.emit("narrate", {
            text: "The desk holds a schedule board and correspondence tray."
        });
    } else if (name === "journal" || name === "study-journal") {
        world.emit("narrate", {
            text: "A leather-bound journal. Write with: journal <your thoughts>"
        });
    } else if (name === "shelves" || name === "study-shelves") {
        world.emit("narrate", {
            text: "Document shelves along one wall — personal collections and research."
        });
    }
}

function getHints() {
    return [
        { label: "Look around", intent: "examine_room", action: "look" },
        { label: "Use desk", intent: "use_desk", action: "use:desk" },
        { label: "Use journal", intent: "use_journal", action: "use:journal" },
        { label: "Use shelves", intent: "use_shelves", action: "use:shelves" }
    ];
}
