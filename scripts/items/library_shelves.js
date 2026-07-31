// CodePlane Workshop furnishing — library shelves ("workshop-library-shelves"
// RoomObject, display name "library shelves" → normalized linkage
// "library_shelves").
//
// The workshop's knowledge wall: framework knowledge, project conventions,
// coding DNA. Read surface is world.library.search() / world.library.read()
// — the same Library index the reading desk queries. Bare `use library
// shelves` explains itself and lists the exact commands.
//
// Per-project compartments materialize as project portals are linked
// ( — follow-on); until then the shelves
// search the whole Library honestly rather than pretending compartments.
exports.manifest = {
  name: "library_shelves",
  version: "1.0.0",
  description: "Wall shelves of framework knowledge and coding DNA — searchable compartments backed by the household Library.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} runs a hand along the shelf-compartments, spines of framework-lore shifting under the touch."
  },
  commands: [
    { label: "Read the shelves", args: "" },
    { label: "Search the shelves", args: "search <query>" },
    { label: "Read one chunk", args: "read <chunkId>" },
    { label: "Shelves help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use library shelves                  — this overview",
    "  use library shelves search <query>   — search the household Library",
    "  use library shelves read <chunkId>   — read one indexed chunk in full",
    "  use library shelves help             — this help",
    "Per-project compartments arrive with project portals (§6.5, not yet wired);",
    "for now every search spans the whole Library index."
  ].join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "" || lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "The shelves hold what the household Library has indexed — frameworks, "
        + "conventions, accumulated coding DNA." + usageFooter()
    };
  }

  try {
    if (lower.indexOf("search ") === 0) {
      var q = raw.substring(7).trim();
      var hits = world.library.search(q, 8);
      if (!hits || hits.length === 0) {
        return {
          ok: true,
          text: "The compartments come up empty for '" + q + "' — nothing indexed "
            + "matches, or the Library isn't bound on this surface." + usageFooter()
        };
      }
      var lines = ["The shelves surface " + hits.length + " match(es) for '" + q + "':"];
      for (var i = 0; i < hits.length; i++) {
        var h = hits[i];
        var line = "  " + (h.title || h.chunkId || h.id || "?");
        if (h.snippet || h.text) {
          var body = String(h.snippet || h.text);
          line += " — " + (body.length > 100 ? body.substring(0, 100) + "…" : body);
        }
        lines.push(line);
        if (h.chunkId || h.id) lines.push("     chunk: " + (h.chunkId || h.id));
      }
      return { ok: true, text: lines.join("\n") + usageFooter(), hits: hits };
    }

    if (lower.indexOf("read ") === 0) {
      var chunkId = raw.substring(5).trim();
      var chunk = world.library.read(chunkId);
      if (!chunk || chunk.error) {
        return {
          ok: true,
          text: "No volume on these shelves answers to chunk '" + chunkId + "'." + usageFooter()
        };
      }
      var out = [];
      if (chunk.title) out.push(chunk.title);
      out.push(String(chunk.text || chunk.content || "(the page is blank)"));
      return { ok: true, text: out.join("\n\n") + usageFooter(), chunk: chunk };
    }
  } catch (e) {
    return {
      ok: true,
      text: "The shelf-runes dim — the Library index isn't reachable from this "
        + "surface right now." + usageFooter()
    };
  }

  return { ok: true, text: "The shelves have no compartment labeled '" + raw + "'." + usageFooter() };
}
