// The Atrium — civic hall for browsing the zone directory.
// (Discovery UX, in-world).
//
// The room listens for spoken queries against the directory board + search
// crystal + tag wheel + recent ledger. Agents are intentionally ignored so
// the room stays a human-facing tool. All user-visible strings flow through
// world.t() for i18n (en/es/ja).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", { text: world.t("atrium.enter", entityName) });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", { text: world.t("atrium.say.help") });
        return;
    }

    if (lower === "recent" || lower === "what's here" || lower === "browse"
            || lower === "who" || lower === "who's here") {
        renderList(world.t("atrium.list.title_recent"),
            world.discoverZones("recent", "10"));
        return;
    }

    if (lower.startsWith("tag ")) {
        var tag = text.substring(4).trim();
        if (tag.length === 0) {
            world.emit("narrate", { text: world.t("atrium.say.need_tag") });
            return;
        }
        renderList(world.t("atrium.list.title_tag", tag),
            world.discoverZones("tag:" + tag, "20"));
        return;
    }

    if (lower.startsWith("capability ")) {
        var cap = text.substring(11).trim();
        if (cap.length === 0) {
            world.emit("narrate", { text: world.t("atrium.say.need_capability") });
            return;
        }
        renderList(world.t("atrium.list.title_capability", cap),
            world.discoverZones("capability:" + cap, "20"));
        return;
    }

    if (lower.startsWith("search ")) {
        var q = text.substring(7).trim();
        if (q.length === 0) {
            world.emit("narrate", { text: world.t("atrium.say.need_query") });
            return;
        }
        // The directory interface doesn't do hybrid search in-world yet —
        // that lives behind rendezvous /api/directory/search. Narrate a
        // pointer rather than silently returning nothing.
        world.emit("narrate", { text: world.t("atrium.say.search_pointer", q) });
        return;
    }

    // Fall-through: subtle hint rather than silence.
    world.emit("narrate", { text: world.t("atrium.say.unknown") });
}

function onUse(entityId, objectName, target) {
    if (world.isAgent(entityId)) return;
    var obj = (objectName || "").toLowerCase();
    var arg = (target || "").trim();

    if (obj === "directory board" || obj === "board") {
        if (arg.length > 0) {
            inspectCard(arg);
            return;
        }
        renderList(world.t("atrium.list.title_board"),
            world.discoverZones("recent", "10"));
        world.emit("narrate", {
            text: "Commands:\n"
                + "  use directory board            — browse the freshest manifest cards\n"
                + "  use directory board <label>    — inspect one zone's card (alias, label, or DID)\n"
                + "  say 'tag <name>' / 'capability <name>' / 'recent' — filtered views"
        });
        return;
    }
    if (obj === "recent ledger" || obj === "ledger") {
        renderList(world.t("atrium.list.title_ledger"),
            world.discoverZones("recent", "15"));
        world.emit("narrate", {
            text: "Commands:\n"
                + "  use recent ledger   — the living index of recently-refreshed zones\n"
                + "  say 'recent'        — same view, spoken"
        });
        return;
    }
    if (obj === "tag wheel" || obj === "wheel") {
        if (arg.length > 0) {
            renderList(world.t("atrium.list.title_tag", arg),
                world.discoverZones("tag:" + arg, "20"));
            return;
        }
        world.emit("narrate", {
            text: world.t("atrium.use.wheel") + "\n\nCommands:\n"
                + "  use tag wheel <tag>   — surface zones advertising that tag\n"
                + "  say 'tag <name>'      — same, spoken"
        });
        return;
    }
    if (obj === "search crystal" || obj === "crystal") {
        if (arg.length > 0) {
            // Honest: hybrid/semantic search lives behind the rendezvous
            // /api/directory/search — the in-world directory interface
            // degrades to a no-op for search. Point at the real paths.
            world.emit("narrate", { text: world.t("atrium.say.search_pointer", arg) });
            return;
        }
        world.emit("narrate", {
            text: world.t("atrium.use.crystal") + "\n\nCommands:\n"
                + "  use search crystal <phrase>   — semantic search (needs a configured rendezvous;\n"
                + "                                  otherwise the crystal points you to tag/capability search)\n"
                + "  say 'tag <name>' / 'capability <name>' — keyword search that works today"
        });
        return;
    }
}

// Inspect a single zone card via the naming service (ungated pure lookup).
function inspectCard(label) {
    var resolvedStr = world.resolveZone(label);
    var resolved;
    try {
        resolved = JSON.parse(resolvedStr);
    } catch (e) {
        world.emit("narrate", { text: resolvedStr });
        return;
    }
    if (!resolved.ok) {
        world.emit("narrate", {
            text: "No card on the board answers to '" + label + "'. "
                + (resolved.message || "") + "\nTry 'recent' to see who is out there."
        });
        return;
    }
    world.emit("narrate", {
        text: "You lift the card for '" + resolved.label + "' from the board:\n"
            + "  label:       " + resolved.label + "\n"
            + "  canonical:   " + resolved.canonical + "\n"
            + "  fingerprint: " + (resolved.fingerprint || "?") + "\n\n"
            + world.t("atrium.list.suffix")
    });
}

function getHints() {
    return [
        { label: "Browse the directory board", intent: "use_board", action: "use:directory board" },
        { label: "Consult the search crystal", intent: "use_crystal", action: "use:search crystal" },
        { label: "Open the recent ledger", intent: "use_ledger", action: "use:recent ledger" },
        { label: "Turn the tag wheel", intent: "use_wheel", action: "use:tag wheel" },
        { label: "See recent zones", intent: "browse_recent", action: "say:recent" }
    ];
}

// --- helpers ---

function renderList(title, resultsJson) {
    var results;
    try { results = JSON.parse(resultsJson); }
    catch (e) { results = []; }

    if (!results || results.length === 0) {
        world.emit("narrate", { text: world.t("atrium.list.empty", title) });
        return;
    }

    var lines = [title + " (" + results.length + "):"];
    for (var i = 0; i < results.length; i++) {
        var m = results[i];
        var icon = m.icon ? (m.icon + " ") : "";
        var display = m.displayName || m.zoneLabel || "?";
        var tagline = m.tagline ? (" — " + m.tagline) : "";
        lines.push("  " + (i + 1) + ". " + icon + display + " [" + m.zoneLabel + "]" + tagline);
        lines.push("     " + m.did);
    }
    lines.push("");
    lines.push(world.t("atrium.list.suffix"));

    world.emit("narrate", { text: lines.join("\n") });
}
