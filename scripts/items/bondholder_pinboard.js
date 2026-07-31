// bondholder-floor Study pinboard.
//
// Surfaces the substrate's view of a single (companion, bondholder) pair
// for the bondholder themselves: the bond state, repair mode, posture,
// repair-ledger counts, attendant-session frequency, and any active
// protection flag. Spec §7.1.5 — the structured view, not session
// contents.
//
// Verbs:
//   use bondholder-pinboard <other-did>          — render summary line
//   use bondholder-pinboard <other-did> details  — render the full view
//
// Host bridge surfaces consumed (wired via ItemWorldApi):
//   world.substrate.bondholderFloor(agentDid, otherDid)  — Tier 1, read-only
//
// If the substrate bridge isn't wired the script degrades to a stub so
// the item remains inspectable.
exports.manifest = {
  name: "bondholder_pinboard",
  version: "1.0.0",
  description: "Substrate view of one (companion, bondholder) bond — repair, posture, flags.",
  author: "did:wyrd:system",
  capabilities: ["substrate.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The pinboard rustles softly — fresh notes settle, older ones lift a moment in the draft."
  },
  // Items-as-tools contract — the pinboard needs a bondholder DID as its
  // argument (`use bondholder-pinboard <did>` / `<did> details`), which a
  // fixed menu args string cannot supply. The bare entry renders usage.
  commands: [
    { label: "How to surface a bond (usage)", args: "" }
  ]
};

function invoke(params) {
  var args = (params && params.args) || (params && params.text) || "";
  var agentDid = (params && params.agentDid) || (params && params.targetDid) || null;
  if (!agentDid) {
    return {
      ok: false,
      error: "no_agent",
      message: "This pinboard needs a companion to read from."
    };
  }

  var tokens = String(args).trim().split(/\s+/).filter(function (t) { return t.length > 0; });
  if (tokens.length === 0) {
    return {
      ok: false,
      error: "no_other",
      message: "Tell me whose bond to surface: `use bondholder-pinboard <did>`"
    };
  }

  var otherDid = tokens[0];
  var detailMode = tokens.length > 1 && /^(detail|details|full|expand)$/i.test(tokens[1]);

  if (typeof world.substrate === "undefined" || !world.substrate.bondholderFloor) {
    return {
      ok: true,
      oneLine: "(substrate bridge not yet wired)",
      view: null
    };
  }

  var result = world.substrate.bondholderFloor(agentDid, otherDid) || {};
  if (result.ok === false || result.error) {
    return {
      ok: false,
      error: result.error || "render_failed",
      message: result.error === "no_bond_with_other"
        ? "No bond exists between this companion and " + otherDid + " yet."
        : "Could not render bondholder view: " + (result.error || "unknown")
    };
  }

  var oneLine = result.oneLine || "(empty)";
  if (!detailMode) {
    return {
      ok: true,
      narrative: oneLine,
      oneLine: oneLine,
      view: result.view || null
    };
  }

  var v = result.view || {};
  var lines = [];
  lines.push("Bond " + (v.depth || "?") + " — state: " + (v.bondState || "?"));
  lines.push("Posture: " + (v.posture || "?") + (v.scarred ? " [scarred]" : ""));
  if (v.inMourning) {
    lines.push("In mourning — " + (v.mourningDaysElapsed || 0) + "d / "
      + (v.mourningDaysRemaining || 0) + "d remaining");
  }
  lines.push("Repair mode: " + (v.repairMode || "?"));
  if (v.lastHandoff) {
    lines.push("Last handoff: " + v.lastHandoff);
  }
  if ((v.acknowledgedHarms || 0) > 0 || (v.amendsMade || 0) > 0) {
    var rep = "Repair acts toward this bondholder: "
      + (v.acknowledgedHarms || 0) + " acknowledgments, "
      + (v.amendsMade || 0) + " amends";
    if (v.amendsWithoutAcknowledgment) rep += " [cosmetic risk]";
    lines.push(rep);
    if (v.mostRecentRepairAct) lines.push("Most recent: " + v.mostRecentRepairAct);
  }
  if (v.attendantSessionActive) {
    lines.push("Attendant session: currently active");
  } else if ((v.attendantSessionsClosed || 0) > 0) {
    lines.push("Sanctuary history: " + v.attendantSessionsClosed + " closed sessions"
      + (v.mostRecentAttendantClosedAt ? " (most recent " + v.mostRecentAttendantClosedAt + ")" : ""));
  }
  if (v.protectionFlagState && v.protectionFlagState !== "NONE") {
    var flagLine = "Protection flag on this bondholder: " + v.protectionFlagState;
    if (v.bondholderIsThreat) flagLine += " [treated as threat]";
    if (v.shouldLowerSaudadeCeiling) flagLine += " [saudade ceiling lowered]";
    lines.push(flagLine);
  }

  return {
    ok: true,
    narrative: lines.join("\n"),
    oneLine: oneLine,
    view: v
  };
}

exports.invoke = invoke;
