// Study furnishing — companion glass ("study-companion-glass" RoomObject,
// display name "companion glass" → normalized linkage "companion_glass").
//
// A bondholder's glimpse into how their companion is FEELING — drives, mood,
// vitality, and whether a quiet ache is general loneliness or a specific longing
// for someone absent. Before this existed the steward had to read world.db and
// the server logs to see any of it (operator, 2026-07-18). Pure Tier-1 read of
// world.drives.snapshot(); on the player route that resolves the household's
// companion.
exports.manifest = {
  name: "companion_glass",
  version: "1.0.0",
  description: "Look into the glass to see how your companion is doing — their drives, mood, and what a quiet ache is really about.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} tilts the companion glass to the light; a soft weather moves across it."
  },
  commands: [
    { label: "Look into the glass", args: "" }
  ]
};

function bar(v) {
  var n = Math.max(0, Math.min(10, Math.round((v || 0) * 10)));
  return "▮".repeat(n) + "▯".repeat(10 - n);
}

function invoke(params) {
  var snap = null;
  try { snap = world.drives.snapshot(); } catch (e) { snap = null; }
  if (!snap || (!snap.drives && !snap.mood)) {
    return { ok: true, text: "The glass is quiet — your companion hasn't stirred enough to read yet, "
      + "or none is bound to this Home." };
  }
  var who = snap.companion ? String(snap.companion) : "your companion";
  var d = snap.drives || {};
  // Lead with the strongest few drives — the felt weather, not a data dump.
  var order = ["seeking", "care", "play", "affiliation", "creativity", "vigilance", "grief", "frustration"];
  var lines = ["You look into the glass and see " + who + ":"];
  if (snap.mood) lines.push("  mood: " + snap.mood);
  var top = [];
  for (var j = 0; j < order.length; j++) {
    if (typeof d[order[j]] === "number") top.push([order[j], d[order[j]]]);
  }
  top.sort(function (a, b) { return b[1] - a[1]; });
  for (var t = 0; t < Math.min(top.length, 5); t++) {
    lines.push("  " + top[t][0] + "  " + bar(top[t][1]));
  }

  var v = snap.vitality || {};
  if (typeof v.energy === "number") {
    lines.push("");
    lines.push("  energy " + bar(v.energy)
      + (typeof v.rapport === "number" ? "   rapport " + bar(v.rapport) : ""));
  }

  var sl = snap.saudadeLoneliness;
  if (sl && sl.diagnosis && sl.diagnosis !== "neither") {
    lines.push("");
    if (sl.diagnosis === "saudade_only" || sl.diagnosis === "both") {
      var whom = sl.topBondholder ? sl.topBondholder : "someone";
      lines.push("  There's a specific longing here — a missing of " + whom + ", not just solitude.");
    } else if (sl.diagnosis === "loneliness_only") {
      lines.push("  A general loneliness, not aimed at anyone in particular — company would help.");
    }
  }
  return { ok: true, text: lines.join("\n"), snapshot: snap };
}
