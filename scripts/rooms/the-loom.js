// The Loom — CRDT garbage collection / compaction room (§59).
// Grace period + checkpoint hybrid for safe GC of distributed state.
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

function loadCrdts() {
    var raw = world.getProperty("loom.crdts");
    return raw ? JSON.parse(raw) : {};
}

function saveCrdts(crdts) {
    world.setProperty("loom.crdts", JSON.stringify(crdts));
}

function loadCompactionLog() {
    var raw = world.getProperty("loom.compactionLog");
    return raw ? JSON.parse(raw) : [];
}

function saveCompactionLog(log) {
    world.setProperty("loom.compactionLog", JSON.stringify(log));
}

function countCompactable() {
    var trackedCrdts = loadCrdts();
    var count = 0;
    for (var id in trackedCrdts) {
        if (trackedCrdts[id].compactable) count++;
    }
    return count;
}

function onEnter(entityId, entityName, fromDirection) {
    var trackedCrdts = loadCrdts();
    var count = Object.keys(trackedCrdts).length;
    var compactable = countCompactable();
    world.emit("narrate", {
        text: world.t("the_loom.enter", entityName, count, compactable)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower === "status" || lower === "threads") {
        var trackedCrdts = loadCrdts();
        var count = Object.keys(trackedCrdts).length;
        if (count === 0) {
            world.emit("narrate", {
                text: world.t("the_loom.say.status_empty")
            });
            return;
        }

        var msg = world.t("the_loom.say.status_header", count, countCompactable()) + "\n\n";
        for (var id in trackedCrdts) {
            var c = trackedCrdts[id];
            msg += "  " + id + " — " + c.sizeBytes + " bytes, " + c.tombstones + " tombstones";
            if (c.compactable) msg += " [READY]";
            msg += "\n";
        }
        world.emit("narrate", { text: msg });
    }

    if (lower.startsWith("compact ")) {
        var targetId = text.substring(text.indexOf(" ") + 1).trim();
        var trackedCrdts = loadCrdts();
        var crdt = trackedCrdts[targetId];
        if (!crdt) {
            world.emit("narrate", {
                text: world.t("the_loom.say.compact_not_found")
            });
        } else if (!crdt.compactable) {
            world.emit("narrate", {
                text: world.t("the_loom.say.compact_not_ready", targetId)
            });
        } else {
            var bytesRemoved = crdt.tombstones * 64;
            var newSize = Math.max(0, crdt.sizeBytes - bytesRemoved);
            crdt.sizeBytes = newSize;
            var removed = crdt.tombstones;
            crdt.tombstones = 0;
            crdt.compactable = false;
            crdt.lastCheckpoint = new Date().toISOString();
            saveCrdts(trackedCrdts);

            var compactionLog = loadCompactionLog();
            compactionLog.push({
                crdtId: targetId,
                tombstonesRemoved: removed,
                bytesFreed: bytesRemoved,
                timestamp: new Date().toISOString()
            });
            saveCompactionLog(compactionLog);

            world.emit("narrate", {
                text: world.t("the_loom.say.compact_success", targetId, removed, bytesRemoved)
            });
        }
    }

    if (lower === "gc-stats" || lower === "history") {
        var compactionLog = loadCompactionLog();
        if (compactionLog.length === 0) {
            world.emit("narrate", {
                text: world.t("the_loom.say.history_empty")
            });
        } else {
            var msg = world.t("the_loom.say.history_header") + "\n";
            var recent = compactionLog.slice(-5).reverse();
            for (var i = 0; i < recent.length; i++) {
                var entry = recent[i];
                msg += world.t("the_loom.say.history_item", entry.crdtId, entry.tombstonesRemoved, entry.bytesFreed) + "\n";
            }
            world.emit("narrate", { text: msg });
        }
    }
}

// Every command listed below is real: status/compact/gc-stats are handled in
// onSay above against state persisted via world.getProperty/setProperty.
function loomCommandsFooter() {
    return "\n\nCommands (spoken):\n" +
           "  status            — every tracked strand: size, tombstones, [READY] marks\n" +
           "  compact <crdt-id> — weave away a READY strand's tombstones\n" +
           "  gc-stats          — the compaction ledger (also: history)";
}

function onUse(entityId, objectName, target) {
    if (objectName.toLowerCase().includes("thread") || objectName.toLowerCase().includes("strand")) {
        world.emit("narrate", {
            text: world.t("the_loom.use.thread") + loomCommandsFooter()
        });
    }
    if (objectName.toLowerCase().includes("loom") || objectName.toLowerCase().includes("frame")) {
        world.emit("narrate", {
            text: world.t("the_loom.use.loom") + loomCommandsFooter()
        });
    }
}

function getHints() {
    return [
        { label: world.t("the_loom.hint.status"), intent: "view_status", action: "say:status" },
        { label: world.t("the_loom.hint.history"), intent: "view_history", action: "say:gc-stats" },
        { label: world.t("the_loom.hint.thread"), intent: "examine_threads", action: "use:luminous threads" },
        { label: "Examine the loom frame", intent: "examine_frame", action: "use:loom frame" },
        // The Loom's only exit is north to the Boiler Room (foundation-rooms.json);
        // the legacy the_loom.hint.west key pointed at an exit that doesn't exist.
        { label: "Go north to Boiler Room", intent: "navigate_north", action: "go:north" }
    ];
}
