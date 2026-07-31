// Study furnishing — pinboard ("study-pinboard" RoomObject, display name
// "pinboard" → normalized linkage "pinboard"). Member Study object.
//
// Bookmarks and clippings from the public Library. Reads pins via
// world.pinboard.list() ({id, content, ts} entries) and pins new
// clippings via world.pinboard.pin(text) — the universal-writes surface
// (capability "pinboard.pin", same call ItemWorldApiUniversalWritesTest
// exercises). Self-documenting: bare use renders the pins AND the
// command list; unknown args render help instead of silence.
exports.manifest = {
  name: "pinboard",
  version: "1.0.0",
  description: "A cork pinboard of bookmarks and clippings from the public Library — read them, or pin a new one.",
  author: "did:wyrd:system",
  capabilities: ["pinboard.pin"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} leans in at the pinboard, notes and clippings shifting in the draft."
  },
  commands: [
    { label: "Read your pins", args: "" },
    { label: "Pin a clipping", args: "pin <text>" },
    { label: "Pinboard help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use pinboard              — read your pinned clippings",
    "  use pinboard pin <text>   — pin a new clipping to the board",
    "  use pinboard help         — this help"
  ].join("\n");
}

function renderPins() {
  var pins = null;
  try { pins = world.pinboard.list(); } catch (e) { pins = null; }
  if (!pins || pins.length === 0) {
    return "The pinboard's cork is bare — nothing pinned yet, "
      + "or this surface isn't bound to your Home. Pin your first clipping with "
      + "'use pinboard pin <text>'.";
  }
  var lines = ["Pinned to the board, your clippings:"];
  for (var i = 0; i < pins.length; i++) {
    var p = pins[i];
    var line = "  📌  " + (p.content || "(blank note)");
    if (p.ts) line += "  (" + p.ts + ")";
    lines.push(line);
    if (p.id) lines.push("       id: " + p.id);
  }
  lines.push("");
  lines.push(pins.length + " clipping" + (pins.length === 1 ? "" : "s") + " on the board.");
  return lines.join("\n");
}

function pinText(text) {
  var result = null;
  try { result = world.pinboard.pin(text); } catch (e) { result = null; }
  if (!result || result.ok !== true) {
    var why = result && result.error ? " (" + result.error + ")" : "";
    return "The pin won't take — the board isn't wired for writing on this surface" + why
      + ". Nothing was lost; try again from your Home.";
  }
  return "You press a fresh pin through the clipping:\n  📌  " + text
    + (result.id ? "\n       id: " + result.id : "");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The pinboard holds bookmarks and clippings from the public Library — "
        + "read them here, or press a new one into the cork." + usageFooter()
    };
  }
  if (args === "pin") {
    return { ok: true, text: "Pin what? Give the board some text: 'use pinboard pin <text>'." + usageFooter() };
  }
  if (args.indexOf("pin ") === 0) {
    var text = raw.slice(4).trim();
    if (!text) {
      return { ok: true, text: "Pin what? Give the board some text: 'use pinboard pin <text>'." + usageFooter() };
    }
    return { ok: true, text: pinText(text) + usageFooter() };
  }
  if (args === "") {
    return { ok: true, text: renderPins() + usageFooter() };
  }
  return { ok: true, text: "The pinboard doesn't know '" + args + "'." + usageFooter() };
}
