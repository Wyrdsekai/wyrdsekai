// Chapel furnishing — bond reliquary ("chapel-reliquary" RoomObject, display
// name "bond reliquary" → normalized linkage "bond_reliquary").
//
// The glass-fronted cabinet of released threads. Read surface is
// world.bonds.list() — every bond, active and released, each thread tied
// off and none cut. Bare `use bond reliquary` renders the cabinet AND the
// command list; `thread <partner>` reads a single thread via
// world.chapel.bond_status(target).
//
// Bond WRITES (suggesting, releasing) are ceremonies, not cabinet acts —
// they run through the companion's bond chapel rite (world.bonds.suggest /
// world.chapel.exit_ritual) or `wyrd bond`. The reliquary says so honestly.
exports.manifest = {
  name: "bond_reliquary",
  version: "1.0.0",
  description: "A glass-fronted cabinet of silken bond-threads — each parting tied off in a loop, none of them cut.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} studies the reliquary's hanging threads, each loop a bond the chapel remembers."
  },
  commands: [
    { label: "Read the threads (all bonds)", args: "" },
    { label: "Read one thread", args: "thread <partner>" },
    { label: "Reliquary help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use bond reliquary                    — every thread the chapel keeps",
    "  use bond reliquary thread <partner>   — one thread, read closely",
    "  use bond reliquary help               — this help",
    "The reliquary keeps; it does not tie or release. New bonds and partings are",
    "ceremonies — a companion performs them through the bond chapel rite, or use",
    "`wyrd bond` from outside. Parting is a knot here, never a severing."
  ].join("\n");
}

function renderAll() {
  var bonds = null;
  try { bonds = world.bonds.list(); } catch (e) { bonds = null; }
  if (!bonds || bonds.length === 0) {
    return "The cabinet hangs empty — no bond-threads are recorded on this surface, "
      + "or the reliquary isn't bound to a companion.";
  }
  var lines = ["Behind the glass, the threads:"];
  var active = 0, released = 0;
  for (var i = 0; i < bonds.length; i++) {
    var b = bonds[i];
    var mark;
    if (b.active === false) {
      released++;
      mark = b.scarred ? "[scarred loop]" : "[tied off]";
    } else {
      active++;
      mark = "[" + (b.depth || "living") + "]";
    }
    lines.push("  " + mark + "  " + (b.partner || "?")
      + "  (" + (b.interactionCount || 0) + " interactions)");
  }
  lines.push("");
  lines.push(active + " living, " + released + " tied off. None cut.");
  return lines.join("\n");
}

function renderOne(partner) {
  var status = null;
  try { status = world.chapel.bond_status(partner); } catch (e) { status = null; }
  if (!status || status.error) {
    return "No thread hangs here for '" + partner + "'.";
  }
  var lines = ["The thread for '" + partner + "', read closely:"];
  if (status.depth) lines.push("  Depth:  " + status.depth);
  if (typeof status.active !== "undefined") {
    lines.push("  Standing: " + (status.active === false
      ? (status.scarred ? "scarred" : "tied off") : "living"));
  }
  if (status.interactionCount) lines.push("  Interactions: " + status.interactionCount);
  if (status.bondId) lines.push("  Thread mark: " + status.bondId);
  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = args.toLowerCase();

  if (lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "The reliquary holds every bond-thread this chapel has witnessed." + usageFooter()
    };
  }
  if (lower.indexOf("thread ") === 0) {
    return { ok: true, text: renderOne(args.substring(7).trim()) + usageFooter() };
  }
  if (lower === "") {
    return { ok: true, text: renderAll() + usageFooter() };
  }
  return { ok: true, text: "The reliquary doesn't answer to '" + args + "'." + usageFooter() };
}
