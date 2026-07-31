// Ward Room furnishing — observation crystal ("ward-crystal" RoomObject,
// display name "observation crystal" → normalized linkage
// "observation_crystal"). Collision-checked: no other room or template
// names an object "observation crystal".
//
// "A crystal orb showing a shifting view of the zone's rooms." The view it
// actually carries: who is present in the Home right now
// (world.presence.in_home()) and the recent activity record
// (world.audit.recent(n)) — real reads, both implicit Tier-1 caps.
// Self-documenting: bare use renders the view AND the command list;
// unknown args render help instead of silence.
exports.manifest = {
  name: "observation_crystal",
  version: "1.0.0",
  description: "A scrying orb tuned to the household — who is present right now, and the recent record of what has happened.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language", "ambient_shift"],
    descriptor_template: "{actor} bends over the observation crystal; rooms and figures drift through its depths."
  },
  commands: [
    { label: "Gaze into the crystal", args: "" },
    { label: "Recent activity", args: "activity 20" },
    { label: "Who is present", args: "here" },
    { label: "Crystal help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use observation crystal              — who is present, and the latest activity",
    "  use observation crystal here         — just the presences",
    "  use observation crystal activity 20  — a longer stretch of the record (up to 50)",
    "  use observation crystal help         — this help",
    "The Warden's deeper security ledger lives in the household audit log (Study: use audit log)."
  ].join("\n");
}

function renderPresence() {
  var who = null;
  try { who = world.presence.in_home(); } catch (e) { who = null; }
  if (!who || who.length === 0) {
    return "The crystal shows still rooms — no presences it can see, or this surface isn't bound to your Home.";
  }
  var lines = ["Figures drift through the crystal — present now:"];
  for (var i = 0; i < who.length; i++) {
    var p = who[i];
    lines.push("  " + (p.name || p.id || p.username || "?")
      + (p.room ? "  — " + p.room : "")
      + (p.status ? "  (" + p.status + ")" : ""));
  }
  return lines.join("\n");
}

function renderActivity(limit) {
  var n = parseInt(limit, 10);
  if (!n || n < 1) n = 8;
  if (n > 50) n = 50;
  var events = null;
  try { events = world.audit.recent(n); } catch (e) { events = null; }
  if (!events || events.length === 0) {
    return "The crystal's depths hold no recent record — quiet hours, or this surface isn't bound to your Home.";
  }
  var lines = ["The record shifts across the crystal (" + events.length + " latest):"];
  for (var i = 0; i < events.length; i++) {
    var ev = events[i];
    var when = ev.timestamp || ev.time || "";
    var actor = ev.actor || ev.entity || ev.who || "?";
    var what = ev.action || ev.verb || ev.type || "?";
    var res = ev.resource || ev.target || "";
    lines.push("  " + when + "  " + actor + "  " + what + (res ? "  " + res : ""));
  }
  return lines.join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The crystal watches the household: presences now, activity just past." + usageFooter() };
  }
  if (args === "here" || args === "who" || args === "presence") {
    return { ok: true, text: renderPresence() + usageFooter() };
  }
  if (args.indexOf("activity") === 0) {
    return { ok: true, text: renderActivity(args.substring(8).trim()) + usageFooter() };
  }
  if (args === "") {
    return { ok: true, text: renderPresence() + "\n\n" + renderActivity(8) + usageFooter() };
  }
  return { ok: true, text: "The crystal clouds — it does not answer to '" + raw + "'." + usageFooter() };
}
