// repair-mirror Study furnishing.
//
// Surfaces the companion's current repair mode (SELF / BONDED / ATTENDANT
// / STEWARD / NONE) plus the most recent handoff event. The companion's
// own view of where they are in the repair-mode lattice — used by both
// the steward and the companion themselves to ground reflection.
//
// Verbs:
//   use repair-mirror                — show current mode + last handoff
//
// Host bridge surfaces consumed (wired via ItemWorldApi):
//   world.substrate.currentRepairMode(agentDid)  — Tier 1, read-only
//
// Graceful stub when the substrate bridge isn't wired.
exports.manifest = {
  name: "repair_mirror",
  version: "1.0.0",
  description: "The companion's current repair mode + most recent handoff.",
  author: "did:wyrd:system",
  capabilities: ["substrate.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The mirror surface ripples; the current repair mode surfaces in soft script."
  },
  // Items-as-tools contract — single no-arg read; invoke() parses no args.
  commands: [
    { label: "Read the mirror (current repair mode)", args: "" }
  ]
};

function invoke(params) {
  var agentDid = (params && params.agentDid) || (params && params.targetDid) || null;
  if (!agentDid) {
    return {
      ok: false,
      error: "no_agent",
      message: "This mirror needs a companion to read from."
    };
  }

  if (typeof world.substrate === "undefined" || !world.substrate.currentRepairMode) {
    return {
      ok: true,
      narrative: "(substrate bridge not yet wired — repair mode unavailable)",
      mode: "unknown",
      lastHandoff: null
    };
  }

  var result = world.substrate.currentRepairMode(agentDid) || {};
  if (result.ok === false || result.error) {
    return {
      ok: false,
      error: result.error || "read_failed",
      message: "Could not read repair mode: " + (result.error || "unknown")
    };
  }

  var mode = result.mode || "none";
  var lines = [];
  lines.push("Current repair mode: " + mode);

  if (result.lastHandoff) {
    var h = result.lastHandoff;
    lines.push("Most recent handoff: " + (h.from || "?") + " → " + (h.to || "?"));
    if (h.reason) lines.push("  Reason: " + h.reason);
    if (h.at) lines.push("  When: " + h.at);
  } else if (mode === "none") {
    lines.push("No repair work is active right now.");
  } else {
    lines.push("(no handoff record yet for this mode)");
  }

  return {
    ok: true,
    narrative: lines.join("\n"),
    mode: mode,
    lastHandoff: result.lastHandoff || null
  };
}

exports.invoke = invoke;
