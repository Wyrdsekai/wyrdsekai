// The Archive — the house's copies of itself.
// The house's vault copies everything every few minutes and opens a copy again now and then to prove it
// whole. This room is where that can be seen from inside the world. It reads; it never makes or restores.

function theRecord() {
    return world.getArchive();
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("archive.enter", entityName) + "\n\n" + theRecord()
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();
    if (lower === "record" || lower === "status" || lower === "copies" || lower === "backups" || lower === "read the record") {
        world.emit("narrate", { text: theRecord() });
    }
}

function onUse(entityId, objectName, target) {
    var name = objectName.toLowerCase();
    if (name === "record" || name === "crystal" || name === "crystals") {
        world.emit("narrate", { text: theRecord() });
    }
}
