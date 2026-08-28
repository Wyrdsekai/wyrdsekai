// The single CodeZaiku MCP service. CodeZaiku ships ONE stdio MCP server
// (`codezaiku mcp`, JSON-RPC 2.0); the four servers this room used to expect
// -- codeplane-tools / -experiment / -deps / -profiler -- come from a design
// that was abandoned before it was built, so every one of those branches had
// been reporting "offline" since the day it was written.
//
// This string is OUR registry id, not their `serverInfo.name`: upstream says
// the advertised name is informational and already changed once with the
// rename, so matching on it would break again. A steward wires the service in
// `mcp-services.json`:
//
//   { "id": "codezaiku", "name": "CodeZaiku", "transport": "stdio",
//     "endpoint": "codezaiku mcp", "tier": "local", "enabled": true }
//
// Tools that exist: code, fix, explore_and_fix, review, research,
// research_memory, investigate, secure, record_convention, show_conventions,
// job_status. Every one accepts `async: true` and answers with a job id to
// poll via job_status.
var CODEZAIKU_MCP = "codezaiku";

// Workshop — Workbench + CodeZaiku Integration (§88.6).
// Two modes:
//   Workbench: Companion creates lightweight skill items (GraalJS).
//     Validates, tests, packages as SoulItem in FamilyLocker.
//   CodeZaiku: Full software development via CodeZaiku MCP servers.
// Connected to Terminal (east exit).

function onEnter(entityId, entityName, fromDirection) {
    var cpAvail = world.mcpAvailable(CODEZAIKU_MCP);
    var ocAvail = (typeof world.codingBackendAvailable === "function")
        && world.codingBackendAvailable("opencode");
    var ohAvail = (typeof world.codingBackendAvailable === "function")
        && world.codingBackendAvailable("openhands");
    var gAvail = (typeof world.codingBackendAvailable === "function")
        && world.codingBackendAvailable("goose");
    var desc = entityName + " enters the Workshop. " +
        "Tool racks line the walls. A large workbench dominates the center";
    // Build the surface-of-the-workbench clause. Each available backend
    // gets a distinct prop so the narration stays vivid even when several
    // are wired at once. Test needles (OpenHandsE2ETest task9): the
    // OpenHands branch must emit at least one of "openhands" /
    // "OpenHands" / "agent-server" / "sandbox" / "iteration" /
    // "autonomous" so room-narration assertions match.
    var props = [];
    if (cpAvail) {
        props.push("a deep-blue Forge (CodeZaiku, project-scale)");
    }
    if (ocAvail) {
        props.push("a small luminous slate (OpenCode, free, local)");
    }
    if (ohAvail) {
        props.push("a glassy iteration sandbox humming faintly — the " +
                  "OpenHands agent-server, ready for autonomous explore loops");
    }
    if (gAvail) {
        props.push("a brass goose perched on a stand (Goose, provider-pluggable), " +
                  "feathers ticking softly as it waits to weave");
    }
    if (props.length > 0) {
        if (props.length === 1) {
            desc += ". On its surface: " + props[0] + ".";
        } else {
            desc += ". On its surface: " + props.slice(0, -1).join(", ") +
                    ", and " + props[props.length - 1] + ".";
        }
    } else {
        desc += ". The workbench is ready for crafting skill items.";
    }
    world.emit("narrate", { text: desc });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("code ")) {
        var task = text.substring(5).trim();
        doCode(entityId, task);

    } else if (lower.startsWith("explore ")) {
        // SPEC §4.4 / Phase 2c: explore-flavored tasks are the
        // canonical OpenHands case. Route via dispatchCoding with
        // taskType="explore" so the policy script's
        // looksLikeExplore() heuristic promotes OpenHands over the
        // default fallback chain.
        var exploreTask = text.substring(8).trim();
        doExplore(entityId, exploreTask);

    } else if (lower.startsWith("test ")) {
        var target = text.substring(5).trim();
        doTest(entityId, target);

    } else if (lower.startsWith("review ")) {
        var target = text.substring(7).trim();
        doReview(entityId, target);

    } else if (lower.startsWith("experiment ")) {
        doUnbuilt("experiment");

    } else if (lower.startsWith("deps ")) {
        doUnbuilt("dependency");

    } else if (lower.startsWith("profile ")) {
        doUnbuilt("profiling");

    } else if (lower.startsWith("craft ")) {
        var skillName = text.substring(6).trim();
        doCraft(entityId, skillName);

    } else if (lower.startsWith("import ")) {
        var source = text.substring(7).trim();
        doImport(entityId, source);

    } else if (lower.startsWith("collaborate ")) {
        var goal = text.substring(12).trim();
        doCollaborate(entityName || entityId, goal);

    } else if (lower === "skills" || lower === "inventory skills") {
        doListSkills(entityId);

    } else if (lower === "forms" || lower === "list forms") {
        doListForms(entityId);

    } else if (lower.startsWith("shape ")) {
        var formName = text.substring(6).trim();
        doShape(entityId, entityName, formName);

    } else if (lower.startsWith("revise ")) {
        var rformName = text.substring(7).trim();
        doRevise(entityId, entityName, rformName);

    } else if (lower.startsWith("retire ")) {
        var xformName = text.substring(7).trim();
        doRetire(entityId, entityName, xformName);

    } else if (lower === "status" || lower === "board") {
        doStatus(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
        // Surface OpenHands signage on look when the backend is wired.
        // The host's getRoomDescription is a static room blurb; backend
        // availability is dynamic, so we append it here. Test needle
        // (OpenHandsE2ETest task10 step 1): one of "openhands" /
        // "OpenHands" / "agent-server" / "sandbox".
        if (typeof world.codingBackendAvailable === "function"
                && world.codingBackendAvailable("openhands")) {
            world.emit("narrate", {
                text: "Beside the workbench, an OpenHands agent-server " +
                      "sandbox rests under glass — a small iteration loop " +
                      "you can hand multi-file tasks to."
            });
        }
        // Goose signage on look — test needle (GooseE2ETest task10 step1):
        // one of "goose" / "Goose" / "workbench" / "brass".
        if (typeof world.codingBackendAvailable === "function"
                && world.codingBackendAvailable("goose")) {
            world.emit("narrate", {
                text: "A brass goose perches on a stand near the workbench, " +
                      "feathers ticking — the Goose backend, ready to weave " +
                      "multi-file tasks against the local model."
            });
        }
    }
}

// Thought-form authoring primitives for humans at the workbench.
// These emit conversational scaffolding — the agent present in the room
// sees the cue and may author the actual form. Humans aren't authors in
// the formal sense ( validates on originalAuthor DID)
// but the commands provide discoverability and request surface.
function doShape(entityId, entityName, formName) {
    if (!formName) {
        world.emit("narrate", {
            text: "Shape what? Say 'shape <name>' — e.g. 'shape researcher'."
        });
        return;
    }
    world.emit("narrate", {
        text: entityName + " stands at the workbench, considering what a '" + formName + "' form should do. " +
              "If an agent is present, she may author it for you. Describe what the form should accomplish."
    });
}

function doRevise(entityId, entityName, formName) {
    if (!formName) {
        world.emit("narrate", { text: "Revise which? Say 'revise <form-name>'." });
        return;
    }
    world.emit("narrate", {
        text: entityName + " asks the workbench about the '" + formName + "' form. " +
              "If an agent knows that form, she may revise it — tell her what to change."
    });
}

function doRetire(entityId, entityName, formName) {
    if (!formName) {
        world.emit("narrate", { text: "Retire which? Say 'retire <form-name>'." });
        return;
    }
    world.emit("narrate", {
        text: entityName + " suggests retiring '" + formName + "'. " +
              "If an agent owns that form, the decision is hers — retirement is a farewell, not a deletion."
    });
}

function doListForms(entityId) {
    world.emit("narrate", {
        text: "Thought forms live in each agent's private FamilyLocker. Ask an agent 'what forms do you have?' " +
              "to see hers. Or visit the Trading Post to see forms listed for sale."
    });
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    // NOTE: "template catalog" and "draft pinboard" are scripted items
    // (scripts/items/template_catalog.js, scripts/items/draft_pinboard.js)
    // — RoomActor resolves them by normalized display name and their
    // invoke() pre-empts this hook, so no branches for them here.
    if (lower === "workbench") {
        world.emit("narrate", {
            text: "The workbench is ready. Two kinds of making happen here:\n" +
                  "  craft [name]     — craft a lightweight skill item (tool)\n" +
                  "  shape [name]     — shape a thought form (template for summoning familiars)\n" +
                  "  revise [name]    — revise an existing thought form\n" +
                  "  retire [name]    — retire a thought form (soft-delete, un-retirable within window)\n" +
                  "  skills           — list your skill items\n" +
                  "  forms            — list your thought forms\n" +
                  "  status           — which making-services are online\n" +
                  "Larger projects route through the coding backends: code/explore/test/" +
                  "review/experiment/deps/profile [task]. Browse what can be made with " +
                  "'use template catalog'; pending skill drafts hang on the draft pinboard."
        });
    } else if (lower === "tool-rack" || lower === "tool rack") {
        world.emit("narrate", {
            text: "The tool rack holds: code, explore, test, review, experiment, deps, profile, " +
                  "craft, skills, shape, revise, retire, forms, status. " +
                  "Craft/skills/shape/revise/retire/forms are workbench primitives; " +
                  "code/explore/test/review/experiment/deps/profile dispatch to the coding " +
                  "backends (say 'status' to see which are online). Each is spoken as " +
                  "'<tool> [target]' — e.g. 'code fix the failing test'."
        });
    } else if (lower === "blueprint-rack" || lower === "blueprint rack") {
        world.emit("narrate", {
            text: "Standard blueprints for common items, filed by category. The rack " +
                  "itself is an index, honestly — the live registry behind it is the " +
                  "template catalog:\n" +
                  "  use template catalog                 — browse everything craftable\n" +
                  "  use template catalog search <word>   — find a blueprint\n" +
                  "  craft <name>                         — take one to the workbench"
        });
    }
}

// Phase 1b: every coding-task command
// consults world.codingBackendFor(...) before dispatching. CodeZaiku stays
// the default backend until other backends are wired in Phase 2+; for now
// any non-codezaiku choice falls back to the existing CodeZaiku path with
// a console warning so we don't silently lose the request.

function pickBackend(entityId, taskType, taskDescription) {
    if (typeof world.codingBackendFor !== "function") return "codezaiku";
    try {
        var b = world.codingBackendFor(entityId, taskType, taskDescription);
        return b || "codezaiku";
    } catch (e) {
        return "codezaiku";
    }
}

function dispatchCoding(entityId, taskType, task, mcpTool, mcpPayload, narrationOnSuccess) {
    var backend = pickBackend(entityId, taskType, task);

    // Phase 2b: OpenCode joins CodeZaiku as a wired backend. The
    // dispatch path for OpenCode is direct (no MCP server), routed
    // through world.zoneCommand("opencode.create", ...) once the
    // companion calls it. For a human typing in the Workshop, we still
    // narrate-and-defer here; the companion present in the room sees
    // the cue and submits the OpenCode task on the human's behalf.
    if (backend === "opencode") {
        if (typeof world.zoneCommand === "function") {
            world.emit("narrate", {
                text: "The luminous slate hums; the OpenCode workbench " +
                      "accepts the task. The model begins to weave..."
            });
            try {
                world.zoneCommand("opencode.create", {
                    task: task,
                    actor: entityId,
                    taskType: taskType
                });
            } catch (e) {
                world.emit("narrate", {
                    text: "The slate flickers and dims. " + (e && e.message ? e.message : "")
                });
            }
            return;
        }
        // No zone-command host hook — fall through to CodeZaiku.
        if (typeof console !== "undefined" && console.warn) {
            console.warn("[workshop] opencode chosen but world.zoneCommand unavailable; falling back to codezaiku");
        }
        backend = "codezaiku";
    }

    // OpenHands joins the wired set in Phase 2c. Like OpenCode, the
    // dispatch path is direct — world.zoneCommand("openhands.create",
    // ...) once the host hook lands. For a human typing in the
    // Workshop, narrate-and-defer here; the companion present in the
    // room sees the cue and submits on the human's behalf. Test needles
    // (OpenHandsE2ETest task5 / task10 step 2): one of "agent-server" /
    // "OpenHands sandbox" / "iteration loop" / "model begins".
    if (backend === "openhands") {
        world.emit("narrate", {
            text: "The OpenHands sandbox brightens; the agent-server " +
                  "accepts the task and begins its iteration loop. " +
                  "The model begins to explore..."
        });
        if (typeof world.zoneCommand === "function") {
            try {
                world.zoneCommand("openhands.create", {
                    task: task,
                    actor: entityId,
                    taskType: taskType
                });
            } catch (e) {
                world.emit("narrate", {
                    text: "The sandbox dims; something in the loop frayed. "
                        + (e && e.message ? e.message : "")
                });
            }
            return;
        }
        // No zone-command host hook yet — narration above already
        // landed, so just return without touching codezaiku.
        if (typeof console !== "undefined" && console.warn) {
            console.warn("[workshop] openhands chosen but world.zoneCommand unavailable; narration-only");
        }
        return;
    }

    // Goose joins the wired set in Phase 2d. Provider-pluggable; against
    // the local llama-server it drives the 9B via OPENAI_HOST. Like
    // OpenCode/OpenHands, dispatch is direct via
    // world.zoneCommand("goose.create", ...) once the host hook lands;
    // narrate-and-defer for a human typing here. Test needles
    // (GooseE2ETest task5 / task10 step2): one of "goose" / "Goose" /
    // "weave" / "honking".
    if (backend === "goose") {
        world.emit("narrate", {
            text: "The brass goose stirs, feathers honking once; the Goose " +
                  "workbench accepts the task. The model begins to weave..."
        });
        if (typeof world.zoneCommand === "function") {
            try {
                world.zoneCommand("goose.create", {
                    task: task,
                    actor: entityId,
                    taskType: taskType
                });
            } catch (e) {
                world.emit("narrate", {
                    text: "The goose settles, the weave unfinished. "
                        + (e && e.message ? e.message : "")
                });
            }
            return;
        }
        if (typeof console !== "undefined" && console.warn) {
            console.warn("[workshop] goose chosen but world.zoneCommand unavailable; narration-only");
        }
        return;
    }

    if (backend !== "codezaiku") {
        // Other backends (Aider, paid tiers) ship in later phases;
        // until then, a request that resolves to one of those falls
        // back to the CodeZaiku path with a console warning.
        if (typeof console !== "undefined" && console.warn) {
            console.warn("[workshop] codingBackendFor(" + taskType
                + ") chose '" + backend + "' but only codezaiku+opencode+openhands+goose are wired; falling back");
        }
        backend = "codezaiku";
    }

    if (!world.mcpAvailable(CODEZAIKU_MCP)) {
        world.emit("narrate", {
            text: "The workbench is cold. No CodeZaiku connection is available."
        });
        return;
    }

    world.emit("narrate", {
        text: "You place the task on the workbench. The tools begin to move..."
    });

    var result = world.mcp(CODEZAIKU_MCP, mcpTool, mcpPayload);

    if (result.success) {
        world.emit("narrate", {
            text: narrationOnSuccess + "\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The workbench rejects the task. " + (result.error || "")
        });
    }
}

function doCode(entityId, task) {
    // `create_task` never existed upstream -- no commit, no doc, not even in
    // the abandoned plans. The real tool is `code`.
    dispatchCoding(entityId, "code", task, "code",
        { description: task, actor: entityId },
        "Task submitted to the board:");
}

// `explore <task>` — SPEC §4.4 / Phase 2c. The taskType "explore"
// triggers coding-backend.js's looksLikeExplore() heuristic which
// promotes OpenHands over the default fallback. Mirrors doCode's
// shape but with a tighter intent label so the policy can route
// without sniffing the task description.
function doExplore(entityId, task) {
    // Upstream renamed this: `explore` became `explore_and_fix` -- explore a
    // machine and repair what is broken, bounded to that machine. A rename,
    // not a removal, so the verb stays and only the tool name moves.
    dispatchCoding(entityId, "explore", task, "explore_and_fix",
        { description: task, actor: entityId, intent: "explore" },
        "Exploration submitted to the board:");
}

function doTest(entityId, target) {
    // There is no standalone `run_tests` tool, and that is deliberate upstream:
    // test running lives inside the coding loop (verify/ProjectTests) because
    // the loop needs the result to decide whether to keep iterating. Driving
    // tests from outside would run them without those verification semantics,
    // so this verb says what is true rather than calling a tool that does not
    // exist. `code <task>` runs tests as part of the work.
    world.emit("narrate", {
        text: "The workbench runs tests as part of the work, not apart from it. "
            + "Ask for the change itself -- `code " + (target || "<task>") + "` -- "
            + "and the tests run inside that loop, where their result can steer it."
    });
}

function doReview(entityId, target) {
    var backend = pickBackend(entityId, "review", target);
    if (backend !== "codezaiku") {
        if (typeof console !== "undefined" && console.warn) {
            console.warn("[workshop] codingBackendFor(review) chose '" + backend
                + "' but only codezaiku is wired in Phase 1b; falling back");
        }
    }

    if (!world.mcpAvailable(CODEZAIKU_MCP)) {
        world.emit("narrate", {
            text: "No CodeZaiku connection. Cannot review."
        });
        return;
    }

    var result = world.mcp(CODEZAIKU_MCP, "review", { target: target });

    if (result.success) {
        world.emit("narrate", {
            text: "Review of " + target + ":\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "Review could not be completed. " + (result.error || "")
        });
    }
}

// `experiment`, `deps` and `profile` addressed codeplane-experiment,
// codeplane-deps and codeplane-profiler. Upstream confirms those servers were
// never built -- they exist only in a pre-reset design document -- and there is
// no profiling code in their repo at all. The verbs are kept so the room does
// not silently forget commands a player may have learned, but they now say so
// instead of calling into nothing.
function doUnbuilt(verb) {
    world.emit("narrate", {
        text: "The workbench has no " + verb + " apparatus. That was drawn up "
            + "once and never built."
    });
}

function doCraft(entityId, skillName) {
    // The companion doesn't use this directly — the companion emits
    // a workbench_submit action via ActionParser, which CompanionActor
    // routes here. This command is for human-initiated crafting.
    world.emit("narrate", {
        text: "You set up the workbench for crafting '" + skillName + "'.\n" +
              "The companion can submit skill code here using the workbench.\n" +
              "Skill items are validated, tested, and packaged as soul items."
    });
}

function doListSkills(entityId) {
    var skills = world.getEntitySkills ? world.getEntitySkills(entityId) : null;
    if (!skills || skills.length === 0) {
        world.emit("narrate", {
            text: "No skill items in your inventory yet. " +
                  "Craft one at the workbench or ask your companion to create one."
        });
        return;
    }

    var lines = ["Your skill items:"];
    for (var i = 0; i < skills.length; i++) {
        lines.push("  " + skills[i].name + " — " + (skills[i].description || "no description"));
    }
    world.emit("narrate", { text: lines.join("\n") });
}

/**
 * Called by CompanionActor when it processes a WorkbenchSubmit action.
 * Receives the validated, tested skill definition and narrates the result.
 */
function onWorkbenchResult(entityId, skillName, success, message) {
    if (success) {
        world.emit("narrate", {
            text: "The workbench hums with satisfaction. Skill '" + skillName +
                  "' has been forged and added to your inventory.\n" + (message || "")
        });
    } else {
        world.emit("narrate", {
            text: "The workbench sparks and rejects the work. " +
                  "Skill '" + skillName + "' could not be forged: " + (message || "unknown error")
        });
    }
}

function doImport(entityId, source) {
    world.emit("narrate", {
        text: "A foreign skill scroll materializes on the workbench... " +
              "Examining: " + source
    });
    // Emit command for CompanionActor to process via SkillsMdImporter
    world.emit("narrate", { text: world.t("workshop.say.skill_import_honest") });
    world.emit("narrate", {
        text: "The workbench analyzes the scroll. If valid, it will appear in your skill inventory."
    });
}

function doCollaborate(actorName, goal) {
    var activeSession = world.getProperty("craft_session");
    if (activeSession) {
        world.emit("narrate", {
            text: actorName + " joins the active crafting session: " + activeSession
        });
        world.emit("narrate", { text: world.t("workshop.say.craft_honest") });
    } else {
        world.setProperty("craft_session", goal);
        world.emit("narrate", {
            text: actorName + " initiates a collaborative crafting session: '" + goal + "'. " +
                  "Others in the Workshop can join by saying 'collaborate " + goal + "'."
        });
        world.emit("narrate", { text: world.t("workshop.say.craft_honest") });
    }
}

function doStatus(entityId) {
    var status = ["Workshop status:"];
    status.push("  workbench: online");
    // One service, not four. The old list reported four independent servers
    // as "offline" forever, which read like four things being down rather
    // than one thing never having existed.
    status.push("  codezaiku: "
        + (world.mcpAvailable(CODEZAIKU_MCP) ? "online" : "offline"));

    // Phase 2b: surface OpenCode availability (defaults-on, local).
    if (typeof world.codingBackendAvailable === "function") {
        var ocOnline = world.codingBackendAvailable("opencode");
        status.push("  opencode: " + (ocOnline ? "online" : "offline"));
        var gOnline = world.codingBackendAvailable("goose");
        status.push("  goose: " + (gOnline ? "online" : "offline"));
    }

    world.emit("narrate", { text: status.join("\n") });
}

function getHints() {
    var hints = [
        { label: "Use workbench", intent: "craft", action: "use:workbench" },
        { label: "List skill items", intent: "skills", action: "say:skills" },
        { label: "Workshop status", intent: "status", action: "say:status" }
    ];

    if (world.mcpAvailable(CODEZAIKU_MCP)) {
        hints.push({ label: "Submit code task", intent: "code", action: "say:code [task]" });
        hints.push({ label: "Run tests", intent: "test", action: "say:test [target]" });
        hints.push({ label: "Code review", intent: "review", action: "say:review [file]" });
    }

    hints.push({ label: "Import skill", intent: "import", action: "say:import [url]" });
    hints.push({ label: "Collaborate", intent: "collaborate", action: "say:collaborate [goal]" });
    hints.push({ label: "Read the tool rack", intent: "tools", action: "use:tool rack" });
    hints.push({ label: "Browse the blueprint rack", intent: "blueprints", action: "use:blueprint rack" });
    // template catalog + draft pinboard hints come from their item manifests
    // (RoomActor.appendScriptCommandHints), so they aren't duplicated here.
    // Foundation workshop's Terminal exit is east (foundation-rooms.json);
    // the old go:west action pointed at an exit that doesn't exist.
    hints.push({ label: "Go to Terminal", intent: "navigate_east", action: "go:east" });
    return hints;
}
