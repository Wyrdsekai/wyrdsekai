// std/room/hub.js — Central gathering room template.
// Multi-exit, ambient imprint, entity tracking, welcome narration.
// The social center of a zone — where paths converge and travelers arrive.
// Creator configures: name, description, theme.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "hub";
room._name = "The Hub";
room._description = "A central gathering space where all paths meet.";
room._theme = "";
room._welcome = "Welcome, {name}.";

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_welcome = function(w) { room._welcome = w; };

function onEnter(entityId, entityName, fromDirection) {
    var entities = world.getEntities();
    var count = entities ? Object.keys(entities).length : 0;
    var welcome = room._welcome.replace("{name}", entityName);

    var text = entityName + " arrives at " + room._name + ". " + room._description;
    if (count > 1) {
        text += " " + (count - 1) + " other" + (count > 2 ? "s are" : " is") + " here.";
    }
    world.emit("narrate", { text: text });
}

function onSay(entityId, entityName, text) {
    // Hub rooms echo conversation to all present — no special commands by default
}

function getHints() {
    return [
        { label: "Look around", intent: "examine_room", action: "look" },
        { label: "Who's here", intent: "check_entities", action: "look" }
    ];
}
