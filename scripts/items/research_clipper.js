// research_clipper.js — Phase P heavy-item exemplar.

exports.manifest = {
  name: "research_clipper",
  version: "1.0.0",
  description: "Probe GitHub code search + Hacker News for a topic, summarise locally, write back to journal.",
  author: "did:wyrd:system",
  capabilities: [
    "github.code.search",   // §4.26 read — Tier 4
    "hn.read"               // §4.25 read — Tier 4
  ],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} clips a finding — a small snip, then the page settles into the research stack."
  },
  external_domains: ["api.github.com", "hn.algolia.com", "hacker-news.firebaseio.com"],
  data_sensitivity: "low",
  // Items-as-tools contract — invoke() reads structured params
  // (params.action = "init"/"examine"/default "research", params.topic),
  // not the args string; a bare invoke explains that a topic is needed.
  commands: [
    { label: "Clip research on a topic (needs a topic)", args: "" }
  ]
};

//
// The companion casts research "<topic>" and the script:
//   1) probes GitHub code via world.github.search_code(query)
//   2) probes Hacker News via world.hn.search(query)
//   3) summarises the corpus via world.llm.summarize
//   4) writes the synthesis into the journal via world.agent.remember
//   5) narrates a one-line clip into the room via world.agent.speak
//
// The two adapter calls each return the Phase-P adapter envelope:
//     { success: true, data: [...] }       on success
//     { success: false, error: {...} }     on failure
// The script defends against both shapes — a missing token degrades
// gracefully into a search-only result rather than throwing.

function unwrap(resp) {
  if (!resp) return null;
  if (resp.success === false) return null;
  return resp.data == null ? null : resp.data;
}

function invoke(params) {
  var p = params || {};
  var action = p.action || "research";

  if (action === "init") {
    return {
      ready: true,
      version: "1.0.0",
      actions: ["research", "examine"],
      adapters: ["github", "hn"]
    };
  }

  if (action === "examine") {
    return {
      name: "Research Clipper (Dev Edition)",
      description: "A leather pouch with brass clips. The seam is stitched with two threads, copper and crimson. " +
        "When a topic settles, the clip on the spine pings — once for code, twice for chatter."
    };
  }

  // default action — research
  var topic = (p.topic || "").trim();
  if (!topic) return { error: "missing_topic" };

  // GitHub code search
  var ghResp = world.github.search_code({ query: topic });
  var ghHits = unwrap(ghResp) || [];

  // HackerNews algolia search
  var hnResp = world.hn.search({ query: topic });
  var hnHits = unwrap(hnResp) || [];

  if (ghHits.length === 0 && hnHits.length === 0) {
    return {
      topic: topic,
      summaries: [],
      sources: [],
      note: "no_hits",
      gh_error: ghResp && ghResp.error ? ghResp.error.code : null,
      hn_error: hnResp && hnResp.error ? hnResp.error.code : null
    };
  }

  // Build a corpus the LLM can summarise. Both adapter shapes are normalised
  // into {label, snippet} so the summarisation step doesn't need to branch.
  var corpus = [];
  for (var i = 0; i < Math.min(ghHits.length, 5); i++) {
    corpus.push({
      label: "github:" + (ghHits[i].repo || "") + "/" + (ghHits[i].path || ""),
      snippet: (ghHits[i].fragment || "").substring(0, 800)
    });
  }
  for (var j = 0; j < Math.min(hnHits.length, 5); j++) {
    var h = hnHits[j];
    corpus.push({
      label: "hn:" + (h.id || "") + " — " + (h.title || ""),
      snippet: (h.title || "") + (h.author ? " by " + h.author : "") +
        (h.points ? " (" + h.points + " points)" : "")
    });
  }

  var blob = corpus.map(function(c) { return c.label + "\n" + c.snippet; })
                    .join("\n\n");

  var summary = world.llm.summarize(blob,
    "Summarise what the developer community is currently saying about " + topic +
    ". Cite the GitHub repos and HN threads.");

  // Write back into agent memory (journal stand-in)
  if (summary && summary.length > 0) {
    world.agent.remember("[research_clipper] " + topic + ": " + summary);
  }

  // Narration
  var clipCount = corpus.length;
  world.agent.speak("Clipped " + clipCount + " research notes on " + topic +
    " — " + ghHits.length + " from code, " + hnHits.length + " from chatter.");

  return {
    topic: topic,
    summary: summary,
    github_hits: ghHits.length,
    hn_hits: hnHits.length,
    sources: corpus.map(function(c) { return c.label; })
  };
}
