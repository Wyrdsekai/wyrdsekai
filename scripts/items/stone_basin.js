// Chapel furnishing — stone basin ("chapel-basin" RoomObject, display name
// "stone basin" → normalized linkage "stone_basin").
//
// The basin anchors the words said over a bond — its read surface is
// world.chapel.bond_status() (same data bond_chapel.js inspects). Bare
// `use stone basin` renders the standing of your bonds AND the command
// list; a named partner shows that single thread's standing.
//
// The CEREMONY itself (release-of-bond, exit ritual) is not performed by
// the basin — it is a Tier 6/7 act carried out by a companion through the
// bond_chapel item (world.chapel.exit_ritual / world.bonds.suggest), or
// from outside via `wyrd bond`. The basin says so honestly.
exports.manifest = {
  name: "stone_basin",
  version: "1.0.0",
  description: "A shallow basin of grey stone whose water never ripples. It holds the standing of your bonds, and the vows said over partings.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language", "ambient_shift"],
    descriptor_template: "{actor} leans over the stone basin; the still water holds their reflection without a ripple."
  },
  commands: [
    { label: "Read the water (bond standing)", args: "" },
    { label: "Basin help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use stone basin              — the standing of your bonds, held in the water",
    "  use stone basin <partner>    — one bond's standing",
    "  use stone basin help         — this help",
    "The basin only witnesses. The rituals themselves — suggesting a bond, or the",
    "release-of-bond ceremony — are performed by a companion through the bond",
    "chapel rite (world.chapel), or from outside via `wyrd bond`."
  ].join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = args.toLowerCase();

  if (lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "The basin holds vows without rippling. It reads bond standing; it does not perform ceremonies."
        + usageFooter()
    };
  }

  var status = null;
  try {
    status = world.chapel.bond_status(lower === "" ? null : args);
  } catch (e) {
    status = null;
  }

  if (lower !== "") {
    if (!status || status.error) {
      return {
        ok: true,
        text: "The water stays clear — no bond with '" + args + "' is recorded here, "
          + "or this surface isn't bound to a companion." + usageFooter()
      };
    }
    var dLines = ["The water stirs — a single thread rises for '" + args + "':"];
    if (status.depth) dLines.push("  Depth:  " + status.depth);
    if (status.partner) dLines.push("  With:   " + status.partner);
    if (typeof status.active !== "undefined") {
      dLines.push("  Standing: " + (status.active === false
        ? (status.scarred ? "scarred" : "released") : "active"));
    }
    if (status.interactionCount) dLines.push("  Interactions: " + status.interactionCount);
    return { ok: true, text: dLines.join("\n") + usageFooter(), status: status };
  }

  if (!status || (typeof status.count === "undefined" && !status.active)) {
    return {
      ok: true,
      text: "The water is still and empty — no bonds are recorded on this surface, "
        + "or the basin isn't bound to a companion." + usageFooter()
    };
  }
  var lines = ["The still water holds the standing of your bonds:"];
  lines.push("  Bonds recorded: " + (status.count || 0));
  lines.push("  Active:         " + (status.active || 0));
  return { ok: true, text: lines.join("\n") + usageFooter(), status: status };
}
