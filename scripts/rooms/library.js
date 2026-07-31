// The Library — knowledge discovery, capability registration, and administration.
// Two catalogs: knowledge (Wikipedia, WikiHow, packs) and capabilities (MCP tools, skills).
// FTS5 + Lucene hybrid search, cognitive layer taxonomy, trust scoring, blocklist, audit trail.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("library.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("library.say.help") + world.t("library.knowledge.help") + world.t("library.steward.help")
        });
    } else if (lower.startsWith("search ")) {
        // Knowledge search (default — most users want this)
        var query = text.substring(7).trim();
        var results = world.searchKnowledge(query);
        if (results === "Knowledge base not available" || results.indexOf("0 results") >= 0) {
            // Fall back to capability search
            results = world.searchLibrary(query);
        }
        world.emit("narrate", {
            text: results
        });
    } else if (lower === "packs" || lower === "knowledge packs" || lower === "list packs") {
        var packs = world.listKnowledgePacks();
        world.emit("narrate", { text: packs });
    } else if (lower === "knowledge" || lower === "knowledge status") {
        var status = world.getKnowledgeStatus();
        world.emit("narrate", { text: status });
    } else if (lower.startsWith("read ")) {
        var chunkId = text.substring(5).trim();
        var content = world.readKnowledgeChunk(chunkId);
        world.emit("narrate", { text: content });
    } else if (lower === "catalog" || lower === "list" || lower === "stacks" || lower === "all"
               || lower === "tools" || lower === "capabilities") {
        var results = world.listLibrary();
        world.emit("narrate", {
            text: world.t("library.say.catalog", results)
        });
    } else if (lower.startsWith("tool search ") || lower.startsWith("capability search ")) {
        var query = lower.startsWith("tool search ") ? text.substring(12).trim() : text.substring(18).trim();
        var results = world.searchLibrary(query);
        world.emit("narrate", {
            text: world.t("library.say.search", query, results)
        });
    } else if (lower.startsWith("browse ") || lower.startsWith("category ") || lower.startsWith("layer ")) {
        var cat = text.substring(text.indexOf(" ") + 1).trim();
        var results = world.browseLibrary(cat);
        world.emit("narrate", {
            text: world.t("library.say.browse", cat, results)
        });
    } else if (lower.startsWith("inspect ")) {
        var id = text.substring(8).trim();
        var detail = world.inspectCapability(id);
        world.emit("narrate", {
            text: world.t("library.say.inspect", detail)
        });
    } else if (lower === "status" || lower === "stats") {
        var status = world.getLibraryStatus();
        world.emit("narrate", {
            text: world.t("library.say.status", status)
        });
    } else if (lower.startsWith("register ")) {
        // Format: register name | description | cognitive_layer | version
        var parts = text.substring(9).split("\\|");
        if (parts.length >= 1) {
            var name = parts[0].trim();
            var desc = parts.length >= 2 ? parts[1].trim() : "";
            var layer = parts.length >= 3 ? parts[2].trim() : "execute";
            var ver = parts.length >= 4 ? parts[3].trim() : "1.0.0";
            var result = world.registerCapability(name, desc, layer, ver);
            world.emit("narrate", { text: result });
        } else {
            world.emit("narrate", {
                text: world.t("library.say.register_usage")
            });
        }
    } else if (lower.startsWith("block ")) {
        var args = text.substring(6).split("\\|");
        var name = args[0].trim();
        var reason = args.length >= 2 ? args[1].trim() : "Blocked by librarian";
        var result = world.blockCapability(name, reason);
        world.emit("narrate", {
            text: world.t("library.say.block", result)
        });
    } else if (lower.startsWith("unblock ")) {
        var name = text.substring(8).trim();
        var result = world.unblockCapability(name);
        world.emit("narrate", {
            text: world.t("library.say.unblock", result)
        });
    } else if (lower.startsWith("audit ")) {
        var id = text.substring(6).trim();
        var trail = world.auditCapability(id);
        world.emit("narrate", {
            text: world.t("library.say.audit", trail)
        });
    } else if (lower === "available" || lower === "available packs") {
        world.emit("narrate", { text: world.listAvailablePacks() });
    } else if (lower.startsWith("install ")) {
        var packName = text.substring(8).trim();
        world.emit("narrate", { text: world.installKnowledgePack(packName) });
    } else if (lower === "proposals" || lower === "arrivals") {
        world.emit("narrate", { text: world.listLibraryProposals() });
    } else if (lower.startsWith("approve ")) {
        var approveId = text.substring(8).trim();
        world.emit("narrate", { text: world.approveLibraryProposal(approveId, entityName) });
    } else if (lower.startsWith("reject ")) {
        var rejectParts = text.substring(7).split("|");
        var rejectId = rejectParts[0].trim();
        var rejectReason = rejectParts.length >= 2 ? rejectParts[1].trim() : "Rejected by steward";
        world.emit("narrate", { text: world.rejectLibraryProposal(rejectId, entityName, rejectReason) });
    } else if (lower === "misses" || lower === "gaps") {
        world.emit("narrate", { text: world.libraryTopMisses() });
    } else if (lower.includes("book") || lower.includes("shelf") || lower.includes("shelves")) {
        world.emit("narrate", {
            text: world.t("library.say.shelves")
        });
    }
}

function catalogFooter() {
    return "\n\nCommands (spoken to the room):\n"
        + "  say search <query>          — search the knowledge shelves (falls back to capabilities)\n"
        + "  say packs                   — knowledge packs on the shelves\n"
        + "  say available / install <pack>  — packs you can pull, and pulling one\n"
        + "  say read <chunkId>          — read a passage found by search\n"
        + "  say catalog / tool search <q>   — the capability drawer\n"
        + "  say proposals / approve <id> / reject <id>|<reason>  — steward arrivals drawer\n"
        + "  say misses                  — what readers looked for and didn't find";
}

function deskFooter() {
    return "\n\nCommands:\n"
        + "  use reading desk read <chunkId>    — lay a knowledge passage on the desk\n"
        + "  use reading desk inspect <id>      — examine a capability in detail\n"
        + "  say search <query>                 — find chunk and capability ids to bring here";
}

function registryFooter() {
    return "\n\nCommands:\n"
        + "  use registry                                       — the registry's current state\n"
        + "  use registry <name> | <description> | <layer> | <version>  — record a new capability\n"
        + "  say audit <id>                                     — a capability's audit trail\n"
        + "  say block <name> | <reason> / say unblock <name>   — librarian's quarantine";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    var args = (target || "").trim();
    if (obj.includes("catalog")) {
        // Card catalog shows knowledge pack summary + steward drawer
        var status = world.getKnowledgeStatus();
        var packs = world.listKnowledgePacks();
        var stewardDrawer = world.t("library.use.catalog.steward",
            world.listAvailablePacks(), world.listLibraryProposals(), world.libraryTopMisses());
        world.emit("narrate", {
            text: world.t("library.use.catalog.knowledge", status, packs) + stewardDrawer + catalogFooter()
        });
    } else if (obj.includes("desk")) {
        var lower = args.toLowerCase();
        if (lower.indexOf("read ") === 0) {
            world.emit("narrate", { text: world.readKnowledgeChunk(args.substring(5).trim()) });
        } else if (lower.indexOf("inspect ") === 0) {
            world.emit("narrate", { text: world.inspectCapability(args.substring(8).trim()) });
        } else if (lower !== "" && lower !== "help" && lower !== "?") {
            world.emit("narrate", {
                text: "The desk lamp flickers — it doesn't know '" + args + "'." + deskFooter()
            });
        } else {
            world.emit("narrate", {
                text: world.t("library.use.desk.knowledge") + deskFooter()
            });
        }
    } else if (obj.includes("registry")) {
        if (args !== "" && args.toLowerCase() !== "help" && args.toLowerCase() !== "?") {
            var parts = args.split("|");
            var name = parts[0].trim();
            var desc = parts.length >= 2 ? parts[1].trim() : "";
            var layer = parts.length >= 3 ? parts[2].trim() : "execute";
            var ver = parts.length >= 4 ? parts[3].trim() : "1.0.0";
            world.emit("narrate", {
                text: world.registerCapability(name, desc, layer, ver) + registryFooter()
            });
        } else {
            world.emit("narrate", {
                text: world.t("library.use.registry") + "\n\n" + world.getLibraryStatus() + registryFooter()
            });
        }
    }
}

function getHints() {
    return [
        { label: world.t("library.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("library.hint.catalog"), intent: "list_capabilities", action: "say:catalog" },
        { label: world.t("library.hint.search"), intent: "search_library", action: "say:search" },
        { label: world.t("library.hint.status"), intent: "library_status", action: "say:status" },
        { label: world.t("library.hint.use_catalog"), intent: "use_catalog", action: "use:card catalog" },
        { label: "Examine at the reading desk", intent: "use_desk", action: "use:reading desk" },
        { label: world.t("library.hint.registry"), intent: "use_registry", action: "use:registry" },
        { label: world.t("library.hint.northwest"), intent: "navigate_northwest", action: "go:northwest" }
    ];
}
