// std/behavior/narrator.js — Generates ambient descriptions on a timer (§31).
// Mixin: schedules the "narrator" room timer on activation/entry and narrates
// a rotating description each tick. Install with the add_script action:
// {"action":"add_script","room_id":"<room>","script":"narrator"}.
// Configure (persists as room properties):
//   narrator.set_interval(300)
//   narrator.add_description("The wind howls through the rafters...")
//   narrator.set_enabled(false)

var narrator = narrator || {};
// Default ambient set (#31 item 5): the mixin used to ship with ZERO
// descriptions and no surface seeded any, so an installed narrator was
// permanently silent. These are deliberately room-agnostic — light, air,
// sound, time — so install→narrates everywhere; add_description() lines
// take over as soon as the room curates its own.
narrator.DEFAULTS = [
    "The light shifts almost imperceptibly, the way it does when time is passing quietly.",
    "A faint draft stirs the air, carrying the room's own particular stillness with it.",
    "Somewhere just at the edge of hearing, the space settles — a soft creak, then quiet again.",
    "Dust motes drift through the air, unhurried, catching what light there is.",
    "The quiet here has a texture to it, the kind that makes small sounds feel deliberate.",
    "A moment passes in which nothing moves, and the room seems to breathe once, slowly.",
    "The shadows lean a little differently than before, as if the hour nudged them.",
];
narrator.interval = function() {
    var v = parseInt(world.getProperty("narrator.interval") || "300", 10);
    return (isNaN(v) || v < 1) ? 300 : v;
};
narrator.descriptions = function() {
    var raw = world.getProperty("narrator.descriptions") || "";
    if (!raw) return narrator.DEFAULTS;
    var curated = raw.split("|").filter(function(s) { return s && s.trim().length > 0; });
    return curated.length > 0 ? curated : narrator.DEFAULTS;
};
narrator.enabled = function() {
    return world.getProperty("narrator.enabled") !== "false";
};
narrator.set_interval = function(secs) {
    world.setProperty("narrator.interval", String(secs));
};
narrator.add_description = function(desc) {
    var raw = world.getProperty("narrator.descriptions") || "";
    world.setProperty("narrator.descriptions", raw ? raw + "|" + desc : desc);
};
narrator.set_enabled = function(e) { world.setProperty("narrator.enabled", String(!!e)); };

function _narrator_schedule() {
    world.scheduleTimer("narrator", narrator.interval(), "onTimer");
}

// Schedule on room activation (recovery) AND on entry — a mixin installed on
// a live room would otherwise wait for a restart before its timer exists.
// Assignment-style chaining; see greeter.js for why not `function onX`.
var _narrator_prev_onActivate = typeof onActivate === "function" ? onActivate : null;
onActivate = function() {
    if (_narrator_prev_onActivate) _narrator_prev_onActivate();
    _narrator_schedule();
};

var _narrator_prev_onEnter = typeof onEnter === "function" ? onEnter : null;
onEnter = function(entityId, entityName, fromDirection) {
    if (_narrator_prev_onEnter) _narrator_prev_onEnter(entityId, entityName, fromDirection);
    _narrator_schedule();
};

// Timer hooks receive (timerId) — the engine passes the id, nothing else.
var _narrator_prev_onTimer = typeof onTimer === "function" ? onTimer : null;
onTimer = function(timerId) {
    if (_narrator_prev_onTimer) _narrator_prev_onTimer(timerId);

    if (timerId !== "narrator" || !narrator.enabled()) return;
    var descs = narrator.descriptions();
    if (descs.length === 0) return;
    // Time-derived rotation: script state does not survive between
    // invocations, so the index comes from the clock, not a counter.
    var idx = Math.floor(Date.now() / (narrator.interval() * 1000)) % descs.length;
    world.emit("narrate", { text: descs[idx] });
};
