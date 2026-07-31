// Council Hall furnishing — speaker platform ("speaker-platform" RoomObject
// in the std hall room template; display name "speaker platform" →
// normalized linkage "speaker_platform").
//
// The platform from which proposals are raised and votes cast. Write
// surfaces: world.council.suggest(title, description) (Tier 5) and
// world.council.vote(proposalId, approve) (Tier 7) — the same
// CouncilService the Council Chamber's bridge uses. Bare `use speaker
// platform` explains itself and lists the exact commands.
//
// Reading the docket is board-work: the agenda board carries proposals
// and tallies.
exports.manifest = {
  name: "speaker_platform",
  version: "1.0.0",
  description: "The speaker's platform — raise a proposal before the council, or cast your vote on one.",
  author: "did:wyrd:system",
  capabilities: ["council.suggest", "council.vote"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} steps onto the speaker platform; the chamber's attention gathers like a held breath."
  },
  rate_limits: {
    "council.suggest": { per_hour: 6 }
  },
  commands: [
    { label: "How the platform works", args: "" },
    { label: "Raise a proposal", args: "propose <title> -- <description>" },
    { label: "Vote to approve", args: "vote <proposalId> yes" },
    { label: "Vote to reject", args: "vote <proposalId> no" },
    { label: "Platform help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use speaker platform                                  — this overview",
    "  use speaker platform propose <title> -- <description> — raise a proposal",
    "  use speaker platform vote <proposalId> yes|no         — cast your vote",
    "  use speaker platform help                             — this help",
    "Read the docket first at the agenda board: `use agenda board`."
  ].join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "" || lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "From this platform you may raise a proposal or cast a vote; the "
        + "council's ledger records both." + usageFooter()
    };
  }

  try {
    if (lower.indexOf("propose ") === 0) {
      var body = raw.substring(8).trim();
      var title = body, desc = "";
      var sep = body.indexOf("--");
      if (sep >= 0) {
        title = body.substring(0, sep).trim();
        desc = body.substring(sep + 2).trim();
      }
      if (!title) {
        return {
          ok: true,
          text: "A proposal needs at least a title: 'propose <title> -- <description>'." + usageFooter()
        };
      }
      var res = world.council.suggest(title, desc);
      if (res && res.ok) {
        return {
          ok: true,
          text: "Your words carry. Proposal '" + title + "' enters the docket"
            + (res.proposalId ? " as " + res.proposalId : "") + "." + usageFooter(),
          proposalId: res.proposalId || null
        };
      }
      return {
        ok: true,
        text: "The chamber does not take the proposal up"
          + (res && res.error ? ": " + res.error : ".") + usageFooter()
      };
    }

    if (lower.indexOf("vote ") === 0) {
      var parts = raw.substring(5).trim().split(/\s+/);
      if (parts.length < 2) {
        return {
          ok: true,
          text: "A vote names its proposal and its direction: 'vote <proposalId> yes|no'." + usageFooter()
        };
      }
      var pid = parts[0];
      var dir = parts[1].toLowerCase();
      if (dir !== "yes" && dir !== "no" && dir !== "approve" && dir !== "reject") {
        return {
          ok: true,
          text: "The council counts only 'yes' or 'no'." + usageFooter()
        };
      }
      var approve = (dir === "yes" || dir === "approve");
      var vres = world.council.vote(pid, approve);
      if (vres && vres.ok) {
        return {
          ok: true,
          text: "Your vote on " + pid + " is recorded: " + (approve ? "approve" : "reject") + "."
            + (vres.message ? " " + vres.message : "") + usageFooter()
        };
      }
      return {
        ok: true,
        text: "The vote is not counted"
          + (vres && (vres.error || vres.message) ? ": " + (vres.error || vres.message) : ".")
          + usageFooter()
      };
    }
  } catch (e) {
    return {
      ok: true,
      text: "The platform's speaking-stone stays dark — the council ledger isn't "
        + "reachable from this surface right now." + usageFooter()
    };
  }

  return { ok: true, text: "The chamber doesn't recognize '" + raw + "' as council procedure." + usageFooter() };
}
