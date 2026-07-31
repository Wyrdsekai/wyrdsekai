// The Gallery — Photo Fabric room (§14).
// Where photos are displayed, browsed, and curated.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("gallery.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("import ")) {
        var filename = text.substring(7).trim();
        world.emit("narrate", {
            text: world.t("gallery.say.import", entityName, filename)
        });
    }

    if (lower.startsWith("tag ")) {
        // Format: tag <photo-id> <tag1> <tag2> ...
        var parts = text.substring(4).trim().split("\\s+", 2);
        if (parts.length < 2) {
            world.emit("narrate", { text: world.t("gallery.say.tag_usage") });
        } else {
            world.emit("narrate", {
                text: world.t("gallery.say.tag_success", parts[0], parts[1])
            });
        }
    }

    if (lower === "browse" || lower === "gallery" || lower === "photos") {
        world.emit("narrate", {
            text: world.t("gallery.say.browse")
        });
    }

    if (lower.startsWith("search ")) {
        var query = text.substring(7).trim();
        world.emit("narrate", {
            text: world.t("gallery.say.search", query)
        });
    }

    if (lower === "faces" || lower === "people") {
        world.emit("narrate", {
            text: world.t("gallery.say.faces")
        });
    }

    if (lower === "memories" || lower === "lanes") {
        world.emit("narrate", {
            text: world.t("gallery.say.memories")
        });
    }
}

function onUse(entityId, objectName, target) {
    if (objectName.toLowerCase().includes("darkroom")) {
        world.emit("narrate", {
            text: world.t("gallery.use.darkroom")
        });
    }
}

function getHints() {
    return [
        { label: world.t("gallery.hint.browse"), intent: "browse", action: "say:browse" },
        { label: world.t("gallery.hint.faces"), intent: "faces", action: "say:faces" },
        { label: world.t("gallery.hint.memories"), intent: "memories", action: "say:memories" },
        { label: world.t("gallery.hint.darkroom"), intent: "darkroom", action: "use:darkroom" },
        { label: world.t("gallery.hint.west"), intent: "navigate_west", action: "go:west" }
    ];
}
