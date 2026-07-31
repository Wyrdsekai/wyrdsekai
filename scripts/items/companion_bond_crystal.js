// Study furnishing — companion bond crystal ("study-companion-crystal"
// RoomObject, display name "companion bond crystal" → normalized linkage
// "companion_bond_crystal").
//
// The bond readout the object's description promises: bond strength per
// partner via world.bonds.list() (same data the Home "shelf" keepsakes
// read) and the household's companions via world.companions.list().
// Self-documenting: bare use renders the overview AND the command list.
//
// Communication-preference WRITES are not exposed through world.* yet —
// the crystal says so honestly instead of pretending.
exports.manifest = {
  name: "companion_bond_crystal",
  version: "1.0.0",
  description: "Warm crystal showing your companion bonds — depth, activity, scars — and who shares the household.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language", "ambient_shift"],
    descriptor_template: "The bond crystal warms under {actor}'s palm, its glow tracing the threads of every bond it remembers."
  },
  commands: [
    { label: "View your bonds", args: "" },
    { label: "List companions", args: "companions" },
    { label: "Birth a new companion", args: "birth <name>" },
    { label: "Hand the bond to a member", args: "transfer <username>" },
    { label: "Crystal help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use companion bond crystal                       — bond overview",
    "  use companion bond crystal companions            — who shares the household",
    "  use companion bond crystal birth <name>          — birth a new companion (steward)",
    "  use companion bond crystal transfer <username>   — hand the bondholder role to a member (steward)",
    "  use companion bond crystal help                  — this help",
    "Communication preferences aren't settable from the crystal yet — speak to your companion directly."
  ].join("\n");
}

function renderBonds() {
  var bonds = null;
  try { bonds = world.bonds.list(); } catch (e) { bonds = null; }
  if (!bonds || bonds.length === 0) {
    return "The crystal is clear and quiet — no bonds recorded yet, or this surface isn't bound to your Home.";
  }
  // A bond partner is stored by DID; a person reads names. Resolve through the
  // household roster (best-effort — an unresolvable DID stays visible as-is,
  // never hidden).
  var names = {};
  try {
    var comps = world.companions.list() || [];
    for (var c = 0; c < comps.length; c++) {
      if (comps[c].did && comps[c].name) names[comps[c].did] = comps[c].name;
    }
  } catch (e) { /* roster unavailable — show DIDs */ }
  var lines = ["The crystal glows; within it, your bonds:"];
  var active = 0, scarred = 0;
  for (var i = 0; i < bonds.length; i++) {
    var b = bonds[i];
    var status = (b.active === false)
      ? (b.scarred ? "[scarred]" : "[severed]")
      : "[" + (b.depth || "?") + "]";
    if (b.active !== false) active++;
    if (b.scarred) scarred++;
    var partner = names[b.partner] || b.partner || "?";
    // Who holds the bond matters — after a handover a former bondholder keeps
    // the relationship (MEMBER) but not the role; make that visible.
    var role = b.kind === "BONDHOLDER" ? "  — you hold the bond"
             : b.kind === "MEMBER" ? "  — household member" : "";
    lines.push("  " + status + "  ↔  " + partner
      + "  (" + (b.interactionCount || 0) + " interactions)" + role);
  }
  lines.push("");
  lines.push(active + " active" + (scarred ? ", " + scarred + " scarred" : "") + ".");
  return lines.join("\n");
}

function renderCompanions() {
  var comps = null;
  try { comps = world.companions.list(); } catch (e) { comps = null; }
  if (!comps || comps.length === 0) {
    return "The crystal shows no companions on this surface.";
  }
  var lines = ["In the crystal's depths, the household's companions:"];
  for (var i = 0; i < comps.length; i++) {
    var c = comps[i];
    lines.push("  " + (c.name || c.id || "?")
      + (c.status ? "  (" + c.status + ")" : ""));
  }
  return lines.join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The bond crystal shows bond strength and household companions." + usageFooter() };
  }
  if (args === "companions") {
    return { ok: true, text: renderCompanions() + usageFooter() };
  }
  // birth <name> — name keeps the typed casing (it becomes the companion's name).
  if (args === "birth" || args.indexOf("birth ") === 0) {
    var newName = raw.slice(5).trim();
    var born = null;
    try { born = world.companions.birth(newName); } catch (e) { born = { ok: false, error: String(e) }; }
    if (!born || born.ok === false) {
      return { ok: false, text: "The crystal stays dark: "
        + ((born && born.error) || "the birth did not take") + usageFooter() };
    }
    return { ok: true, text: "The crystal flares — " + (born.summary || (born.name + " is born."))
      + "\nThey will find their own voice; give them a moment, then say hello." };
  }
  // transfer <username> — hand the bondholder role to another member.
  if (args === "transfer" || args.indexOf("transfer ") === 0) {
    var member = raw.slice(8).trim();
    var moved = null;
    try { moved = world.bonds.transfer(member); } catch (e) { moved = { ok: false, error: String(e) }; }
    if (!moved || moved.ok === false) {
      return { ok: false, text: "The crystal holds still: "
        + ((moved && moved.error) || "the handover did not take") + usageFooter() };
    }
    return { ok: true, text: "The light inside shifts its center. " + (moved.summary || "") };
  }
  if (args === "" || args === "bonds") {
    return { ok: true, text: renderBonds() + usageFooter() };
  }
  return { ok: true, text: "The crystal doesn't answer to '" + args + "'." + usageFooter() };
}
