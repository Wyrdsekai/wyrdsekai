// Phase D-N chapel demo.
//
// A small chapel item that lets a companion read its bonds, suggest a new
// bond ritual to a counterparty, or — when explicitly invoked with
// params.exit=true — sever an existing bond.
//
// Demonstrates Tier 1 read (chapel.bond_status, bond.list, bond.detail),
// Tier 6 suggestion (bond.suggest), Tier 7 severance (chapel.exit_ritual).
exports.manifest = {
  name: "bond_chapel",
  version: "1.0.0",
  description: "Inspect and tend bonds from a chapel item.",
  author: "did:wyrd:system",
  capabilities: ["bond.suggest", "chapel.exit_ritual", "chapel.ceremony"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The chapel hush deepens; a soft candle-shimmer plays across the bond ledger."
  },
  rate_limits: {
    "chapel.exit_ritual": { per_day: 1 }
  },
  // Items-as-tools contract — the args-string surface. Bare `use` is the
  // read-only inspection; the suggest/exit paths need structured params
  // (params.target + params.suggest/params.exit), so they are not reachable
  // from a menu args string and are deliberately not listed here.
  commands: [
    { label: "Inspect bonds and chapel status", args: "" }
  ],
  // Declared so a companion can actually drive the chapel, not just stare at the
  // default view. All optional: "inspect bonds" is a legitimate no-arg default.
  params: [
    { name: "target", type: "string", required: false,
      description: "The bond to act on, named by the other party (a person or agent)." },
    { name: "exit", type: "string", required: false,
      description: "Leave a bond: the other party to part from." },
    { name: "reason", type: "string", required: false,
      description: "Why — recorded with the act." },
    { name: "suggest", type: "string", required: false,
      description: "Propose a change to a bond rather than making it." },
    { name: "depth", type: "string", required: false,
      description: "Bond depth to move toward: acquaintance, familiar, item, sacred." }
  ]
};

function invoke(params) {
  var allBonds = world.bonds.list();
  var status = world.chapel.bond_status(params.target || null);

  if (params.exit && params.target) {
    // Tier 7 — irreversible severance.
    var severed = world.chapel.exit_ritual(params.target, params.reason || "");
    return {
      ok: severed.ok === true,
      action: "exit_ritual",
      bondId: severed.bondId || null,
      scarred: severed.scarred || false,
      bondCount: allBonds.length
    };
  }

  if (params.suggest && params.target) {
    // Tier 6 — propose a new ritual.
    var suggestion = world.bonds.suggest(
      params.target,
      params.depth || "ITEM",
      params.reason || "We share many crafts."
    );
    return {
      ok: suggestion.ok === true,
      action: "suggest",
      suggestionId: suggestion.suggestionId || null,
      target: params.target
    };
  }

  // Default — read-only inspection.
  return {
    ok: true,
    action: "inspect",
    status: status,
    count: allBonds.length,
    active: allBonds.filter(function (b) { return b.active; }).length
  };
}

exports.invoke = invoke;
