// CodeZaiku Workshop furnishing — library shelves ("workshop-library-shelves"
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
  version: "1.1.0",
  description: "Wall shelves of framework knowledge and coding DNA — searchable compartments backed by the household Library.",
  author: "did:wyrd:system",
  capabilities: [],
  // Declaring the slot the script actually reads is what makes it callable.
  // Without this the runtime advertised only `query`, the script read `args`,
  // and EVERY invocation fell through to the help screen below — which the
  // companion then read out loud as though it were an answer (2026-08-07).
  //
  // Declaring a free-form first slot is also the dispatcher's opt-in signal:
  // it may put the person's raw request here when the model supplies nothing
  // usable. Command-style items that declare no params keep the old behaviour,
  // because for them an empty argument is a legitimate default view.
  params: [
    { name: "args", type: "string", required: false,
      description: "What to look for. A plain question is searched against the "
        + "household Library and the books the bondholder has shared — e.g. "
        + "\"vel-shara of Adrun\". Or a sub-command: \"search <query>\", "
        + "\"read <chunkId>\", \"help\"." }
  ],
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

// The person asked a question; BM25 needs a query. "ari, can u look through my
// books/library and tell me what the librarian told kestan about velsharas in snow
// crash?" is a sentence, not a query — the vocative and the please-go-look
// preamble match nothing and drown the two words that matter.
function toQuery(text) {
  var s = String(text || "").trim();
  // Drop a leading vocative: "ari, ..." / "hey ari -- ..."
  s = s.replace(/^\s*(hey|hi|ok|okay|so)?[\s,]*[a-z][a-z0-9_-]{1,20}\s*[,:—-]\s+/i, "");
  // Drop the fetch-request preamble up to the real question.
  s = s.replace(
    /^.*?\b(?:tell me|find out|look up|what|who|when|where|why|how)\b/i,
    function (m) { return /\b(what|who|when|where|why|how)\b$/i.test(m) ? m.slice(m.search(/\b(what|who|when|where|why|how)\b$/i)) : ""; });
  return s.replace(/^[\s,.:;?!-]+/, "").trim() || String(text || "").trim();
}

function invoke(params) {
  // `query` first: that is what the dispatcher actually injects. Reading only
  // args/text/target meant the person's request never arrived.
  var raw = String((params && (params.args || params.query || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "" || lower === "help" || lower === "?") {
    return {
      ok: true,
      help: true,
      text: "The shelves hold what the household Library has indexed — frameworks, "
        + "conventions, accumulated coding DNA." + usageFooter()
    };
  }

  // Anything that isn't a recognised subcommand is a question about the books.
  // Treating it as "no such compartment" is how a shelf full of answers reports
  // that it has none.
  if (lower.indexOf("search ") !== 0 && lower.indexOf("read ") !== 0) {
    raw = "search " + toQuery(raw);
    lower = raw.toLowerCase();
  }

  try {
    if (lower.indexOf("search ") === 0) {
      var q = raw.substring(7).trim();
      var hits = world.library.search(q, 8);
      if (!hits || hits.length === 0) {
        return {
          ok: true,
          query: q,
          text: "Nothing on the shelves matches '" + q + "'."
        };
      }
      // Passages, not a catalogue. The companion has to READ these and answer in
      // her own words, so give her enough of each passage to do that — a 100-char
      // stub plus a chunk id is a machine digest, and the never-silent guard
      // correctly refused to speak it ("raw data I couldn't read as an answer").
      var lines = [];
      for (var i = 0; i < hits.length && i < 5; i++) {
        var h = hits[i];
        var title = h.title || h.pack || "";
        var body = String(h.snippet || h.text || "").replace(/\s+/g, " ").trim();
        if (!body) continue;
        if (body.length > 700) body = body.substring(0, 700) + "…";
        lines.push(title ? ("From " + title + ": " + body) : body);
      }
      if (lines.length === 0) {
        return { ok: true, query: q, text: "Nothing on the shelves matches '" + q + "'." };
      }
      return {
        ok: true,
        query: q,
        passages: lines,
        text: lines.join("\n\n"),
        hits: hits
      };
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
      if (chunk.title) out.push("From " + chunk.title + ":");
      out.push(String(chunk.text || chunk.content || "(the page is blank)"));
      // No usage footer on a real answer — it is speech, not a terminal session.
      return { ok: true, text: out.join("\n\n"), chunk: chunk };
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
