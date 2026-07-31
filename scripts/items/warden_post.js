// Gate furnishing — warden post ("warden-post" RoomObject in the std gate
// room template; display name "warden post" → normalized linkage
// "warden_post").
//
// The warden's station beside the gate. Read surfaces: world.ward.list()
// for this room's standing wards, world.audit.security() for recent
// security events. Bare `use warden post` reads the watch AND lists
// commands.
//
// Ward WRITES (granting, revoking) are steward acts done through the ward
// keyring in the steward's Study or the wyrd CLI — the post says so
// honestly. The gate log beside the post keeps the record of passages.
exports.manifest = {
  name: "warden_post",
  version: "1.0.0",
  description: "The warden's station — read this room's standing wards and recent security events.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} leans on the warden post's worn rail, following the warden's gaze down the approach."
  },
  commands: [
    { label: "Read the watch", args: "" },
    { label: "Standing wards", args: "wards" },
    { label: "Recent security events", args: "events" },
    { label: "Post help", args: "help" }
  ]
};

// Room context: on the furnishing path RoomActor passes params.roomId (the
// per-player provider has no room binding, so world.room.id() is empty there).
var _invokeParams = null;
function currentRoomId() {
  var params = _invokeParams;
  var fromParams = params && params.roomId ? String(params.roomId) : "";
  if (fromParams) return fromParams;
  try { var rid = world.room.id(); return rid ? String(rid) : ""; } catch (e) { return ""; }
}

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use warden post           — the watch, at a glance",
    "  use warden post wards     — standing wards on this room",
    "  use warden post events    — recent security events",
    "  use warden post help      — this help",
    "Granting and revoking wards is steward work — the ward keyring in the",
    "steward's Study, or the wyrd CLI. Passages are recorded in the gate log."
  ].join("\n");
}

function renderWards() {
  var wards = null;
  try { wards = world.ward.list(currentRoomId()); } catch (e) { wards = null; }
  if (!wards || wards.length === 0) {
    return "No standing wards are readable from the post — either this gate is "
      + "open by policy, or the ward ledger isn't bound on this surface.";
  }
  var lines = ["Standing wards on this room, " + wards.length + ":"];
  for (var i = 0; i < wards.length; i++) {
    var w = wards[i];
    var line = "  ";
    var wrote = false;
    for (var k in w) {
      if (w[k] === null || typeof w[k] === "object") continue;
      line += (wrote ? ", " : "") + k + "=" + w[k];
      wrote = true;
    }
    lines.push(wrote ? line : "  (an unreadable ward-mark)");
  }
  return lines.join("\n");
}

function renderEvents() {
  var events = null;
  try { events = world.audit.security(10); } catch (e) { events = null; }
  if (!events || events.length === 0) {
    return "The watch has been quiet — no recent security events on this surface.";
  }
  var lines = ["Recent security events, newest first:"];
  for (var i = 0; i < events.length; i++) {
    var ev = events[i];
    var line = "  ";
    var wrote = false;
    for (var k in ev) {
      if (ev[k] === null || typeof ev[k] === "object") continue;
      line += (wrote ? ", " : "") + k + "=" + ev[k];
      wrote = true;
    }
    lines.push(wrote ? line : "  (a smudged entry)");
  }
  return lines.join("\n");
}

function invoke(params) {
  _invokeParams = params || null;
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The warden post reads the gate's watch — wards and events." + usageFooter() };
  }
  if (args === "wards") {
    return { ok: true, text: renderWards() + usageFooter() };
  }
  if (args === "events" || args === "audit") {
    return { ok: true, text: renderEvents() + usageFooter() };
  }
  if (args === "") {
    return {
      ok: true,
      text: "The warden nods from the post. The watch stands.\n\n"
        + renderWards() + "\n\n" + renderEvents() + usageFooter()
    };
  }
  return { ok: true, text: "The warden has no orders concerning '" + args + "'." + usageFooter() };
}
