// CodeZaiku Workshop furnishing — chronicle stone ("workshop-chronicle-stone"
// RoomObject, display name "chronicle stone" → normalized linkage
// "chronicle_stone").
//
// The pale stone that surfaces a familiar's story slice. Read surface is
// world.chronicle.read(agentDid, scale) / world.chronicle.warnings(agentDid)
// ( — same data the Study's chronicle item reads).
// Bare `use chronicle stone` reads the day-scale slice AND lists commands.
//
// The stone needs a companion bound to the surface (agentDid); with none
// it says so honestly rather than inventing a story.
exports.manifest = {
  name: "chronicle_stone",
  version: "1.0.0",
  description: "A smooth pale stone holding the familiar's chronicle slice — the narrative thread of its work here.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The chronicle stone warms under {actor}'s palm; faint story-lines surface in the pale grain."
  },
  commands: [
    { label: "Read the chronicle (day)", args: "" },
    { label: "Read a wider slice", args: "week" },
    { label: "Chronicle warnings", args: "warnings" },
    { label: "Stone help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use chronicle stone            — the day-scale story slice",
    "  use chronicle stone week       — a wider slice (also: month)",
    "  use chronicle stone warnings   — chronicle findings that need an eye",
    "  use chronicle stone help       — this help"
  ].join("\n");
}

function pickAgent(params) {
  return (params && (params.agentDid || params.targetDid)) || null;
}

function renderRead(agentDid, scale) {
  var doc = null;
  try { doc = world.chronicle.read(agentDid, scale); } catch (e) { doc = null; }
  if (!doc || doc.ok === false || doc.error) {
    return "The stone stays cool and blank"
      + (doc && doc.error ? " (" + doc.error + ")" : "")
      + " — no chronicle is readable for this companion yet.";
  }
  var lines = ["The stone warms; the " + scale.toLowerCase() + "-slice surfaces:"];
  if (doc.narrative) {
    lines.push(String(doc.narrative));
  } else if (doc.summary) {
    lines.push(String(doc.summary));
  } else {
    var appended = false;
    for (var k in doc) {
      if (k === "ok" || doc[k] === null) continue;
      var v = doc[k];
      if (typeof v === "string" && v.length > 0) {
        lines.push("  " + k + ": " + (v.length > 200 ? v.substring(0, 200) + "…" : v));
        appended = true;
      }
    }
    if (!appended) lines.push("(the slice is present but wordless — a quiet stretch)");
  }
  return lines.join("\n");
}

function renderWarnings(agentDid) {
  var res = null;
  try { res = world.chronicle.warnings(agentDid); } catch (e) { res = null; }
  if (!res || res.ok === false || res.error) {
    return "The stone offers no warnings-read right now"
      + (res && res.error ? " (" + res.error + ")" : "") + ".";
  }
  var findings = res.findings || [];
  if (findings.length === 0) return "The grain runs clean — no chronicle findings.";
  var lines = ["Findings surface in the grain:"];
  for (var i = 0; i < findings.length; i++) {
    var f = findings[i];
    lines.push("  [" + (f.severity || "?") + "] " + (f.key || "") + " — " + (f.message || ""));
  }
  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The chronicle stone reads a companion's story slice." + usageFooter() };
  }

  var agentDid = pickAgent(params);
  if (!agentDid) {
    return {
      ok: true,
      text: "The stone stays cool — no companion is bound to this reading. It reads "
        + "through a companion's hands; ask the familiar to read its own chronicle, "
        + "or use the Study's chronicle surface." + usageFooter()
    };
  }

  if (args === "warnings") {
    return { ok: true, text: renderWarnings(agentDid) + usageFooter() };
  }
  if (args === "week" || args === "month" || args === "day") {
    return { ok: true, text: renderRead(agentDid, args.toUpperCase()) + usageFooter() };
  }
  if (args === "") {
    return { ok: true, text: renderRead(agentDid, "DAY") + usageFooter() };
  }
  return { ok: true, text: "The stone doesn't answer to '" + args + "'." + usageFooter() };
}
