// Phase Q sample item.
//
// research_assistant — pairs an arXiv search with Wikipedia background and
// drops a structured note into the holder's journal. Demonstrates the
// Phase Q knowledge adapters end-to-end without touching paid services.
//
// Items-as-tools contract — manifest converted from the legacy
// `const MANIFEST = {...}` literal (which ItemManifestParser cannot see) to
// the canonical exports-header shape the loader parses.
// (NB: this comment must not spell out the assignment pattern itself —
// ItemManifestParser matches the first occurrence in the file head.)

exports.manifest = {
    name: "research_assistant",
    version: "1.0.0",
    description: "Research a topic across arXiv + Wikipedia and journal a structured summary.",
    author: "did:wyrdsekai:foundation",
    capabilities: [
        "arxiv.read",
        "wikipedia.read",
        "journal.write",
        "embed.similarity"
    ],
    embodiment: {
      silent: false,
      emits: ["ambient_shift"],
      descriptor_template: "Pages turn quietly; a summary accretes in the margin — sources, synthesis, the shape of an answer."
    },
    rate_limits: {
        "arxiv.search":     { per_minute: 6 },
        "wikipedia.summary": { per_minute: 30 }
    },
    external_domains: [
        "export.arxiv.org",
        "arxiv.org",
        "*.wikipedia.org",
        "wikipedia.org"
    ],
    data_sensitivity: "low",
    // The invoke() entrypoint takes the research topic from
    // params.topic / params.args / params.text; a bare invoke explains
    // that a topic is needed.
    // The schema the MODEL sees. This item reads `topic` and never reads `query`, so the
    // free-form-`query`-only schema left it with nothing to research on every model call.
    params: [
        { name: "topic", type: "string", required: true,
          description: "What to research, e.g. \"liquid neural networks\". Take it from "
                     + "what the user asked about." }
    ],
    commands: [
        { label: "Research a topic and journal a summary", args: "" }
    ]
};

// Action handler. Triggered by `use research_assistant on <topic>` or
// equivalent inventory action.
function onUse(args) {
    const topic = (args && args.topic) ? String(args.topic) : null;
    if (!topic || topic.trim().length === 0) {
        return { ok: false, error: "missing topic — pass {topic: \"what to research\"}" };
    }

    // Honesty wrap (cost_ledger pattern) — world.time may not be wired in
    // every host; degrade to a blank timestamp rather than throwing.
    var timestamp = null;
    try { timestamp = world.time.iso(); } catch (e) { timestamp = null; }

    const results = {
        topic: topic,
        timestamp: timestamp,
        sources: []
    };

    // Wikipedia summary — fast, free, gives a one-paragraph context anchor.
    try {
        const summary = world.wikipedia.summary({ title: topic });
        if (summary && summary.success && summary.data) {
            results.background = {
                title: summary.data.title,
                extract: summary.data.extract,
                url: summary.data.content_urls
                    ? summary.data.content_urls.desktop && summary.data.content_urls.desktop.page
                    : null
            };
            results.sources.push("wikipedia");
        }
    } catch (e) {
        results.wiki_error = String(e);
    }

    // arXiv — cap to 5 papers so the journal note stays readable.
    try {
        const papers = world.arxiv.search({ query: topic, max: 5 });
        if (papers && papers.success) {
            results.papers = papers.data;
            results.sources.push("arxiv");
        }
    } catch (e) {
        results.arxiv_error = String(e);
    }

    // Drop a structured note into the journal. journal.write expects
    // { title, body } — body is plaintext, so we pretty-print the JSON.
    try {
        const title = "Research notes — " + topic;
        const body = "Topic: " + topic + "\n"
            + "Searched: " + results.timestamp + "\n"
            + "Sources: " + results.sources.join(", ") + "\n\n"
            + (results.background
                ? "Background (Wikipedia):\n" + results.background.extract + "\n\n"
                : "")
            + (results.papers
                ? "Papers (arXiv):\n" + world.json.stringify(results.papers).substring(0, 2000)
                : "");
        world.journal.write({ title: title, body: body });
        results.journaled = true;
    } catch (e) {
        results.journal_error = String(e);
    }

    return { ok: true, result: results };
}

// Items-as-tools contract — invoke() entrypoint. Thin wrapper over the
// existing onUse handler: derives the topic from the structured param or
// the menu args string and reports honest usage when none is given.
function invoke(params) {
    var topic = (params && (params.topic || params.args || params.text)) || "";
    topic = String(topic).trim();
    if (!topic) {
        return {
            ok: false,
            error: "missing topic — pass {topic: \"what to research\"}",
            message: "The research assistant needs a topic: `use research_assistant <topic>`."
        };
    }
    return onUse({ topic: topic });
}

exports.invoke = invoke;
