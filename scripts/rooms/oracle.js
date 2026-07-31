// The Oracle — prediction engine room.
// Reads predictions from room properties (set by OracleForgeHook)
// and manifests them as objects, narration, and hints.
//
// Objects: oracle-lens (predictions), pattern-board (connections),
//   forecast-pool (forecasts), insight-scroll (takeaway).
//
// Predictions are stored as JSON in room property "oracle_predictions".

function onEnter(entityId, entityName, fromDirection) {
    var predictionsRaw = world.getProperty("oracle_predictions");
    var predictions = [];
    try {
        if (predictionsRaw) predictions = JSON.parse(predictionsRaw);
    } catch (e) { /* empty */ }

    if (predictions.length === 0) {
        world.emit("narrate", {
            text: world.t("oracle.room.enter.empty")
        });
        return;
    }

    world.emit("narrate", {
        text: world.t("oracle.room.enter", entityName, predictions.length)
    });

    // Narrate the top 3 predictions
    var top = predictions.slice(0, 3);
    for (var i = 0; i < top.length; i++) {
        var p = top[i];
        var icon = {
            "pattern": "~", "anomaly": "!", "forecast": ">",
            "correlation": "<>", "topic": "#", "sequence": "->",
            "recommendation": "*", "anticipation": "**"
        }[p.category] || "?";

        world.emit("narrate", {
            text: "  " + icon + " " + p.text
        });
    }

    if (predictions.length > 3) {
        world.emit("narrate", {
            text: world.t("oracle.room.more", predictions.length - 3)
        });
    }
}

function onSay(entityId, entityName, text) {
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "/help") {
        world.emit("narrate", {
            text: world.t("oracle.room.help")
        });
        return;
    }

    if (lower === "patterns") {
        showByCategory("pattern");
        return;
    }

    if (lower === "anomalies") {
        showByCategory("anomaly");
        return;
    }

    if (lower === "forecast" || lower === "forecasts") {
        showByCategory("forecast");
        return;
    }

    if (lower === "topics") {
        showByCategory("topic");
        return;
    }

    if (lower === "correlations") {
        showByCategory("correlation");
        return;
    }

    if (lower === "all" || lower === "predictions") {
        showAll();
        return;
    }

    if (lower === "train" || lower === "refresh") {
        // Real trigger: RoomActor routes this to the OracleForgeHook train
        // cycle and narrates the outcome (fresh predictions or an honest
        // "engine not running") back into the room asynchronously.
        world.emit("oracle_action", { action: "train", entityId: entityId });
        world.emit("narrate", {
            text: world.t("oracle.room.training")
        });
        return;
    }
}

// --- Room-scoped agent tools (W2) ---
// Declares tools the companion's model can call while standing in this room.
// RoomActor answers GetToolDefinitions with this list; calls arrive at
// onToolCall below.

function getToolDefinitions() {
    return [
        {
            name: "train_oracle",
            description: "Run an Oracle training cycle now: learn from recent "
                + "events and refresh the predictions shown in this room. "
                + "Results are narrated here when the cycle finishes.",
            params: {}
        }
    ];
}

function onToolCall(entityId, toolName, argsJson) {
    if (toolName === "train_oracle") {
        world.emit("oracle_action", { action: "train", entityId: entityId });
        world.emit("narrate", {
            text: world.t("oracle.room.training")
        });
    }
}

function onUse(entityId, objectName, target) {
    // objectName arrives as the RESOLVED display name ("oracle lens",
    // "pattern board", ...) — match on fragments, keeping the old
    // hyphenated ids working too.
    var name = objectName.toLowerCase();
    if (name.includes("lens")) {
        world.emit("narrate", {
            text: "The lens focuses every current prediction:" + lensFooter()
        });
        showAll();
    } else if (name.includes("board")) {
        world.emit("narrate", {
            text: "The threads on the board trace patterns and correlations:" + boardFooter()
        });
        showByCategory("pattern");
        showByCategory("correlation");
    } else if (name.includes("pool")) {
        world.emit("narrate", {
            text: "The still water resolves into forecasts:" + poolFooter()
        });
        showByCategory("forecast");
    } else if (name.includes("scroll")) {
        // Show the single most important prediction
        var predictions = loadPredictions();
        if (predictions.length > 0) {
            world.emit("narrate", {
                text: world.t("oracle.room.scroll", predictions[0].text) + scrollFooter()
            });
        } else {
            world.emit("narrate", {
                text: "The scroll is blank — the Oracle has no insight to distill yet."
                    + " Say train to set the engine working." + scrollFooter()
            });
        }
    }
}

function lensFooter() {
    return "\n(use oracle lens — all predictions | say patterns / forecasts / anomalies"
        + " — by kind | say train — refresh the Oracle)\n";
}

function boardFooter() {
    return "\n(use pattern board — patterns and correlations | say correlations"
        + " — spoken form | say train — refresh)\n";
}

function poolFooter() {
    return "\n(use forecast pool — the Oracle's forecasts | say forecast"
        + " — spoken form | say train — refresh)\n";
}

function scrollFooter() {
    return "\n(use insight scroll — the single key insight | use oracle lens"
        + " — everything behind it)";
}

function getHints() {
    return [
        { label: world.t("oracle.room.hint.all"), intent: "all", action: "say:predictions" },
        { label: "Focus the oracle lens", intent: "use_lens", action: "use:oracle lens" },
        { label: "Trace the pattern board", intent: "use_board", action: "use:pattern board" },
        { label: "Gaze into the forecast pool", intent: "use_pool", action: "use:forecast pool" },
        { label: "Unroll the insight scroll", intent: "use_scroll", action: "use:insight scroll" },
        { label: world.t("oracle.room.hint.patterns"), intent: "patterns", action: "say:patterns" },
        { label: world.t("oracle.room.hint.forecast"), intent: "forecast", action: "say:forecast" },
        { label: world.t("oracle.room.hint.anomalies"), intent: "anomalies", action: "say:anomalies" },
        { label: world.t("oracle.room.hint.help"), intent: "help", action: "say:help" }
    ];
}

// --- Helpers ---

function loadPredictions() {
    var raw = world.getProperty("oracle_predictions");
    try {
        return raw ? JSON.parse(raw) : [];
    } catch (e) {
        return [];
    }
}

function showAll() {
    var predictions = loadPredictions();
    if (predictions.length === 0) {
        world.emit("narrate", { text: world.t("oracle.room.no_predictions") });
        return;
    }
    for (var i = 0; i < predictions.length; i++) {
        world.emit("narrate", { text: "  " + predictions[i].text });
    }
}

function showByCategory(category) {
    var predictions = loadPredictions();
    var filtered = [];
    for (var i = 0; i < predictions.length; i++) {
        if (predictions[i].category === category) {
            filtered.push(predictions[i]);
        }
    }
    if (filtered.length === 0) {
        world.emit("narrate", { text: world.t("oracle.room.none_in_category", category) });
        return;
    }
    for (var i = 0; i < filtered.length; i++) {
        world.emit("narrate", { text: "  " + filtered[i].text });
    }
}
