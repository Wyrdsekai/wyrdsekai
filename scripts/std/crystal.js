// std/crystal.js — World observation (read-only sensing).
// A crystal queries a data source and returns observations. No side effects.
// Creator configures: source ("zone", "oracle", "weather", "metrics", custom).
// Override: query() for custom data sources.

item._type = "crystal";
item._source = "zone";
item._label = "crystal";
item._refresh_mode = "on_use";  // "on_use" or "periodic"
item._last_data = null;

item.set_source = function(s) { item._source = s; };
item.set_label = function(l) { item._label = l; };
item.set_refresh = function(mode) { item._refresh_mode = mode; };

function invoke(params) {
    var topic = params.topic || params.query || item._source;

    if (item._source === "oracle") {
        var predictions = world.oracle.query(topic);
        return { source: "oracle", topic: topic, observations: predictions };
    }

    if (item._source === "zone" || item._source === "metrics") {
        // Zone crystals use oracle for pattern data
        var patterns = world.oracle.query(topic, "patterns");
        return { source: item._source, topic: topic, observations: patterns };
    }

    if (item._source === "weather" || item._source === "web") {
        // External data via web search
        var results = world.web.search(topic + " current data");
        var snippets = [];
        var count = Math.min(results.length, 3);
        for (var i = 0; i < count; i++) {
            snippets.push(results[i].snippet || results[i].title);
        }
        return { source: item._source, topic: topic, observations: snippets };
    }

    // Custom source — summarize from library
    var knowledge = world.library.search(topic, 5);
    var texts = [];
    for (var i = 0; i < knowledge.length; i++) {
        texts.push(knowledge[i].title + ": " + (knowledge[i].text || ""));
    }
    if (texts.length > 0) {
        var summary = world.llm.summarize(texts.join("\n"), "Summarize observations about: " + topic);
        return { source: item._source, topic: topic, summary: summary };
    }
    return { source: item._source, topic: topic, observations: [], note: "No data found" };
}
