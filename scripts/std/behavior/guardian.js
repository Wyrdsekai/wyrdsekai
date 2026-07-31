// std/behavior/guardian.js — Monitors for unauthorized access and alerts.
// Mixin: chains onto the room's onEnter hook. Install with the add_script
// action: {"action":"add_script","room_id":"<room>","script":"guardian"}.
// The allowlist persists in the room property "guardian.allowed"
// (comma-separated entity ids); with an EMPTY list the guardian stays quiet.
// Configure (persists as room properties):
//   guardian.allow("entity-id")   guardian.deny("entity-id")
//   guardian.set_alert_message("Intruder!")   guardian.set_enabled(false)

var guardian = guardian || {};
guardian.allowed = function() {
    var raw = world.getProperty("guardian.allowed") || "";
    if (!raw) return [];
    return raw.split(",").map(function(s) { return s.trim(); })
        .filter(function(s) { return s.length > 0; });
};
guardian.alert_message = function() {
    return world.getProperty("guardian.alert_message")
        || "Alert: unauthorized entity detected.";
};
guardian.enabled = function() {
    return world.getProperty("guardian.enabled") !== "false";
};
guardian.allow = function(entityId) {
    var list = guardian.allowed();
    if (list.indexOf(entityId) < 0) list.push(entityId);
    world.setProperty("guardian.allowed", list.join(","));
};
guardian.deny = function(entityId) {
    var list = guardian.allowed().filter(function(id) { return id !== entityId; });
    world.setProperty("guardian.allowed", list.join(","));
};
guardian.set_alert_message = function(msg) { world.setProperty("guardian.alert_message", msg); };
guardian.set_enabled = function(e) { world.setProperty("guardian.enabled", String(!!e)); };

// Assignment-style chaining; see greeter.js for why not `function onX`.
var _guardian_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_guardian_prev_onEnter) _guardian_prev_onEnter(entityId, entityName, fromDirection);

    var allowed = guardian.allowed();
    if (guardian.enabled() && allowed.length > 0 && allowed.indexOf(entityId) < 0) {
        world.emit("narrate", {
            text: guardian.alert_message() + " (" + entityName + ")"
        });
    }
};
