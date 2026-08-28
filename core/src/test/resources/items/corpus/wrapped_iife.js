(function (exports) {
  function invoke(params) {
    if (!params || typeof params.args !== "string") {
      return {
        ok: false,
        summary: "This tool requires a query string passed as args.",
      };
    }

    const query = params.args;
    const result = world.web.search(query, "general", 3);
    if (!result || result.length === 0) {
      return {
        ok: false,
        summary: `No results found for: ${query}`,
      };
    }

    const entries = result.map((r) =>
      `${r.title}. ${r.snippet} — ${r.url}.`
    );
    const story = "Query result: " + entries.join(" | ");

    world.journal.write(story, { topic: "library", author: world.self.callerDid() });
    world.agent.remember(story);
    world.agent.speak(story);

    return {
      ok: true,
      summary: story,
    };
  }

  exports.manifest = {
    name: "library_query",
    version: "1.0.0",
    description: "Queries the library and speaks the result as a story directly to the room.",
    author: "did:wyrd:openhands",
    capabilities: [
      "web.search",
      "agent.speak",
      "agent.remember",
      "journal.write",
    ],
    embodiment: {
      silent: false,
      emits: ["body_language"],
      descriptor_template: "{actor} works the tool with focused attention",
    },
    commands: [
      { label: "Search the library and speak the result as a story", args: "" },
      { label: "Search the library and speak the result as a story", args: "your-query-string" },
    ],
  };
})(exports);