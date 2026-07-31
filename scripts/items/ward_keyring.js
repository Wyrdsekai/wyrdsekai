// Study furnishing — ward keyring ("study-ward-keyring" RoomObject,
// display name "ward keyring" → normalized linkage "ward_keyring").
//
// A heavy iron ring of warded keys: room wards via world.ward.list(roomId),
// grant(roomId, subject, capability), revoke(roomId, subject, capability).
// Capabilities are enter / speak / take / drop / use / build / admin. The
// provider enforces steward/admin checks itself — the keyring renders the
// service's refusal honestly instead of pre-judging the caller.
//
// TODO: declare ward.grant once registered in KNOWN_CAPABILITIES
// TODO: declare ward.revoke once registered in KNOWN_CAPABILITIES
exports.manifest = {
  name: "ward_keyring",
  version: "1.0.0",
  description: "Heavy iron ring of warded keys — see which wards guard a room, cut new keys, or melt old ones down.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} lifts the ward keyring; the iron keys clink and settle, each one humming faintly with its ward."
  },
  commands: [
    { label: "About the keyring", args: "" },
    { label: "List a room's wards", args: "list <roomId>" },
    { label: "Cut a key", args: "grant <roomId> <subject> <capability>" },
    { label: "Melt a key", args: "revoke <roomId> <subject> <capability>" },
    { label: "Keyring help", args: "help" }
  ]
};

var CAPABILITIES = ["enter", "speak", "take", "drop", "use", "build", "admin"];

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use ward keyring                                        — about the keyring",
    "  use ward keyring list <roomId>                          — the wards on a room",
    "  use ward keyring grant <roomId> <subject> <capability>  — cut a new key",
    "  use ward keyring revoke <roomId> <subject> <capability> — melt a key down",
    "  use ward keyring help                                   — this help",
    "Capabilities: " + CAPABILITIES.join(", ") + ".",
    "The same wards answer at the terminal: `wyrd wards list|add|remove` (W4)."
  ].join("\n");
}

// The keyring's terminal twin (wyrd wards …) — narrated whenever a
// modification can't happen from this surface, so the steward always
// leaves knowing the exact command that WILL do it.
function cliHint(action, roomId, subject, capability) {
  return "\nFrom the terminal, the same cut: wyrd wards " + action + " "
    + roomId + " " + subject + " " + capability
    + "  (after `wyrd login`).";
}

function doList(rest) {
  var roomId = rest.split(/\s+/)[0];
  if (!roomId) {
    return "List the wards of which room? list <roomId>." + usageFooter();
  }
  var wards = null;
  try { wards = world.ward.list(roomId); } catch (e) { wards = null; }
  if (!wards || wards.length === 0) {
    return "No key on the ring answers to '" + roomId + "' — the room carries no wards, "
      + "stands open to the household, or this surface isn't bound to your Home."
      + usageFooter();
  }
  var lines = ["The keys stir; the wards upon " + roomId + ":"];
  for (var i = 0; i < wards.length; i++) {
    var w = wards[i];
    var line = "  🗝  " + (w.capability || "?") + "  →  " + (w.subject || "?");
    if (w.grantedBy) line += "  (cut by " + w.grantedBy + ")";
    lines.push(line);
  }
  lines.push("");
  lines.push(wards.length + " ward" + (wards.length === 1 ? "" : "s") + " on this room.");
  return lines.join("\n");
}

function doGrant(rest) {
  var parts = rest.split(/\s+/);
  if (parts.length < 3) {
    return "Cutting a key takes three things: grant <roomId> <subject> <capability>."
      + usageFooter();
  }
  var roomId = parts[0], subject = parts[1], capability = parts[2].toLowerCase();
  var res = null;
  try { res = world.ward.grant(roomId, subject, capability); } catch (e) { res = null; }
  if (!res || res.ok === false && res.error && res.error.code === "adapter_unavailable") {
    return "The iron stays cold — the ward service isn't reachable from this surface."
      + cliHint("add", roomId, subject, capability)
      + usageFooter();
  }
  if (res.ok) {
    return "A new key is cut and slid onto the ring: " + subject + " may now "
      + capability + " in " + roomId + "."
      + (res.created === false ? " (The ward already existed — the key merely gleams anew.)" : "");
  }
  return "The iron refuses the cut: " + (res.error || "the grant was refused") + "."
    + cliHint("add", roomId, subject, capability)
    + usageFooter();
}

function doRevoke(rest) {
  var parts = rest.split(/\s+/);
  if (parts.length < 3) {
    return "Melting a key takes three things: revoke <roomId> <subject> <capability>."
      + usageFooter();
  }
  var roomId = parts[0], subject = parts[1], capability = parts[2].toLowerCase();
  var res = null;
  try { res = world.ward.revoke(roomId, subject, capability); } catch (e) { res = null; }
  if (!res || res.ok === false && res.error && res.error.code === "adapter_unavailable") {
    return "The forge stays cold — the ward service isn't reachable from this surface."
      + cliHint("remove", roomId, subject, capability)
      + usageFooter();
  }
  if (res.ok) {
    return "The key is melted down; " + subject + " no longer holds "
      + capability + " in " + roomId + ".";
  }
  return "The key will not melt: " + (res.error || "the revocation was refused") + "."
    + cliHint("remove", roomId, subject, capability)
    + usageFooter();
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The ward keyring governs who may do what in a warded room. "
        + "Each key is a ward: a subject, a room, and one of "
        + CAPABILITIES.join(" / ") + "." + usageFooter()
    };
  }
  if (verb === "list") {
    return { ok: true, text: doList(rest) };
  }
  if (verb === "grant") {
    return { ok: true, text: doGrant(rest) };
  }
  if (verb === "revoke") {
    return { ok: true, text: doRevoke(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "No key on the ring answers to '" + raw + "'." + usageFooter() };
  }
  return {
    ok: true,
    text: "The keyring hangs heavy with warded keys — each one a room's permission, "
      + "cut for a single subject. Name a room to see its wards: list <roomId>."
      + usageFooter()
  };
}
