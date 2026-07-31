// The Vault — filesystem abstraction, knowledge storage.
// Provides ward-gated file reading from ~/.wyrdsekai/vault/.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("vault.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "list" || lower === "files" || lower === "ls" || lower === "catalog") {
        var listing = world.listVaultFiles();
        world.emit("narrate", {
            text: world.t("vault.say.catalog_header", listing)
        });
    } else if (lower.startsWith("read ")) {
        var filename = text.substring(5).trim();
        var contents = world.readVaultFile(filename);
        world.emit("narrate", {
            text: world.t("vault.say.read_scroll", contents)
        });
    }
}

function onUse(entityId, objectName, target) {
    var name = objectName.toLowerCase();
    if (name === "readme") {
        var contents = world.readVaultFile("readme.txt");
        world.emit("narrate", {
            text: world.t("vault.use.readme", contents)
                + "\n\nCommands:\n"
                + "  use readme         — read this parchment (~/.wyrdsekai/vault/readme.txt)\n"
                + "  say 'list'         — catalog every scroll on the shelves\n"
                + "  say 'read <file>'  — unroll a specific scroll"
        });
    } else if (name === "safe" || name === "strongbox" || name === "ledger") {
        var economy = world.getEconomyStatus();
        var reputation = world.getReputationSummary();
        var text = "=== Vault Ledger ===\n";
        if (economy && economy.indexOf("No") !== 0) text += economy + "\n";
        if (reputation && reputation.indexOf("No") !== 0) text += "\n" + reputation;
        world.emit("narrate", { text: text });
    }
}

function onTake(entityId, objectName, objectId) {
    world.emit("narrate", {
        text: world.t("vault.take", objectName)
    });
}

function onDrop(entityId, objectName, objectId) {
    world.emit("narrate", {
        text: world.t("vault.drop", objectName)
    });
}

function getHints() {
    var hints = [
        { label: world.t("vault.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("vault.hint.readme"), intent: "use_readme", action: "use:readme" },
        { label: world.t("vault.hint.catalog"), intent: "list_files", action: "say:catalog" },
        { label: world.t("vault.hint.east"), intent: "navigate_east", action: "go:east" }
    ];
    return hints;
}
