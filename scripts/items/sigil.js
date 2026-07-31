// Ward Room furnishing — sigil ("ward-sigil" RoomObject, display name
// "sigil" → normalized linkage "sigil"). Collision-checked: no other room
// or template names an object "sigil".
//
// "A glowing sigil etched into the wall — part of the ward system." The
// ward system it is part of: room-level access grants, read through
// world.ward.list(roomId) and written through world.ward.grant/revoke
// (steward or room-admin only — enforced provider-side). Self-documenting:
// bare `use sigil` reads the wards on the room it is etched into AND lists
// the command set; unknown args render help instead of silence.
exports.manifest = {
  name: "sigil",
  version: "1.0.0",
  description: "A glowing ward-sigil — reads the access grants on this room, and etches or erases them for those with the authority.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} traces the sigil's glowing lines; the ward-light pulses in answer."
  },
  commands: [
    { label: "Read the wards on this room", args: "" },
    { label: "Grant a ward", args: "grant <who> <capability>" },
    { label: "Revoke a ward", args: "revoke <who> <capability>" },
    { label: "Sigil help", args: "help" }
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

var CAPS = "enter/speak/take/drop/use/build/admin";

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use sigil                            — the ward grants on this room",
    "  use sigil grant <who> <capability>   — extend a ward (" + CAPS + ")",
    "  use sigil revoke <who> <capability>  — withdraw one",
    "  use sigil help                       — this help",
    "Grants and revocations take a steward or room admin; the sigil refuses all others."
  ].join("\n");
}

function roomId() {
  try { return currentRoomId(); } catch (e) { return null; }
}

function renderWards() {
  var rid = roomId();
  if (!rid) {
    return "The sigil flickers — it cannot tell which room it is etched into on this surface.";
  }
  var wards = null;
  try { wards = world.ward.list(rid); } catch (e) { wards = null; }
  if (wards === null) {
    return "The sigil's light is steady but silent — the ward ledger isn't bound on this surface.";
  }
  if (wards.length === 0) {
    return "The sigil glows evenly: no explicit ward grants on " + rid
      + ". The room stands under the zone's default protections.";
  }
  var lines = ["The sigil brightens, tracing the wards on " + rid + ":"];
  for (var i = 0; i < wards.length; i++) {
    var w = wards[i];
    lines.push("  " + (w.subject || "?") + " — " + (w.capability || "?")
      + (w.grantedBy ? "  (granted by " + w.grantedBy + ")" : ""));
  }
  return lines.join("\n");
}

function doGrant(rest, revoke) {
  var parts = rest.split(/\s+/).filter(function (p) { return p !== ""; });
  if (parts.length < 2) {
    return "The sigil needs both a name and a capability: use sigil "
      + (revoke ? "revoke" : "grant") + " <who> <" + CAPS + ">.";
  }
  var rid = roomId();
  if (!rid) {
    return "The sigil flickers — it cannot tell which room it is etched into on this surface.";
  }
  var subject = parts[0];
  var capability = parts[1].toLowerCase();
  var res = null;
  try {
    res = revoke
      ? world.ward.revoke(rid, subject, capability)
      : world.ward.grant(rid, subject, capability);
  } catch (e) {
    return "The sigil's light dims — this surface may not carry the authority to change wards ("
      + e.message + ").";
  }
  if (res && res.ok) {
    return revoke
      ? "A line of the sigil fades: " + capability + " withdrawn from " + subject + " on " + rid + "."
      : "A new line burns into the sigil: " + capability + " granted to " + subject + " on " + rid + ".";
  }
  var reason = res && (res.error || res.message) ? String(res.error || res.message) : "refused";
  return "The sigil refuses: " + reason + ".";
}

function invoke(params) {
  _invokeParams = params || null;
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The sigil is the ward system's face: it reads and rewrites who may do what in this room." + usageFooter() };
  }
  if (args.indexOf("grant ") === 0) {
    return { ok: true, text: doGrant(raw.substring(6).trim(), false) + usageFooter() };
  }
  if (args.indexOf("revoke ") === 0) {
    return { ok: true, text: doGrant(raw.substring(7).trim(), true) + usageFooter() };
  }
  if (args === "" || args === "list" || args === "wards") {
    return { ok: true, text: renderWards() + usageFooter() };
  }
  return { ok: true, text: "The sigil does not answer to '" + raw + "'." + usageFooter() };
}
