// Ward Room furnishing — grant stone ("hermod-grant-stone" RoomObject,
// display name "grant stone" → normalized linkage "grant_stone").
// Collision-checked: the Study's "privacy ward stone" is the CONSENT
// system (world.grants); this stone is the HERMOD system — the signed
// data-domain grant files a device carries so household errands may be
// routed to it. Different registry, different stone, on purpose.
//
// Reads through world.hermod.grants(); revocation through
// world.hermod.revoke(...) — steward only, enforced provider-side.
// Self-documenting: bare `use grant stone` reads the grants AND lists
// the command set; unknown args render help instead of silence.
exports.manifest = {
  name: "grant_stone",
  version: "1.0.0",
  description: "A rune-cut stone that remembers which kinds of household data may travel to which kinds of device — and lets the steward withdraw that permission.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} rests a hand on the grant stone; the runes rearrange themselves into an accounting."
  },
  commands: [
    { label: "Read the household's data-domain grants", args: "" },
    { label: "Revoke a grant", args: "revoke <grantId or domain-class>" },
    { label: "Grant stone help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use grant stone                       — the data-domain grants this household holds",
    "  use grant stone revoke <id|domain-class> — tombstone one (steward only)",
    "  use grant stone help                  — this help",
    "New grants are a spoken steward act at the node: wyrd hermod-grant <domain> <class> <days>.",
    "Revoking stops NEW distribution; a copy a device already carries stays valid until its own expiry."
  ].join("\n");
}

function renderGrants() {
  var rows;
  try { rows = world.hermod.grants(); } catch (e) { rows = null; }
  if (!rows || rows.length === 0) {
    return "The stone's runes lie dormant — this household has granted no data domains to any device class."
      + usageFooter();
  }
  var lines = ["The grant stone's runes read:"];
  for (var i = 0; i < rows.length; i++) {
    var g = rows[i];
    if (g.grantId) {
      lines.push("  [" + g.status + "] " + g.dataDomain + " -> " + g.deviceClass
        + "  (expires " + g.expiresAt + ", id " + g.grantId + ")");
    } else {
      lines.push("  [" + g.status + "] " + g.file);
    }
  }
  return lines.join("\n") + usageFooter();
}

function revoke(args) {
  var target = args.replace(/^revoke\s+/i, "").trim();
  if (!target) {
    return "Revoke which grant? Name its id or its domain-class (as the stone lists them)." + usageFooter();
  }
  var result;
  try { result = world.hermod.revoke(target); } catch (e) {
    return "The stone shudders: " + e.message;
  }
  if (!result || result.ok !== true) {
    var reason = result && result.error ? result.error : "the stone does not answer";
    return "The runes refuse: " + reason + ".";
  }
  return "The runes for " + target + " dim and sink into the stone. "
    + (result.note ? result.note + "." : "");
}

exports.invoke = function (params) {
  var args = (params && (params.args || params.text) || "").trim();
  if (!args) return { summary: renderGrants() };
  if (/^help$/i.test(args)) return { summary: "The grant stone hums." + usageFooter() };
  if (/^revoke\b/i.test(args)) return { summary: revoke(args) };
  return { summary: "The stone does not know that rune (\"" + args + "\")." + usageFooter() };
};
