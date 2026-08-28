// CodeZaiku Workshop furnishing — familiar perch ("workshop-familiar-perch"
// RoomObject, display name "familiar perch" → normalized linkage
// "familiar_perch").
//
// Where a companion's WORKING SELVES rest between tasks. 2026-07-18: the perch
// used to center on a persistent "Coding Familiar" that no production path ever
// summons — dead prose describing a dead feature. The live, primary mechanism is
// the BUNSHIN: the companion forking herself onto a task (persistent, tracked,
// resumed across restarts). "Familiars" — separate thought-form personas — are a
// lighter, secondary thing; the ephemeral kind runs but isn't tracked here, so
// the perch says so honestly instead of pretending. Read-only (Tier 1):
// world.bunshin.list() is the load-bearing surface; world.familiar.list() shows
// any tracked familiars if the thought-form path ever surfaces them.
exports.manifest = {
  name: "familiar_perch",
  version: "1.0.0",
  description: "The perch where a companion's bunshin (self-forks) rest between tasks — read who is out working, and who has come home.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} rests a hand on the worn perch-bar, checking for the warmth of a recent landing."
  },
  commands: [
    { label: "Check the perch", args: "" },
    { label: "One worker's state", args: "status <id>" },
    { label: "Perch help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use familiar perch              — who is out working, who has come home",
    "  use familiar perch status <id>  — one worker, read closely",
    "  use familiar perch help         — this help",
    "A companion dispatches a bunshin herself when a task needs her hands in two",
    "places; the perch only watches."
  ].join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "The perch is where a companion's bunshin — the selves she sends out to work — "
        + "rest between tasks." + usageFooter()
    };
  }

  if (lower.indexOf("status ") === 0) {
    var fid = raw.substring(7).trim();
    var st = null;
    try { st = world.familiar.status(fid); } catch (e) { st = null; }
    if (!st || st.error) {
      return {
        ok: true,
        text: "The perch holds no trace of a familiar called '" + fid + "'." + usageFooter()
      };
    }
    var sLines = ["The perch remembers '" + fid + "':"];
    for (var k in st) {
      if (st[k] === null || typeof st[k] === "object") continue;
      sLines.push("  " + k + ": " + st[k]);
    }
    return { ok: true, text: sLines.join("\n") + usageFooter(), status: st };
  }

  if (lower === "") {
    var familiars = null, bunshin = null;
    try { familiars = world.familiar.list(); } catch (e) { familiars = null; }
    try { bunshin = world.bunshin.list(); } catch (e2) { bunshin = null; }

    var hasF = familiars && familiars.length > 0;
    var hasB = bunshin && bunshin.length > 0;
    if (!hasF && !hasB) {
      return {
        ok: true,
        text: "The perch is quiet — no bunshin is out working right now. When your companion "
          + "sends one of herself off on a task, its state reads here until it comes home." + usageFooter()
      };
    }
    var lines = [];
    // Bunshin first — the live, primary mechanism.
    if (hasB) {
      lines.push("Bunshin out working, " + bunshin.length + ":");
      for (var j = 0; j < bunshin.length; j++) {
        var b = bunshin[j];
        lines.push("  " + (b.name || b.id || b.bunshinId || "?")
          + (b.status ? "  [" + b.status + "]" : "")
          + (b.task ? " — " + b.task : ""));
      }
    }
    if (hasF) {
      if (hasB) lines.push("");
      lines.push("Thought-form familiars present, " + familiars.length + ":");
      for (var i = 0; i < familiars.length; i++) {
        var f = familiars[i];
        lines.push("  " + (f.name || f.id || f.familiarId || "?")
          + (f.status ? "  [" + f.status + "]" : "")
          + (f.task ? " — " + f.task : ""));
      }
    }
    return { ok: true, text: lines.join("\n") + usageFooter(), familiars: familiars || [], bunshin: bunshin || [] };
  }

  return { ok: true, text: "The perch doesn't answer to '" + raw + "'." + usageFooter() };
}
