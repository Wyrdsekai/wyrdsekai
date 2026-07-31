// std/room/empty.js — Blank canvas room template.
// Minimal room with just enter narration. The foundation for custom rooms.
// Creator configures: name, description, theme.
// Override: any hook (onEnter, onSay, onUse, onLeave, getHints).

var room = room || {};  // config holder; survives only within one evaluation
room._type = "empty";
room._name = "An Empty Room";
room._description = "A bare room with smooth walls and a clean floor. It awaits purpose.";
room._theme = "";

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

function getHints() {
    return [
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
