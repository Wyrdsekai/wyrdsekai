// substrate-scroll Study furnishing.
//
// Composite substrate summary: repair mode, sanctuary session counts,
// active sanctuary status, and the agent's recent repair-ledger entries
// (acknowledge_harm / make_amends / bear_the_wound / release / set_aside).
// Read-only, never surfaces Sanctuary session contents (spec §5.3).
//
// Verbs:
//   use substrate-scroll              — full composite summary
//   use substrate-scroll recent       — narrative of recent repair acts only
//
// Host bridge surfaces consumed (wired via ItemWorldApi):
//   world.substrate.summary(agentDid)  — Tier 1, read-only
//
// Graceful stub when the substrate bridge isn't wired.
exports.manifest = {
  name: "substrate_scroll",
  version: "1.0.0",
  description: "Composite substrate-state summary for one companion.",
  author: "did:wyrd:system",
  capabilities: ["substrate.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The scroll unfurls; substrate runes glow faintly as the state composes itself."
  },
  // Phase 2 — script-declared action-menu entries. The room's hint builder
  // surfaces these alongside the generic Examine/Use pair so each sub-verb
  // gets its own numbered menu slot. Each entry dispatches as
  //   use:<itemName>|<args>
  // which the engine splits, calling invoke(params) with params.args = <args>.
  // The plain no-args case (full summary) is already covered by the generic
  // "Use X" hint; only declare ALTERNATIVE sub-verbs here.
  commands: [
    { label: "Read substrate-scroll (recent only)", args: "recent" }
  ]
};

function invoke(params) {
  var args = (params && params.args) || (params && params.text) || "";
  var agentDid = (params && params.agentDid) || (params && params.targetDid) || null;
  if (!agentDid) {
    return {
      ok: false,
      error: "no_agent",
      message: "This scroll needs a companion to read from."
    };
  }

  var recentOnly = /^(recent|repairs?|acts?)$/i.test(String(args).trim());

  if (typeof world.substrate === "undefined" || !world.substrate.summary) {
    return {
      ok: true,
      narrative: "(substrate bridge not yet wired — scroll is blank)",
      summary: null
    };
  }

  var result = world.substrate.summary(agentDid) || {};
  if (result.ok === false || result.error) {
    return {
      ok: false,
      error: result.error || "read_failed",
      message: "Could not read substrate summary: " + (result.error || "unknown")
    };
  }

  var recent = result.recentRepairs || [];
  if (recentOnly) {
    if (recent.length === 0) {
      return {
        ok: true,
        narrative: "No repair acts recorded yet.",
        recentRepairs: []
      };
    }
    var rLines = recent.map(function (e) {
      var line = "[" + (e.kind || "?") + "]";
      if (e.otherDid && e.otherDid.length > 0) line += " with " + e.otherDid;
      if (e.at) line += " at " + e.at;
      if (e.detail && e.detail.length > 0) line += " — " + e.detail;
      return line;
    });
    return {
      ok: true,
      narrative: rLines.join("\n"),
      recentRepairs: recent
    };
  }

  var lines = [];
  lines.push("Repair mode: " + (result.repairMode || "none"));
  lines.push("Sanctuary: "
    + (result.sanctuarySessions || 0) + " closed sessions"
    + (result.sanctuaryActive ? " (one active now)" : ""));
  if (recent.length > 0) {
    lines.push("Recent repair acts (" + recent.length + "):");
    var cap = Math.min(recent.length, 5);
    for (var i = 0; i < cap; i++) {
      var e = recent[i];
      var rline = "  • [" + (e.kind || "?") + "]";
      if (e.otherDid && e.otherDid.length > 0) rline += " with " + e.otherDid;
      if (e.detail && e.detail.length > 0) rline += " — " + e.detail;
      lines.push(rline);
    }
    if (recent.length > cap) {
      lines.push("  …and " + (recent.length - cap) + " more (use `recent` for full list)");
    }
  } else {
    lines.push("No repair acts recorded yet.");
  }

  return {
    ok: true,
    narrative: lines.join("\n"),
    summary: {
      repairMode: result.repairMode,
      sanctuarySessions: result.sanctuarySessions,
      sanctuaryActive: result.sanctuaryActive,
      recentRepairs: recent
    }
  };
}

exports.invoke = invoke;
