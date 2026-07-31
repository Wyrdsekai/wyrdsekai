// std/behavior/announcer.js — Announces arrivals to everyone attached to the room.
// Mixin: chains onto the room's existing onEnter hook. Install with the
// add_script action: {"action":"add_script","room_id":"<room>","script":"announcer"}.
// The broadcast reaches every room subscriber — local surfaces and, through
// Between replication, remote ones.
// Configure (persists as room properties):
//   announcer.set_format("{name} has arrived at {room}.")
//   announcer.set_enabled(false)

var announcer = announcer || {};
announcer.format = function() {
    return world.getProperty("announcer.format") || "{name} has arrived at {room}.";
};
announcer.enabled = function() {
    return world.getProperty("announcer.enabled") !== "false";
};
announcer.set_format = function(fmt) { world.setProperty("announcer.format", fmt); };
announcer.set_enabled = function(e) { world.setProperty("announcer.enabled", String(!!e)); };

// Assignment-style chaining; see greeter.js for why not `function onX`.
var _announcer_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_announcer_prev_onEnter) _announcer_prev_onEnter(entityId, entityName, fromDirection);

    if (announcer.enabled()) {
        var msg = announcer.format()
            .replace("{name}", entityName)
            .replace("{room}", world.getRoomName() || "this room");
        world.emit("broadcast", { text: msg });
    }
};
