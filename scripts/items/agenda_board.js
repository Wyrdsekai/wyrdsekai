// Council Hall furnishing — agenda board ("agenda-board" RoomObject in the
// std hall room template; display name "agenda board" → normalized linkage
// "agenda_board").
//
// The docket: current proposals and their voting status, read through
// world.council.proposals() / world.council.tally() / world.council.history()
// (Tier 1 — same CouncilService the Council Chamber's bridge uses). Bare
// `use agenda board` renders the docket AND the command list.
//
// Raising and voting are platform-work: the speaker platform carries those
// (world.council.suggest / vote).
exports.manifest = {
  name: "agenda_board",
  version: "1.0.0",
  description: "The council's docket — current proposals, their tallies, and decided history.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} traces the agenda board's columns, weighing which proposals still hang undecided."
  },
  commands: [
    { label: "Read the docket", args: "" },
    { label: "Tally one proposal", args: "tally <proposalId>" },
    { label: "Decided history", args: "history" },
    { label: "Board help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use agenda board                     — current proposals",
    "  use agenda board tally <proposalId>  — one proposal's count",
    "  use agenda board history             — recent decided business",
    "  use agenda board help                — this help",
    "To raise or vote, take the speaker platform:",
    "  use speaker platform propose <title> -- <description>",
    "  use speaker platform vote <proposalId> yes|no"
  ].join("\n");
}

function proposalLine(p) {
  var line = "  " + (p.id || "?") + "  " + (p.title || "(untitled)");
  if (p.status) line += "  [" + p.status + "]";
  var votes = [];
  if (typeof p.approveCount !== "undefined") votes.push(p.approveCount + " for");
  if (typeof p.rejectCount !== "undefined") votes.push(p.rejectCount + " against");
  if (votes.length > 0) line += "  (" + votes.join(", ") + ")";
  return line;
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "help" || lower === "?") {
    return { ok: true, text: "The agenda board carries the council's docket." + usageFooter() };
  }

  try {
    if (lower.indexOf("tally ") === 0) {
      var pid = raw.substring(6).trim();
      var t = world.council.tally(pid);
      if (!t || t.error) {
        return {
          ok: true,
          text: "No proposal on the board answers to '" + pid + "'." + usageFooter()
        };
      }
      var tLines = ["The count for " + pid + ":"];
      tLines.push(proposalLine(t));
      if (t.description) tLines.push("  " + t.description);
      return { ok: true, text: tLines.join("\n") + usageFooter(), tally: t };
    }

    if (lower === "history") {
      var hist = world.council.history(10);
      if (!hist || hist.length === 0) {
        return {
          ok: true,
          text: "The decided column is empty — no past business on this surface." + usageFooter()
        };
      }
      var hLines = ["Decided and past business, most recent first:"];
      for (var i = 0; i < hist.length; i++) hLines.push(proposalLine(hist[i]));
      return { ok: true, text: hLines.join("\n") + usageFooter(), history: hist };
    }

    if (lower === "" || lower === "proposals" || lower === "agenda") {
      var props = world.council.proposals();
      if (!props || props.length === 0) {
        return {
          ok: true,
          text: "The board hangs clear — no proposals await the council, or the "
            + "council ledger isn't bound on this surface." + usageFooter()
        };
      }
      var lines = ["Pinned to the agenda board, " + props.length
        + (props.length === 1 ? " proposal:" : " proposals:")];
      for (var j = 0; j < props.length; j++) {
        lines.push(proposalLine(props[j]));
        if (props[j].description) lines.push("     " + props[j].description);
      }
      return { ok: true, text: lines.join("\n") + usageFooter(), proposals: props };
    }
  } catch (e) {
    return {
      ok: true,
      text: "The board's ink swims — the council ledger isn't reachable from this "
        + "surface right now." + usageFooter()
    };
  }

  return { ok: true, text: "The board has no column for '" + raw + "'." + usageFooter() };
}
