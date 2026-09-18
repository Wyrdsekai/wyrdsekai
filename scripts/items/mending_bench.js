// The mending bench. Things that were made for a companion and do not work are hers to know
// about and hers to take to the workshop.
//
// Verbs:
//   use mending bench                 — what is broken, in plain words
//   use mending bench mend <name>     — hand one to the workshop
//
// Host bridge surfaces consumed (wired via ItemWorldApi):
//   world.workshop.broken()           — read-only
//   world.workshop.mend(name)         — cap "workshop.mend"; returns at once, a mark says how it went
exports.manifest = {
  name: "mending_bench",
  version: "1.0.0",
  description: "Lists the household's items that do not work and hands one to the workshop to be mended.",
  author: "did:wyrd:system",
  capabilities: ["workshop.mend"],
  rate_limits: { "workshop.mend": "6/hour" },
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} lays the thing on the bench and looks it over"
  },
  commands: [
    { label: "See what is broken", args: "" },
    { label: "Hand one to the workshop", args: "mend <name>" }
  ]
};

function invoke(params) {
  var args = String(params.args || "").trim();
  var parts = args.split(/\s+/);
  if (parts[0] === "mend") {
    var name = parts.slice(1).join("_").toLowerCase();
    if (!name) { return { ok: false, text: "Mend which? `use mending bench` lists what is broken." }; }
    var r = world.workshop.mend(name);
    return { ok: !!r.ok, text: r.ok ? r.text : ("The bench has nothing to do: " + r.error + ".") };
  }
  var broken = world.workshop.broken();
  if (!broken || broken.length === 0) { return { ok: true, text: "Nothing on the bench. Everything that was made for this house works." }; }
  var lines = [];
  for (var i = 0; i < broken.length; i++) { lines.push("- " + broken[i].item + ": " + broken[i].what); }
  return { ok: true, text: "What does not work yet:\n" + lines.join("\n") + "\nTo mend one: `use mending bench mend <name>`." };
}
