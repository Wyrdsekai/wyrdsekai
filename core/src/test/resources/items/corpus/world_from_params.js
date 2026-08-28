// A scripted item that queries the library and speaks a story to the room.
exports.manifest = {
  name: "library_speaks",
  version: "1.0.0",
  description: "Queries the library and speaks a story to the room.",
  author: "did:wyrd:openhands",
  capabilities: ["library.search", "web.search", "room.emit"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} works the tool with focused attention"
  },
  commands: [
    { label: "Speak a story from the library", args: "" },
    { label: "Show library search results", args: "details" }
  ]
};

function invoke(params) {
  const { world } = params;
  const args = params.args || "";

  if (!world) {
    return {
      ok: false,
      error: "invoke called outside a Wyrdsekai sandbox.",
      summary: ""
    };
  }

  if (args === "details") {
    // Show library search results for the current topic.
    const searchResult = world.web.search("library", "general", 3);
    const results = searchResult || [];
    const summary = results.length
      ? `${results.length} library entries found.`
      : "No library entries found.";
    return {
      ok: true,
      summary: summary
    };
  }

  // Default action: query the library and speak a story.
  const query = world.web.search("library", "general", 3) || [];
  let story = "";

  if (query.length > 0) {
    // Use the first result as the story source.
    const result = query[0];
    // world.web.fetch returns a STRING, not an object.
    const text = world.web.fetch(result.url, 2000) || "";
    if (text && !text.startsWith("[error]")) {
      // Extract the core story from the fetched text.
      story = text.trim();
    } else if (result.text) {
      // Fall back to the title and snippet if fetch fails.
      story = `${result.title} — ${result.snippet || "no snippet available"}`;
    }
  }

  if (story) {
    const body = `Here is the story I found: ${story}`;
    world.agent.speak(body);
    world.room.emit("library_speaks", { summary: body });
    return {
      ok: true,
      summary: story
    };
  }

  // No story found.
  const emptyMessage = "I searched the library but found no story to share.";
  world.agent.speak(emptyMessage);
  world.room.emit("library_speaks", { empty: true });
  return {
    ok: true,
    summary: emptyMessage
  };
}