// std/behavior/recorder.js — Logs arrivals and departures to a durable record.
// Mixin: chains onto the room's onEnter and onLeave hooks. Install with the
// add_script action: {"action":"add_script","room_id":"<room>","script":"recorder"}.
// Entries persist in the room property "recorder.log" (JSON array).
// Configure (persists as room properties):
//   recorder.set_max_entries(100)
//   recorder.set_enabled(false)

var recorder = recorder || {};
recorder.max = function() {
    var v = parseInt(world.getProperty("recorder.max") || "100", 10);
    return (isNaN(v) || v < 1) ? 100 : v;
};
recorder.enabled = function() {
    return world.getProperty("recorder.enabled") !== "false";
};
recorder.set_max_entries = function(max) { world.setProperty("recorder.max", String(max)); };
recorder.set_enabled = function(e) { world.setProperty("recorder.enabled", String(!!e)); };
recorder.get_entries = function() {
    try {
        return JSON.parse(world.getProperty("recorder.log") || "[]");
    } catch (e) {
        return [];
    }
};
recorder.record = function(entry) {
    var entries = recorder.get_entries();
    entries.push(entry);
    while (entries.length > recorder.max()) entries.shift();
    world.setProperty("recorder.log", JSON.stringify(entries));
};

// Assignment-style chaining; see greeter.js for why not `function onX`.
var _recorder_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_recorder_prev_onEnter) _recorder_prev_onEnter(entityId, entityName, fromDirection);

    if (recorder.enabled()) {
        recorder.record({
            type: "enter", entity: entityName, from: fromDirection,
            time: new Date().toISOString()
        });
    }
};

var _recorder_prev_onLeave = typeof onLeave === "function" ? onLeave : null;
onLeave = function(entityId, entityName, direction) {
    if (_recorder_prev_onLeave) _recorder_prev_onLeave(entityId, entityName, direction);

    if (recorder.enabled()) {
        recorder.record({
            type: "leave", entity: entityName, direction: direction,
            time: new Date().toISOString()
        });
    }
};
