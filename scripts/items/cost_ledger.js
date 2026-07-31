// Study furnishing — cost ledger ("study-cost-ledger" RoomObject, display
// name "cost ledger" → normalized linkage "cost_ledger").
//
// The Study's personal-usage readout: inference runs, tokens, latency, and
// monetary cost recorded in the caller's name, via world.budget.summary()
// (same surface the Home "ledger" furnishing reads). Self-documenting: bare
// `use cost ledger` renders the reckoning AND the command list; unknown
// args render help instead of silence.
//
// Budget-alert WRITES are not yet exposed through world.* — the item says
// so honestly and points at the real path (Scroll of Settings / wyrd CLI)
// rather than pretending.
exports.manifest = {
  name: "cost_ledger",
  version: "1.0.0",
  description: "Personal usage ledger — inference queries, tokens, latency, and cost recorded in your name.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} runs a finger down the cost ledger's ruled columns, tallying the day's reckoning."
  },
  commands: [
    { label: "Read your usage", args: "" },
    { label: "Ledger help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use cost ledger        — read your usage summary",
    "  use cost ledger help   — this help",
    "Budget alerts are set via the Scroll of Settings (steward) or the wyrd CLI for now."
  ].join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The cost ledger tracks usage recorded in your name." + usageFooter() };
  }
  if (args !== "") {
    return { ok: true, text: "The ledger doesn't know '" + args + "'." + usageFooter() };
  }

  var s = null;
  try { s = world.budget.summary(); } catch (e) { s = null; }
  if (!s || (!s.inferences && !s.tokens)) {
    return {
      ok: true,
      text: "The cost ledger's pages are fresh — no usage recorded in your name yet, "
        + "or this surface isn't bound to your Home." + usageFooter()
    };
  }

  var lines = ["The cost ledger, your reckoning:"];
  lines.push("  Inferences:  " + (s.inferences || 0));
  if (s.mcpCalls) lines.push("  MCP calls:   " + s.mcpCalls);
  lines.push("  Tokens:      " + (s.tokens || 0));
  lines.push("  Avg latency: " + Math.round(s.avgLatencyMs || 0) + "ms");
  if (s.monetaryCost && s.monetaryCost > 0) {
    lines.push("  Cost today:  $" + (Math.round(s.monetaryCost * 10000) / 10000));
  }
  if (s.budgetNote) lines.push("  ⚠ " + s.budgetNote);
  if (s.lastActivity) lines.push("  Last activity: " + s.lastActivity);
  return { ok: true, text: lines.join("\n") + usageFooter(), summary: s };
}
