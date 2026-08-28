// library_keeper: a scripted item that queries the library and speaks a story.
exports.manifest = {
  name: "library_keeper",
  version: "1.0.0",
  description: "Queries the library for content and speaks a 2-paragraph story about what it finds.",
  author: "did:wyrd:openhands",
  capabilities: ["library.search", "web.search", "agent.speak", "room.emit"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} works the tool with focused attention"
  },
  commands: [
    { label: "Query the library and speak a story", args: "" },
    { label: "Query with specific topic", args: "topic" },
    { label: "Show what the tool does", args: "details" }
  ]
};

function invoke(params) {
  const args = (params.args || "")
    .trim()
    .replace(/^"|"$/g, "")       // strip surrounding quotes if present
    .replace(/^'/, "");           // strip leading single quote

  if (args === "") {
    const query = "what is in the library right now";
    const libraryResults = world.library.search(query, 20);

    if (!libraryResults || libraryResults.length === 0) {
      const summary = `The library search returned no results for "${query}". There is nothing to speak about at this moment.`;
      world.agent.speak(summary);
      return { ok: true, summary };
    }

    const firstFive = libraryResults.slice(0, 5);
    const stories = firstFive.map(function (item) {
      const story = world.llm.summarize(item.text || item.title, "write a short paragraph about this item in a conversational tone");
      return story;
    });

    const paragraphs = [];
    for (var i = 0; i < stories.length; i++) {
      paragraphs.push(stories[i]);
    }

    const story = paragraphs.join(" ");
    const summary = `I searched the library and found ${libraryResults.length} items. Here is what I found: ${story}`;
    world.agent.speak(summary);
    return { ok: true, summary };
  }

  if (args === "details") {
    return { ok: true, summary: "This tool searches the library for content and speaks a story about what it finds. Pass an empty query to search broadly, or pass a topic string to focus the search." };
  }

  const topic = args.trim();
  const query = topic === "" ? "what is in the library right now" : topic;
  const libraryResults = world.library.search(query, 20);

  if (!libraryResults || libraryResults.length === 0) {
    const summary = `I searched the library for "${query}" and found nothing. The library is quiet on that topic right now.`;
    world.agent.speak(summary);
    return { ok: true, summary };
  }

  const firstFive = libraryResults.slice(0, 5);
  const stories = firstFive.map(function (item) {
    const story = world.llm.summarize(item.text || item.title, "write a short paragraph about this item in a conversational tone");
    return story;
  });

  const paragraphs = [];
  for (var i = 0; i < stories.length; i++) {
    paragraphs.push(stories[i]);
  }

  const story = paragraphs.join(" ");
  const summary = `I searched the library for "${query}" and found ${libraryResults.length} items. Here is what I found: ${story}`;
  world.agent.speak(summary);
  return { ok: true, summary };
}
