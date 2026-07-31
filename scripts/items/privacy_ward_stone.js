// Study furnishing — privacy ward stone ("study-privacy-ward-stone"
// RoomObject, display name "privacy ward stone" → normalized linkage
// "privacy_ward_stone").
//
// The description promises: "controls what personal data is shared…
// review your consent grants." The stone renders three faces of consent
// via the same surfaces the Home board and mailbox read:
//   world.grants.issued()          — what you have granted to others
//   world.grants.held()            — what others have granted over you
//   world.grants.pendingRequests() — requests awaiting your decision
//
// CHANGING consent isn't done through the stone yet — granting and
// revoking flow through your companion or the steward tools for now,
// and the stone says so honestly instead of pretending.
exports.manifest = {
  name: "privacy_ward_stone",
  version: "1.0.0",
  description: "A cool ward stone showing every consent that touches you — grants given, grants held over you, requests waiting.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The ward stone cools under {actor}'s touch, faint runes surfacing — every consent it guards."
  },
  commands: [
    { label: "Review your consents", args: "" },
    { label: "Ward stone help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use privacy ward stone        — review every consent that touches you",
    "  use privacy ward stone help   — this help",
    "Changing consent is done by granting or revoking through your companion",
    "or the steward tools for now — the stone only shows you the truth of it."
  ].join("\n");
}

function grantLine(g, arrow, other) {
  var status = g.active ? "[active]" : (g.revokedAt ? "[revoked]" : "[expired]");
  var line = "  " + status + "  " + (g.capability || "?") + "  " + (g.resource || "?")
    + "  " + arrow + "  " + (other || "?");
  if (g.expiresAt) line += "  (until " + g.expiresAt + ")";
  return line;
}

function renderWard() {
  var issued = null, held = null, pending = null;
  try { issued = world.grants.issued(); } catch (e) { issued = null; }
  try { held = world.grants.held(); } catch (e) { held = null; }
  try { pending = world.grants.pendingRequests(); } catch (e) { pending = null; }

  var hasIssued = issued && issued.length > 0;
  var hasHeld = held && held.length > 0;
  var hasPending = pending && pending.length > 0;

  if (!hasIssued && !hasHeld && !hasPending) {
    return "The ward stone rests dark and smooth — no consents recorded that touch you, "
      + "or this surface isn't bound to your Home.";
  }

  var lines = ["Runes surface on the ward stone — every consent that touches you:"];

  if (hasPending) {
    lines.push("");
    lines.push("Glowing warm — " + pending.length + " request"
      + (pending.length === 1 ? "" : "s") + " awaiting your decision:");
    for (var i = 0; i < pending.length; i++) {
      var p = pending[i];
      var line = "  ✉  " + (p.requester || "?")
        + "  →  " + (p.capability || "?") + " on " + (p.resource || "?");
      if (p.reason) line += "  (\"" + p.reason + "\")";
      lines.push(line);
      lines.push("     id: " + p.id);
    }
    lines.push("  Respond with: approve <id>  |  deny <id>");
  }

  if (hasIssued) {
    lines.push("");
    lines.push("Etched outward — what you have granted to others:");
    for (var j = 0; j < issued.length; j++) {
      lines.push(grantLine(issued[j], "→", issued[j].subject));
    }
  } else {
    lines.push("");
    lines.push("Etched outward — nothing. You have granted no one anything.");
  }

  if (hasHeld) {
    lines.push("");
    lines.push("Etched inward — what others have granted over you:");
    for (var k = 0; k < held.length; k++) {
      lines.push(grantLine(held[k], "←", held[k].issuer));
    }
  } else {
    lines.push("");
    lines.push("Etched inward — nothing. No one holds a grant over you.");
  }

  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The privacy ward stone shows what personal data is shared and with whom — "
        + "the consents you've given, the consents held over you, and any requests "
        + "waiting for your word." + usageFooter()
    };
  }
  if (args !== "") {
    return { ok: true, text: "The ward stone doesn't answer to '" + args + "'." + usageFooter() };
  }
  return { ok: true, text: renderWard() + usageFooter() };
}
