// GPU Chamber — inference room with compute scheduling (§59).
// Agents reserve GPU slots before inference. Room description auto-updates.
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

var maxSlots = 4;

function loadReservations() {
    var raw = world.getProperty("gpu.reservations");
    return raw ? JSON.parse(raw) : {};
}

function saveReservations(reservations) {
    world.setProperty("gpu.reservations", JSON.stringify(reservations));
}

function loadNextId() {
    var raw = world.getProperty("gpu.nextId");
    return raw ? parseInt(raw, 10) : 1;
}

function saveNextId(id) {
    world.setProperty("gpu.nextId", String(id));
}

function countActiveReservations() {
    var reservations = loadReservations();
    var count = 0;
    for (var id in reservations) {
        if (reservations[id].active) count++;
    }
    return count;
}

function onEnter(entityId, entityName, fromDirection) {
    var active = countActiveReservations();
    var available = maxSlots - active;
    world.emit("narrate", {
        text: world.t("gpu_chamber.enter", entityName, active, maxSlots, available)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("reserve") || lower.startsWith("claim")) {
        var reservations = loadReservations();
        var active = countActiveReservations();
        if (active >= maxSlots) {
            world.emit("narrate", {
                text: world.t("gpu_chamber.say.reserve_full")
            });
            return;
        }

        // Check if already has reservation
        for (var id in reservations) {
            if (reservations[id].agentId === entityId && reservations[id].active) {
                world.emit("narrate", {
                    text: world.t("gpu_chamber.say.reserve_already", entityName, id)
                });
                return;
            }
        }

        var nextId = loadNextId();
        var rId = "gpu-" + nextId;
        reservations[rId] = {
            agentId: entityId,
            agentName: entityName,
            active: true,
            reservedAt: new Date().toISOString()
        };
        saveReservations(reservations);
        saveNextId(nextId + 1);
        world.emit("narrate", {
            text: world.t("gpu_chamber.say.reserve_success", rId, entityName, maxSlots - active - 1)
        });
    }

    if (lower.startsWith("release") || lower.startsWith("free")) {
        var reservations = loadReservations();
        var targetId = text.substring(text.indexOf(" ") + 1).trim();
        var found = false;

        // Try by ID first
        if (reservations[targetId] && reservations[targetId].agentId === entityId) {
            reservations[targetId].active = false;
            found = true;
        } else {
            // Release own slot
            for (var id in reservations) {
                if (reservations[id].agentId === entityId && reservations[id].active) {
                    reservations[id].active = false;
                    targetId = id;
                    found = true;
                    break;
                }
            }
        }

        if (found) {
            saveReservations(reservations);
            world.emit("narrate", {
                text: world.t("gpu_chamber.say.release_success", entityName, targetId)
            });
        } else {
            world.emit("narrate", {
                text: world.t("gpu_chamber.say.release_not_found", entityName)
            });
        }
    }

    if (lower === "status" || lower === "slots") {
        var reservations = loadReservations();
        var active = countActiveReservations();
        var available = maxSlots - active;
        var msg = world.t("gpu_chamber.say.status", active, maxSlots, available) + "\n";

        for (var id in reservations) {
            if (reservations[id].active) {
                msg += "  [" + id + "] " + reservations[id].agentName + " (since " + reservations[id].reservedAt + ")\n";
            }
        }
        world.emit("narrate", { text: msg });
    }
}

function renderSlotTable() {
    var reservations = loadReservations();
    var active = countActiveReservations();
    var lines = [world.t("gpu_chamber.say.status", active, maxSlots, maxSlots - active)];
    for (var id in reservations) {
        if (reservations[id].active) {
            lines.push("  [" + id + "] " + reservations[id].agentName
                + " (since " + reservations[id].reservedAt + ")");
        }
    }
    return lines.join("\n");
}

function slotCommands() {
    return "Commands:\n"
        + "  say 'reserve'        — claim an inference slot\n"
        + "  say 'release'        — free your slot (or 'release <slot-id>')\n"
        + "  say 'status'         — slot allocations (same table as above)";
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower.includes("core") || lower.includes("bank")) {
        // Real state: slot reservations (room-persisted) + backend count.
        var backends = world.getInferenceBackendCount();
        world.emit("narrate", {
            text: world.t("gpu_chamber.use.core")
                + "\n\n" + renderSlotTable()
                + "\nConfigured inference backends: " + backends
                + "\n\n" + slotCommands()
        });
        return;
    }

    if (lower.includes("monitor") || lower.includes("status")) {
        // The display's promise: current slot allocations and throughput.
        // Slot allocations are fully wired (room state). Per-backend
        // throughput detail is boiler-room/bridge-gated, so the monitor
        // shows the honest count and names the real path.
        var backendCount = world.getInferenceBackendCount();
        world.emit("narrate", {
            text: "The status monitor flickers to life:\n\n"
                + renderSlotTable()
                + "\nConfigured inference backends: " + backendCount
                + "\n\nPer-backend throughput detail isn't wired to this display —"
                + " read it in The Boiler Room ('use computer inference') or The Bridge ('say inference')."
                + "\n\n" + slotCommands()
        });
        return;
    }
}

function getHints() {
    return [
        { label: world.t("gpu_chamber.hint.reserve"), intent: "reserve_gpu", action: "say:reserve" },
        { label: world.t("gpu_chamber.hint.status"), intent: "check_status", action: "say:status" },
        { label: world.t("gpu_chamber.hint.core"), intent: "examine_cores", action: "use:inference cores" },
        { label: "Read the status monitor", intent: "use_monitor", action: "use:status monitor" },
        { label: world.t("gpu_chamber.hint.release"), intent: "release_gpu", action: "say:release" }
    ];
}
