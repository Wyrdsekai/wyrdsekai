// Chronicle Study furnishing.
//
// Surfaces the synthesized chronicle (testimony + synthesis) of a companion
// agent at a chosen time scale. Steward-only by default (lives in the
// steward Study). Per-companion variants may surface in bondholder Studies
// later, with consent.
//
// Verbs:
//   use chronicle              — last day (default scale)
//   use chronicle week
//   use chronicle month
//   use chronicle diverge      — show where testimony and synthesis differ
//   use chronicle warnings     — surface active doom-loop / psychosis findings
//
// Host bridge surfaces consumed (wired via ItemWorldApi):
//   world.chronicle.read(agentDid, scale)   — Tier 1, read-only
//   world.chronicle.warnings(agentDid)      — Tier 1, read-only
//
// If the chronicle bridge is not yet wired the script degrades gracefully
// to a stub message so the item is still inspectable.
exports.manifest = {
  name: "chronicle",
  version: "1.0.0",
  description: "Read the steward chronicle for a companion (testimony + synthesis).",
  author: "did:wyrd:system",
  capabilities: ["chronicle.read"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "The chronicle's pages whisper as {actor} reads — testimony settling, synthesis pooling at the margin."
  },
  // Items-as-tools contract — every arg string invoke() actually parses.
  commands: [
    { label: "Read today's chronicle", args: "" },
    { label: "Read the week's chronicle", args: "week" },
    { label: "Read the month's chronicle", args: "month" },
    { label: "Compare testimony vs synthesis", args: "diverge" },
    { label: "Surface active warnings", args: "warnings" }
  ]
};

function invoke(params) {
  var args = (params && params.args) || (params && params.text) || "day";
  var agentDid = (params && params.agentDid) || (params && params.targetDid) || null;
  if (!agentDid) {
    return {
      ok: false,
      error: "no_agent",
      message: "Specify which companion this chronicle should cover."
    };
  }

  var tokens = String(args).trim().toLowerCase().split(/\s+/);
  var first = tokens[0] || "day";

  // ── warnings branch — surface detector findings ─────────────────────
  if (first === "warnings" || first === "warning" || first === "alerts") {
    if (typeof world.chronicle === "undefined" || !world.chronicle.warnings) {
      return { ok: true, scale: "warnings",
        narrative: "(chronicle bridge not yet wired — no warnings available)",
        warnings: [] };
    }
    var w = world.chronicle.warnings(agentDid) || {};
    var findings = w.findings || [];
    if (findings.length === 0) {
      return { ok: true, scale: "warnings",
        narrative: "No active warnings for " + agentDid + ".",
        warnings: [] };
    }
    var lines = findings.map(function (f) {
      return "[" + (f.severity || "WARN") + "] " + (f.key || "?") + ": "
        + (f.message || "");
    });
    return {
      ok: true, scale: "warnings",
      narrative: lines.join("\n"),
      warnings: findings
    };
  }

  // ── diverge branch — show where testimony and synthesis disagree ────
  if (first === "diverge" || first === "divergence") {
    var divDoc = readChronicle(agentDid, "DAY");
    if (!divDoc.ok) return divDoc;
    return {
      ok: true,
      scale: "diverge",
      narrative:
        "Testimony:\n" + (divDoc.testimony || "(empty)") + "\n\n" +
        "Synthesis:\n" + (divDoc.synthesis || "(empty)") + "\n\n" +
        "(Compare narratives directly — divergence in factual content is the signal.)",
      testimony: divDoc.testimony,
      synthesis: divDoc.synthesis
    };
  }

  // ── default: a single time-scale chronicle ──────────────────────────
  var scale = "DAY";
  if (first === "week" || first === "weekly") scale = "WEEK";
  else if (first === "month" || first === "monthly") scale = "MONTH";

  var doc = readChronicle(agentDid, scale);
  if (!doc.ok) return doc;

  var summary =
    "Chronicle (" + scale.toLowerCase() + ", "
      + (doc.stats && doc.stats.totalTicks ? doc.stats.totalTicks : 0) + " ticks)\n\n" +
    "Testimony — what " + (doc.agentName || "the agent") + " would say:\n" +
    (doc.testimony || "(no testimony in this window)") + "\n\n" +
    "Synthesis — what behavior shows:\n" +
    (doc.synthesis || "(no synthesis available)");

  return {
    ok: true,
    scale: scale.toLowerCase(),
    narrative: summary,
    testimony: doc.testimony,
    synthesis: doc.synthesis,
    stats: doc.stats
  };
}

function readChronicle(agentDid, scale) {
  if (typeof world.chronicle === "undefined" || !world.chronicle.read) {
    return {
      ok: true,
      agentName: "unknown",
      testimony: "(chronicle bridge not yet wired — synthesis unavailable)",
      synthesis: "(chronicle bridge not yet wired — testimony unavailable)",
      stats: { totalTicks: 0 }
    };
  }
  var doc = world.chronicle.read(agentDid, scale) || {};
  return {
    ok: doc.ok !== false,
    agentName: doc.agentName || null,
    testimony: doc.testimony || null,
    synthesis: doc.synthesis || null,
    stats: doc.stats || null
  };
}

exports.invoke = invoke;
