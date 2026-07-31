// Workshop furnishing — draft pinboard ("draft-pinboard" RoomObject, display
// name "draft pinboard" → normalized linkage "draft_pinboard").
//
// The corkboard of skill drafts a companion has proposed in response to
// recurring gaps (–§6). Read surface is
// world.skill.pendingDrafts() — the same queue WorkshopPinboard renders and
// `wyrd skill drafts` lists. Bare `use draft pinboard` shows the queue AND
// the command list.
//
// APPROVE / REJECT / EDIT are steward decisions (Tier 5–7) that run through
// the HTTP decision path — `wyrd skill drafts approve/reject <id>` (W4) or
// the Study — not through this board. Item scripts can't call HTTP, so the
// approve/reject commands here TEACH the exact terminal command for the
// card you named, resolving <n> to the real draft id.
exports.manifest = {
  name: "draft_pinboard",
  version: "1.0.0",
  description: "A corkboard of pending skill drafts your companion proposed — read the queue here; decide at the workbench or via `wyrd skill drafts`.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} tilts a pinned card toward the lamp-light, reading a draft in the companion's careful hand."
  },
  commands: [
    { label: "Read the draft queue", args: "" },
    { label: "How to approve a draft", args: "approve <n>" },
    { label: "How to reject a draft", args: "reject <n>" },
    { label: "Pinboard help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use draft pinboard              — the queue of pending skill drafts",
    "  use draft pinboard approve <n>  — the terminal command that approves card n",
    "  use draft pinboard reject <n>   — the terminal command that rejects card n",
    "  use draft pinboard help         — this help",
    "Deciding is a steward act, done at the terminal: `wyrd skill drafts` lists,",
    "`wyrd skill drafts approve <id>` / `wyrd skill drafts reject <id>` decide,",
    "and edits happen at the workbench in your Study."
  ].join("\n");
}

// The board can't reach the decision routes itself (no HTTP from item
// scripts) — so it resolves the card and hands the steward the exact
// command. Honest narration over theater: nothing is decided here.
function decisionHint(verb, rest) {
  var drafts = null;
  try { drafts = world.skill.pendingDrafts(); } catch (e) { drafts = null; }
  if (!drafts || drafts.length === 0) {
    return "The corkboard is bare — there is nothing to " + verb + "." + usageFooter();
  }
  var pick = String(rest || "").trim();
  var d = null;
  if (pick !== "") {
    var n = parseInt(pick, 10);
    if (!isNaN(n) && n >= 1 && n <= drafts.length) {
      d = drafts[n - 1];
    } else {
      for (var i = 0; i < drafts.length; i++) {
        if (drafts[i].draftId === pick || drafts[i].name === pick) { d = drafts[i]; break; }
      }
    }
    if (!d) {
      return "No card matches '" + pick + "' — the board holds " + drafts.length
        + " (say a number 1–" + drafts.length + " or a draft id)." + usageFooter();
    }
  }
  if (!d && drafts.length === 1) d = drafts[0];
  if (!d) {
    return "Which card? The board holds " + drafts.length + " — " + verb
      + " <n> names one." + usageFooter();
  }
  var flag = verb === "approve" ? " [--note <text>]" : " [--reason <text>]";
  return "The board can show you the draft, but the decision is made at the terminal.\n"
    + "To " + verb + " '" + (d.name || "?") + "', run:\n"
    + "  wyrd skill drafts " + verb + " " + (d.draftId || "<draft-id>") + flag;
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The pinboard holds skill drafts awaiting a steward's decision." + usageFooter()
    };
  }
  if (verb === "approve" || verb === "reject") {
    return { ok: true, text: decisionHint(verb, rest) };
  }
  if (args !== "") {
    return { ok: true, text: "The pinboard has no card for '" + raw + "'." + usageFooter() };
  }

  var drafts = null;
  try { drafts = world.skill.pendingDrafts(); } catch (e) { drafts = null; }
  if (!drafts || drafts.length === 0) {
    return {
      ok: true,
      text: "The corkboard is bare — no drafts await a decision, or this surface "
        + "isn't bound to a companion's workbench." + usageFooter()
    };
  }

  var lines = ["The pinboard holds " + drafts.length
    + (drafts.length === 1 ? " pending draft:" : " pending drafts:")];
  for (var i = 0; i < drafts.length; i++) {
    var d = drafts[i];
    lines.push("  " + (i + 1) + ". " + (d.name || "?")
      + (d.description ? " — " + d.description : ""));
    if (d.rationale) lines.push("     why: " + d.rationale);
    if (d.draftId) lines.push("     id: " + d.draftId);
  }
  return { ok: true, text: lines.join("\n") + usageFooter(), drafts: drafts };
}
