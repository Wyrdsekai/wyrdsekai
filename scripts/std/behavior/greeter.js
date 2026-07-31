// std/behavior/greeter.js — Narrates entity arrivals with customizable greeting.
// Mixin: chains onto the room's existing onEnter hook. Install with the
// add_script action: {"action":"add_script","room_id":"<room>","script":"greeter"}.
// Configure (persists as room properties):
//   greeter.set_message("Welcome, {name}. The Force is with you.")
//   greeter.set_enabled(false)

var greeter = greeter || {};
greeter.message = function() {
    return world.getProperty("greeter.message") || "Welcome, {name}.";
};
greeter.enabled = function() {
    return world.getProperty("greeter.enabled") !== "false";
};
greeter.set_message = function(msg) { world.setProperty("greeter.message", msg); };
greeter.set_enabled = function(e) { world.setProperty("greeter.enabled", String(!!e)); };

// Chain onto any existing onEnter. Assignment (NOT `function onEnter`) is
// load-bearing: mixins are appended after the room's base script and the whole
// file is evaluated as one program — a hoisted function declaration would
// shadow the base hook from line one and capture ITSELF as "previous".
var _greeter_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_greeter_prev_onEnter) _greeter_prev_onEnter(entityId, entityName, fromDirection);

    if (greeter.enabled()) {
        world.emit("narrate", { text: greeter.message().replace("{name}", entityName) });
    }
};
