// The Safe — topology-gated secret keeper (§74.11).
// Stores secrets using threshold secret sharing (Shamir's SS).
// Access requires specific vitality levels (confidence + alignment).
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

function loadSecrets() {
    var raw = world.getProperty("safe.secrets");
    return raw ? JSON.parse(raw) : {};
}

function saveSecrets(secrets) {
    world.setProperty("safe.secrets", JSON.stringify(secrets));
}

function loadNextId() {
    var raw = world.getProperty("safe.nextId");
    return raw ? parseInt(raw, 10) : 1;
}

function saveNextId(id) {
    world.setProperty("safe.nextId", String(id));
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("the_safe.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("deposit ") || lower.startsWith("store ")) {
        // Format: deposit <name> | <secret> | <threshold> | <shares>
        var parts = text.substring(text.indexOf(" ") + 1).split("|");
        if (parts.length < 4) {
            world.emit("narrate", {
                text: world.t("the_safe.say.deposit_usage")
            });
        } else {
            var name = parts[0].trim();
            var secretText = parts[1].trim();
            var threshold = parseInt(parts[2].trim()) || 2;
            var totalShares = parseInt(parts[3].trim()) || 3;
            if (threshold > totalShares) threshold = totalShares;
            if (threshold < 2) threshold = 2;

            var nextId = loadNextId();
            var id = "secret-" + nextId;
            var secrets = loadSecrets();
            secrets[id] = {
                name: name,
                owner: entityName,
                ownerId: entityId,
                threshold: threshold,
                totalShares: totalShares,
                depositedAt: new Date().toISOString()
            };
            saveSecrets(secrets);
            saveNextId(nextId + 1);
            world.emit("narrate", {
                text: world.t("the_safe.say.deposit_success", entityName, name, totalShares, threshold, id)
            });
        }
    }

    if (lower.startsWith("withdraw ") || lower.startsWith("retrieve ")) {
        var targetId = text.substring(text.indexOf(" ") + 1).trim();
        var secrets = loadSecrets();
        var secret = secrets[targetId];
        if (!secret) {
            world.emit("narrate", {
                text: world.t("the_safe.say.withdraw_not_found")
            });
        } else {
            world.emit("narrate", {
                text: world.t("the_safe.say.withdraw_found", secret.name, secret.threshold, secret.totalShares)
            });
        }
    }

    if (lower === "inventory" || lower === "list" || lower === "secrets") {
        var secrets = loadSecrets();
        var ids = Object.keys(secrets);
        if (ids.length === 0) {
            world.emit("narrate", {
                text: world.t("the_safe.say.inventory_empty")
            });
        } else {
            var listing = world.t("the_safe.say.inventory_header") + "\n";
            for (var id in secrets) {
                var s = secrets[id];
                listing += world.t("the_safe.say.inventory_item", id, s.name, s.owner, s.threshold, s.totalShares) + "\n";
            }
            world.emit("narrate", { text: listing });
        }
    }

    if (lower === "status" || lower === "wards") {
        var secrets = loadSecrets();
        world.emit("narrate", {
            text: world.t("the_safe.say.status", Object.keys(secrets).length)
        });
    }

    if (lower === "pair code" || lower === "pairing code") {
        // Inform the steward about the pairing code endpoint.
        // The actual code is managed by PairingService via REST (/api/pair/code).
        world.emit("narrate", {
            text: "The rune-lock on the wall flickers. To pair a new device, "
                + "use the REST endpoint: GET /api/pair/code — or check the server log "
                + "for the prominently displayed 6-digit code after a device requests pairing."
        });
    }
}

function lockFooter() {
    return "\n\nCommands (spoken to the room):\n"
        + "  say deposit <name> | <secret> | <threshold> | <shares>  — split a secret across key-holders\n"
        + "  say withdraw <secret-id>   — check what it takes to reassemble one\n"
        + "  say inventory              — every secret behind the lock\n"
        + "  say pair code              — how to pair a new device (REST /api/pair/code)";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    // The silver runes are a scripted item (scripts/items/silver_runes.js) —
    // `use silver runes` runs it directly. This branch only fires via
    // speech-trigger or if the item failed to load; it points at the real
    // interface.
    if (obj.includes("rune") || obj.includes("ward")) {
        world.emit("narrate", {
            text: world.t("the_safe.use.rune")
                + "\n\nThe runes answer to touch, not speech:\n"
                + "  use silver runes            — the backup snapshots the wards preserve\n"
                + "  use silver runes slots      — the named vault slots the wards guard"
        });
    }
    if (obj.includes("lock") || obj.includes("threshold")) {
        var secrets = loadSecrets();
        var ids = Object.keys(secrets);
        var text = world.t("the_safe.use.lock")
            + "\n\nThe lock holds " + ids.length + " secret(s) under threshold shares.";
        for (var id in secrets) {
            var s = secrets[id];
            text += "\n  " + id + " — " + s.name + " (" + s.owner + ", "
                + s.threshold + "-of-" + s.totalShares + ")";
        }
        world.emit("narrate", { text: text + lockFooter() });
    }
}

function getHints() {
    return [
        { label: world.t("the_safe.hint.inventory"), intent: "list_secrets", action: "say:inventory" },
        { label: world.t("the_safe.hint.status"), intent: "ward_status", action: "say:status" },
        { label: "Pairing code", intent: "pair_code", action: "say:pair code" },
        { label: world.t("the_safe.hint.rune"), intent: "examine_runes", action: "use:silver runes" },
        { label: "Inspect the threshold lock", intent: "use_lock", action: "use:threshold lock" },
        { label: world.t("the_safe.hint.northeast"), intent: "navigate_nw", action: "go:northwest" }
    ];
}
