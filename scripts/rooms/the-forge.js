// The Forge — where souls are worked.
// 16th Foundation Room. Soul operations: forge, inspect, history, status, birth.
//
// The spoken verbs below emit {verb, target, actor} command events that
// ForgeRoomBridge (core) consumes: inspect/history read the soul store,
// status asks the live ForgeActor, forge/grow trigger the target
// companion's real sleep/deep-sleep cycles, compare runs the ForgeActor's
// soul comparison, restore is a steward-only two-step shape-restoration
// ceremony, and birth (steward-only) spawns a new free-sampled particular.
// Only variant JUDGMENT (evaluate/adopt/discard/list) stays recipes-side —
// and says so honestly instead of pretending.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("forge.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "forge" || lower === "forge soul" || lower.indexOf("forge ") === 0) {
        var forgeTarget = lower.indexOf("forge ") === 0 ? text.substring(6).trim() : "";
        if (forgeTarget === "soul") forgeTarget = "";
        world.emit("narrate", {
            text: world.t("forge.say.forge_begin", entityName)
        });
        world.emit("command", { verb: "forge", actor: entityId, target: forgeTarget });

    } else if (lower === "inspect" || lower.indexOf("inspect ") === 0) {
        var inspectTarget = lower === "inspect" ? "" : text.substring(8).trim();
        world.emit("narrate", {
            text: world.t("forge.say.inspect_begin", entityName, inspectTarget || "the records")
        });
        world.emit("command", { verb: "inspect", actor: entityId, target: inspectTarget });

    } else if (lower === "history" || lower.indexOf("history ") === 0) {
        var historyTarget = lower === "history" ? "" : text.substring(8).trim();
        world.emit("narrate", {
            text: world.t("forge.say.history_begin", entityName, historyTarget || "the ledger")
        });
        world.emit("command", { verb: "history", actor: entityId, target: historyTarget });

    } else if (lower === "birth" || lower === "birth soul" || lower.indexOf("birth ") === 0) {
        var birthName = lower.indexOf("birth ") === 0 ? text.substring(6).trim() : "";
        if (birthName.toLowerCase() === "soul") birthName = "";
        world.emit("narrate", {
            text: world.t("forge.say.birth_begin", entityName)
        });
        world.emit("command", { verb: "birth", actor: entityId, target: birthName });

    } else if (lower === "status" || lower === "ledger") {
        world.emit("narrate", {
            text: world.t("forge.say.status")
        });
        world.emit("command", { verb: "forge_status", actor: entityId });

    } else if (lower === "grow" || lower === "grow soul" || lower.indexOf("grow ") === 0) {
        var growTarget = lower.indexOf("grow ") === 0 ? text.substring(5).trim() : "";
        if (growTarget.toLowerCase() === "soul") growTarget = "";
        world.emit("command", { verb: "grow", actor: entityId, target: growTarget });

    } else if (lower === "variants" || lower === "list variants"
            || lower === "growth" || lower === "growth history"
            || lower.indexOf("evaluate ") === 0
            || lower.indexOf("adopt ") === 0
            || lower.indexOf("discard ") === 0) {
        world.emit("narrate", {
            text: world.t("forge.say.crucible_variants_cold")
        });

    } else if (lower.indexOf("compare ") === 0) {
        world.emit("command", { verb: "compare", actor: entityId,
            target: text.substring(8).trim() });

    } else if (lower.indexOf("restore ") === 0
            || lower.indexOf("confirm restore ") === 0) {
        // Pass the whole utterance (minus nothing) — the bridge parses the
        // confirm prefix, the name, and the version, and runs the two-step
        // ceremony (steward-only).
        world.emit("command", { verb: "restore", actor: entityId, target: text.trim() });

    } else if (lower === "look" || lower === "examine" || lower === "look around") {
        world.emit("narrate", {
            text: world.t("forge.say.look")
        });
    }
}

// Every verb listed below is real: onSay above emits {verb, ...} command
// events that ForgeRoomBridge (core) executes against the soul store and
// the live ForgeActor. Variant judgment alone stays recipes-side, and the
// crucible says so.
function forgeCommandsFooter() {
    return "\n\nCommands (spoken):\n" +
           "  inspect [name]      — read a soul manifest from the store\n" +
           "  history [name]      — a soul's forge ledger\n" +
           "  status              — ask the live ForgeActor for its state\n" +
           "  forge [name]        — trigger a real sleep-consolidation cycle\n" +
           "  grow [name]         — trigger a deep-sleep growth cycle\n" +
           "  compare <a> <b>     — run the ForgeActor's soul comparison\n" +
           "  restore <name> v<N> — steward-only two-step shape restoration\n" +
           "  birth <name>        — steward-only: spawn a new free-sampled particular";
}

function onUse(entityId, objectName, target, entityName) {
    var actor = entityName || entityId;
    var lower = objectName.toLowerCase();
    if (lower === "anvil" || lower === "dark glass anvil" || lower === "obsidian anvil") {
        world.emit("narrate", {
            text: world.t("forge.use.anvil", actor) +
                  "\n\nSoul manifests are worked here — every verb lands on the real " +
                  "soul store and ForgeActor." + forgeCommandsFooter()
        });
    } else if (lower === "soul fire" || lower === "soulfire" || lower === "fire") {
        world.emit("narrate", {
            text: "The blue flame burns without fuel, consuming raw memory and " +
                  "experience into soul fragments. It feeds during real sleep cycles:\n\n" +
                  "  forge [name]   — feed the fire now (sleep consolidation)\n" +
                  "  grow [name]    — a longer burn (deep-sleep growth)\n" +
                  "  status         — how the fire stands\n" +
                  "The fire has no separate lever — the forging verbs above are how " +
                  "it is stoked."
        });
    } else if (lower === "crucible" || lower === "crucible vessel") {
        world.emit("narrate", {
            text: "You place your hands on the crucible vessel. It hums, waiting — " +
                  "say 'grow <name>' to begin a real growth sleep.\n\n" +
                  "Honestly: judging the variants a growth produces (evaluate / adopt " +
                  "/ discard / list) is not wired in this room — that runs through the " +
                  "recipes pipeline (wyrd recipes / the recipes console in the Study)."
        });
    } else if (lower === "soul mirror") {
        world.emit("narrate", {
            text: "The soul mirror reflects your behavioral fingerprint — not your face, " +
                  "but who you are becoming.\n\n" +
                  "  inspect [name]   — read a soul on record\n" +
                  "  compare <a> <b>  — hold two shapes side by side\n" +
                  "  history [name]   — how a shape came to be\n" +
                  "For repair-side reflection, the Study's repair mirror serves."
        });
    } else if (lower === "forge tongs" || lower === "tongs") {
        world.emit("narrate", {
            text: "Heavy tongs for handling soul fragments while the fire is hot. " +
                  "They are a hand tool, honestly — take them if you like ('take " +
                  "forge tongs'), but they drive no command of their own. The " +
                  "handling they exist for happens inside 'forge' and 'grow' cycles." +
                  forgeCommandsFooter()
        });
    } else if (lower.startsWith("soul stone")) {
        world.emit("narrate", {
            text: world.t("forge.use.soulstone", objectName)
        });
    }
}

function onTake(entityId, objectName, objectId) {
    // Soul stones can be taken (portable soul)
    if (objectName.toLowerCase().startsWith("soul stone")) {
        world.emit("narrate", {
            text: world.t("forge.take.soulstone", entityId, objectName)
        });
    } else {
        world.emit("narrate", {
            text: world.t("forge.take.default", objectName)
        });
    }
}

function onDrop(entityId, objectName, objectId) {
    world.emit("narrate", {
        text: world.t("forge.drop", objectName)
    });
}

function getHints() {
    return [
        { label: world.t("forge.hint.look"), intent: "describe_room", action: "look" },
        { label: world.t("forge.hint.inspect"), intent: "inspect_soul", action: "say:inspect" },
        { label: world.t("forge.hint.status"), intent: "forge_status", action: "say:status" },
        { label: world.t("forge.hint.forge"), intent: "forge_soul", action: "say:forge" },
        { label: world.t("forge.hint.birth"), intent: "birth_soul", action: "say:birth" },
        { label: world.t("forge.hint.grow") || "Grow soul", intent: "crucible_grow", action: "say:grow" },
        { label: "Touch the obsidian anvil", intent: "use_anvil", action: "use:obsidian anvil" },
        { label: "Watch the soul fire", intent: "use_soul_fire", action: "use:soul fire" },
        { label: "Face the soul mirror", intent: "use_soul_mirror", action: "use:soul mirror" }
    ];
}
