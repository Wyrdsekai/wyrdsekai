// Study furnishing — invitation scroll ("study-invitation-scroll"
// RoomObject, display name "invitation scroll" → normalized linkage
// "invitation_scroll").
//
// Rolled parchment on a brass peg: pending invites via world.invite.list(),
// minting via create(role[, intendedName]), revocation via revoke(codeOrId).
// Invite codes are join secrets — the provider returns an EMPTY list to
// non-stewards, so an empty scroll notes that it only unrolls for the
// steward. A freshly minted code is rendered prominently: it is what a new
// member types at `wyrd connect`.
//
// TODO: declare invite.create once registered in KNOWN_CAPABILITIES
// TODO: declare invite.revoke once registered in KNOWN_CAPABILITIES
exports.manifest = {
  name: "invitation_scroll",
  version: "1.0.0",
  description: "Rolled parchment holding the household's pending invitations — the steward may mint new codes or burn old ones.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} lifts the invitation scroll from its brass peg and lets the parchment unroll."
  },
  commands: [
    { label: "Read pending invites", args: "" },
    { label: "Mint an invite", args: "create <role> [name]" },
    { label: "Revoke an invite", args: "revoke <code>" },
    { label: "Scroll help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use invitation scroll                       — read the pending invitations",
    "  use invitation scroll create <role> [name]  — mint an invite (steward/member/guest/child)",
    "  use invitation scroll revoke <code>         — burn a pending invitation",
    "  use invitation scroll help                  — this help",
    "A minted code is what the newcomer types at `wyrd connect` to join the household."
  ].join("\n");
}

function renderInvites() {
  var invites = null;
  try { invites = world.invite.list(); } catch (e) { invites = null; }
  if (!invites || invites.length === 0) {
    return "The scroll unrolls blank — no invitations pending. "
      + "(If you are not the steward, know that the parchment only shows its "
      + "writing to the steward's eyes — invite codes are join secrets.)";
  }
  var lines = ["The scroll unrolls; the pending invitations, in fading ink:"];
  for (var i = 0; i < invites.length; i++) {
    var inv = invites[i];
    var status = inv.consumed ? "[consumed]" : (inv.expired ? "[expired]" : "[pending]");
    var line = "  " + status + "  code: " + (inv.code || "?")
      + "  as " + (inv.role || "?");
    if (inv.intendedName) line += "  for “" + inv.intendedName + "”";
    lines.push(line);
    if (inv.createdAt) lines.push("      written " + inv.createdAt
      + (inv.expiresAt ? ", fades " + inv.expiresAt : ""));
  }
  return lines.join("\n");
}

function doCreate(rest) {
  var parts = rest.split(/\s+/);
  var role = (parts[0] || "").toLowerCase();
  if (!role) {
    return "Mint an invitation for whom? create <role> [name] — roles are steward, member, guest, child."
      + usageFooter();
  }
  var name = parts.length > 1 ? parts.slice(1).join(" ") : null;
  var res = null;
  try {
    res = name ? world.invite.create(role, name) : world.invite.create(role);
  } catch (e) { res = null; }
  if (!res) {
    return "The quill scratches nothing — the invitation service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    var lines = ["Fresh ink dries on the parchment — an invitation is minted:"];
    lines.push("");
    lines.push("      ✦  " + (res.code || "?") + "  ✦");
    lines.push("");
    lines.push("  Role: " + (res.role || role)
      + (res.intendedName ? "  for “" + res.intendedName + "”" : ""));
    if (res.expiresAt) lines.push("  The ink fades " + res.expiresAt + ".");
    lines.push("  Give this code to the newcomer — it is what they type at `wyrd connect`.");
    return lines.join("\n");
  }
  return "The parchment refuses the ink: " + (res.error || "the invitation was refused") + "."
    + usageFooter();
}

function doRevoke(rest) {
  var code = rest.split(/\s+/)[0];
  if (!code) {
    return "Burn which invitation? revoke <code>." + usageFooter();
  }
  var res = null;
  try { res = world.invite.revoke(code); } catch (e) { res = null; }
  if (!res) {
    return "The candle gutters — the invitation service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "The invitation curls and blackens at the candle's flame — that code opens no door now.";
  }
  return "The parchment will not burn: " + (res.error || "the revocation was refused") + "."
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
      text: "The invitation scroll holds the household's pending invitations — "
        + "the steward may mint new codes or burn old ones." + usageFooter()
    };
  }
  if (verb === "create") {
    return { ok: true, text: doCreate(rest) };
  }
  if (verb === "revoke") {
    return { ok: true, text: doRevoke(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The scroll doesn't answer to '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderInvites() + usageFooter() };
}
