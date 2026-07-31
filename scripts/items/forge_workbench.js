// Phase D-N demo.
//
// A workbench tool that surfaces the agent's current Forge gap report and
// lets the agent propose a small skill draft when invoked with params.propose=true.
//
// Demonstrates §4.10 forge.cycle_status (Tier 1), forge.gap_report (Tier 1),
// forge.observe (Tier 4), and forge.propose_skill (Tier 5).
exports.manifest = {
  name: "forge_workbench",
  version: "1.0.0",
  description: "Inspect Forge state and propose skill drafts from in-world.",
  author: "did:wyrd:system",
  capabilities: ["forge.observe", "forge.propose_skill", "forge.journal"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The workbench warms; sketch-marks brighten and a draft skill flickers into shape."
  },
  rate_limits: {
    "forge.propose_skill": { per_hour: 4 }
  },
  // Items-as-tools contract — bare `use` is the read-only Forge inspection.
  // The propose path needs structured params (params.propose + skillName /
  // code / rationale), which a menu args string cannot supply.
  commands: [
    { label: "Inspect Forge state (cycle, gaps, recent)", args: "" }
  ],
  // Optional: the no-arg default inspects Forge state, which is the common case.
  params: [
    { name: "propose", type: "string", required: false,
      description: "Propose a new skill for the Forge. Pair with skillName + skillDescription." },
    { name: "skillName", type: "string", required: false,
      description: "Short identifier for the skill being proposed or built." },
    { name: "skillDescription", type: "string", required: false,
      description: "What the skill does, in a sentence." },
    { name: "runtime", type: "string", required: false,
      description: "Runtime the skill's code targets." },
    { name: "code", type: "string", required: false,
      description: "The skill's source." },
    { name: "rationale", type: "string", required: false,
      description: "Why this skill is worth forging." }
  ]
};

function invoke(params) {
  // Read-only state surfaces are implicit Tier 1.
  var status = world.forge.cycle_status();
  var gaps = world.forge.gap_report();
  var recent = world.forge.history(5);

  if (!params.propose) {
    return {
      ok: true,
      cycleStatus: status,
      gapReport: gaps,
      recent: recent
    };
  }

  // Tier 4 — feed a structured observation that this draft was authored.
  world.forge.observe("workbench_authoring", {
    skill: params.skillName,
    purpose: params.skillDescription,
    importance: 0.6
  });

  // Tier 5 — drop a draft into the steward's pinboard for review.
  var draft = world.forge.propose_skill(
    params.skillName || "untitled",
    params.skillDescription || "",
    params.runtime || "javascript",
    params.code || "",
    params.rationale || "Drafted via workbench item."
  );

  // Tier 2 — leave a note in the forge journal so the next cycle sees it.
  if (draft.ok) {
    world.forge.journal("Workbench draft submitted: " + params.skillName);
  }

  return {
    ok: draft.ok === true,
    draftId: draft.draftId || null,
    error: draft.error || null,
    cycleStatus: status
  };
}

exports.invoke = invoke;
