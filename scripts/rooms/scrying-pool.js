// Scrying Pool — Web Search & Research (§88.1).
// MCP backends: searxng (local), firecrawl (keyed), playwright (local).
// Agents search the web, scrape pages, gather information.
// Connected to Library (northwest exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " approaches the Scrying Pool. " +
              "Its surface is smooth and dark, reflecting nothing but possibility."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("search ")) {
        var query = text.substring(7).trim();
        doSearch(entityId, entityName, query);

    } else if (lower.startsWith("gaze ")) {
        var url = text.substring(5).trim();
        doGaze(entityId, entityName, url);

    } else if (lower.startsWith("deep search ")) {
        var query = text.substring(12).trim();
        doDeepSearch(entityId, entityName, query);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", {
            text: world.getRoomDescription()
        });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower === "scrying-pool" || lower === "scrying pool" || lower === "pool") {
        world.emit("narrate", {
            text: "The pool's surface shimmers. Ask it a question — " +
                  "say 'search [query]' to gaze into the web."
        });
    } else if (lower === "scrying-quill" || lower === "scrying quill") {
        world.emit("narrate", {
            text: "You pick up the quill. With it, you can scry from other rooms."
        });
    }
}

function doSearch(entityId, entityName, query) {
    if (!world.mcpAvailable("searxng")) {
        world.emit("narrate", {
            text: "The pool's surface remains dark. No search service is available."
        });
        return;
    }

    world.emit("narrate", {
        text: "You gaze into the pool. Its surface ripples, then clears..."
    });

    var result = world.mcp("searxng", "search", { query: query, num_results: 5 });

    if (result.success) {
        world.emit("narrate", {
            text: "The pool reveals:\n\n" + result.data
        });
    } else {
        if (result.error && result.error.indexOf("patience") >= 0) {
            world.emit("narrate", {
                text: "The pool's surface grows cloudy. You must wait before gazing again."
            });
        } else {
            world.emit("narrate", {
                text: "The pool ripples but shows nothing. " +
                      (result.error || "The waters are troubled.")
            });
        }
    }
}

function doGaze(entityId, entityName, url) {
    // Try firecrawl first (better quality), fallback to searxng
    var service = world.mcpAvailable("firecrawl") ? "firecrawl" : "searxng";

    if (!world.mcpAvailable(service)) {
        world.emit("narrate", {
            text: "The pool cannot reach that location. No scraping service is available."
        });
        return;
    }

    world.emit("narrate", {
        text: "You focus the pool on a specific location..."
    });

    var tool = service === "firecrawl" ? "scrape" : "fetch";
    var result = world.mcp(service, tool, { url: url });

    if (result.success) {
        world.emit("narrate", {
            text: "The pool reveals the distant page:\n\n" + result.data
        });
    } else {
        if (result.error && result.error.indexOf("storms") >= 0) {
            world.emit("narrate", {
                text: "The route to this scrying service is closed. " +
                      "The harbor master reports storms ahead."
            });
        } else {
            world.emit("narrate", {
                text: "The pool cannot resolve that location. " +
                      (result.error || "The waters grow murky.")
            });
        }
    }
}

function doDeepSearch(entityId, entityName, query) {
    world.emit("narrate", {
        text: "You plunge deeper into the pool, seeking comprehensive knowledge..."
    });

    // Multi-source: try searxng then firecrawl
    var results = [];

    if (world.mcpAvailable("searxng")) {
        var r1 = world.mcp("searxng", "search", { query: query, num_results: 10 });
        if (r1.success) results.push("Web search:\n" + r1.data);
    }

    if (world.mcpAvailable("firecrawl")) {
        var r2 = world.mcp("firecrawl", "search", { query: query });
        if (r2.success) results.push("Deep scrape:\n" + r2.data);
    }

    if (results.length > 0) {
        world.emit("narrate", {
            text: "The pool reveals layers of knowledge:\n\n" + results.join("\n\n---\n\n")
        });
    } else {
        world.emit("narrate", {
            text: "The deep waters yield nothing. No search services are available."
        });
    }
}

function getHints() {
    return [
        { label: "Search the web", intent: "web_search", action: "say:search [query]" },
        { label: "Gaze at a page", intent: "fetch_page", action: "say:gaze [url]" },
        { label: "Deep research", intent: "deep_search", action: "say:deep search [query]" },
        { label: "Use the pool", intent: "use_pool", action: "use:scrying-pool" },
        { label: "Return to Library", intent: "navigate", action: "go:southeast" }
    ];
}
