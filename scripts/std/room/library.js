// std/room/library.js — Knowledge access template.
// Knowledge search, pack browsing, reading areas.
// Creator configures: name, description, theme, collection focus.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "library";
room._name = "Library";
room._description = "Shelves of volumes reach toward the ceiling. The air smells of old paper and possibility.";
room._theme = "";
room._focus = ""; // optional topic focus: "science", "history", "mythology", etc.

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_focus = function(f) { room._focus = f; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("search ")) {
        var query = text.substring(7).trim();
        var results = world.searchKnowledge(query, 5);
        if (results && results.length > 0) {
            var lines = [];
            for (var i = 0; i < results.length; i++) {
                lines.push("  " + (i + 1) + ". " + (results[i].title || results[i].id));
            }
            world.emit("narrate", {
                text: "The catalog opens, finding matches for '" + query + "':\n" + lines.join("\n")
            });
        } else {
            world.emit("narrate", {
                text: "The catalog finds nothing for '" + query + "'."
            });
        }
    }
}

function onUse(entityId, objectName, target) {
    var name = (objectName || "").toLowerCase();
    if (name === "card catalog" || name === "catalog" || name === "card-catalog" || name === "library-catalog") {
        world.emit("narrate", {
            text: "The card catalog stands ready. Say 'search <query>' to find knowledge."
        });
    }
}

function getHints() {
    return [
        { label: "Search", intent: "search_knowledge", action: "say:search" },
        { label: "Use catalog", intent: "use_catalog", action: "use:catalog" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
