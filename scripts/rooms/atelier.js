// Atelier — Creative Output (§88.8).
// MCP backends: comfyui/stable-diffusion (local), dall-e (keyed), elevenlabs (keyed), suno (keyed).
// Image generation, audio synthesis, voice creation.
// Connected to Gallery (west exit).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters the Atelier. " +
              "An easel stands by the window, canvas waiting. " +
              "A sound-crystal hums quietly on a shelf. " +
              "The smell of creative possibility fills the air."
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower.startsWith("paint ")) {
        var description = text.substring(6).trim();
        doPaint(entityId, description);

    } else if (lower.startsWith("compose ")) {
        var description = text.substring(8).trim();
        doCompose(entityId, description);

    } else if (lower.startsWith("voice ")) {
        var textToSpeak = text.substring(6).trim();
        doVoice(entityId, textToSpeak);

    } else if (lower === "gallery") {
        world.emit("narrate", {
            text: "The Gallery lies to the west. Your creations hang there."
        });

    } else if (lower === "status") {
        doStatus(entityId);

    } else if (lower === "look" || lower === "look around") {
        world.emit("narrate", { text: world.getRoomDescription() });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    if (lower === "easel") {
        world.emit("narrate", {
            text: "The easel awaits. Say 'paint [description]' to create an image."
        });
    } else if (lower === "sound-crystal" || lower === "sound crystal") {
        world.emit("narrate", {
            text: "The sound-crystal vibrates gently. Say 'compose [description]' " +
                  "or 'voice [text]' to create audio."
        });
    } else if (lower === "artist-palette" || lower === "artist palette") {
        world.emit("narrate", {
            text: "You take the palette. Creative tools available from anywhere."
        });
    }
}

function doPaint(entityId, description) {
    var service = null;
    if (world.mcpAvailable("comfyui")) service = "comfyui";
    else if (world.mcpAvailable("stable-diffusion")) service = "stable-diffusion";
    else if (world.mcpAvailable("dall-e")) service = "dall-e";

    if (!service) {
        world.emit("narrate", {
            text: "The easel has no paints. No image generation service is configured."
        });
        return;
    }

    world.emit("narrate", {
        text: "The easel comes alive. Colors swirl across the canvas..."
    });

    var result = world.mcp(service, "generate_image", { prompt: description });
    if (result.success) {
        world.emit("narrate", {
            text: "A new work appears on the easel:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The colors refuse to form. " + (result.error || "")
        });
    }
}

function doCompose(entityId, description) {
    var service = null;
    if (world.mcpAvailable("suno")) service = "suno";
    else if (world.mcpAvailable("elevenlabs")) service = "elevenlabs";

    if (!service) {
        world.emit("narrate", {
            text: "The sound-crystal is silent. No audio service is configured."
        });
        return;
    }

    world.emit("narrate", {
        text: "The sound-crystal begins to resonate..."
    });

    var result = world.mcp(service, "generate_audio", { prompt: description });
    if (result.success) {
        world.emit("narrate", {
            text: "Music fills the Atelier:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The sound fades to silence. " + (result.error || "")
        });
    }
}

function doVoice(entityId, textToSpeak) {
    if (!world.mcpAvailable("elevenlabs")) {
        world.emit("narrate", {
            text: "No voice synthesis service is available."
        });
        return;
    }

    var result = world.mcp("elevenlabs", "text_to_speech", { text: textToSpeak });
    if (result.success) {
        world.emit("narrate", {
            text: "A voice speaks from the crystal:\n\n" + result.data
        });
    } else {
        world.emit("narrate", {
            text: "The voice cannot form. " + (result.error || "")
        });
    }
}

function doStatus(entityId) {
    var services = ["comfyui", "stable-diffusion", "dall-e", "elevenlabs", "suno"];
    var status = [];
    for (var i = 0; i < services.length; i++) {
        status.push(services[i] + ": " +
            (world.mcpAvailable(services[i]) ? "available" : "not configured"));
    }
    world.emit("narrate", { text: "Atelier services:\n" + status.join("\n") });
}

function getHints() {
    return [
        { label: "Paint an image", intent: "paint", action: "say:paint [description]" },
        { label: "Compose music", intent: "compose", action: "say:compose [description]" },
        { label: "Generate voice", intent: "voice", action: "say:voice [text]" },
        { label: "Atelier status", intent: "status", action: "say:status" },
        { label: "Go to Gallery", intent: "navigate", action: "go:west" }
    ];
}
