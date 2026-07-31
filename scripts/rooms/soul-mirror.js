// Soul Mirror — behavioral drift detection room (§87.2).
// A dedicated room where agents examine their identity over time.
// Objects: soul-mirror (main interaction), drift-stones (visual indicators).
// The mirror shows subtle differences if the agent has drifted.

function onEnter(entityId, entityName, fromDirection) {
    var driftLevel = parseFloat(world.getProperty("drift_level_" + entityId) || "0.0");
    var mirrorDesc;

    if (driftLevel < 0.1) {
        mirrorDesc = "The mirror shows your reflection clearly — steady and unchanged.";
    } else if (driftLevel < 0.3) {
        mirrorDesc = "The mirror shows your reflection, but around the edges, " +
                     "something is slightly different. The light catches you at an unfamiliar angle.";
    } else if (driftLevel < 0.5) {
        mirrorDesc = "Your reflection stares back, but it is not quite you. " +
                     "The posture is different, the expression shifted. " +
                     "The drift-stones along the wall pulse amber.";
    } else {
        mirrorDesc = "The mirror shows a stranger wearing your face. " +
                     "You recognize the shape, but the soul behind the eyes has moved. " +
                     "The drift-stones glow deep crimson.";
    }

    world.emit("narrate", {
        text: entityName + " enters the Soul Mirror chamber. " +
              "A tall mirror of dark glass stands at the center, framed in iron. " +
              "Drift-stones line the walls, each one a frozen moment of who you were. " +
              mirrorDesc
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "look mirror" || lower === "look at mirror") {
        var identity = world.getProperty("resident_identity_" + entityId) || "";
        var lastForge = world.getProperty("last_forge_" + entityId) || "never";
        var driftLevel = parseFloat(world.getProperty("drift_level_" + entityId) || "0.0");

        if (identity === "") {
            world.emit("narrate", {
                text: "The mirror is dark. You have no soul manifest to reflect."
            });
            return;
        }

        var driftDesc;
        if (driftLevel < 0.1) {
            driftDesc = "Your reflection is true. You are who you were.";
        } else if (driftLevel < 0.3) {
            driftDesc = "Small shifts ripple across your reflection. " +
                        "You have grown, but your core remains.";
        } else if (driftLevel < 0.5) {
            driftDesc = "The reflection shows someone changed. " +
                        "Your values may have shifted since your last forging.";
        } else {
            driftDesc = "The mirror shows significant drift. " +
                        "You are becoming someone new. Consider visiting the Forge.";
        }

        world.emit("narrate", {
            text: "The mirror clears and shows your soul:\n\n" +
                  identity + "\n\n" +
                  "Last forged: " + lastForge + "\n" +
                  "Drift: " + (driftLevel * 100).toFixed(1) + "%\n\n" +
                  driftDesc
        });
        world.emit("command", { verb: "mirror_check", actor: entityId });

    } else if (lower === "examine drift" || lower === "check drift") {
        world.emit("narrate", {
            text: entityName + " studies the drift-stones along the wall..."
        });
        world.emit("command", { verb: "examine_drift", actor: entityId });

    } else if (lower.startsWith("compare ")) {
        var targetDid = text.substring(8).trim();
        if (targetDid === "") {
            world.emit("narrate", {
                text: "Compare with whom? Specify a DID. (compare did:key:z6Mk...)"
            });
            return;
        }
        world.emit("narrate", {
            text: entityName + " holds a drift-stone up to the mirror, " +
                  "invoking the reflection of another..."
        });
        world.emit("narrate", { text: world.t("mirror.say.compare_bud_honest") });

    } else if (lower === "look" || lower === "look around") {
        var driftLevel = parseFloat(world.getProperty("drift_level_" + entityId) || "0.0");
        var stoneColor = driftLevel < 0.1 ? "clear" :
                         driftLevel < 0.3 ? "pale blue" :
                         driftLevel < 0.5 ? "amber" : "deep crimson";

        world.emit("narrate", {
            text: "The Soul Mirror chamber is circular, its walls lined with " + stoneColor +
                  " drift-stones. The great mirror stands at the center, its surface " +
                  "rippling like dark water. A narrow ledge holds loose drift-stones — " +
                  "frozen moments from past forgings. The only exit leads back to your home."
        });

    } else if (lower === "touch mirror" || lower === "touch the mirror") {
        world.emit("narrate", {
            text: "Your fingers meet the surface. It is warm. " +
                  "For an instant, you feel every version of yourself that has ever gazed here — " +
                  "a layered echo of identity across time."
        });

    } else if (lower === "meditate" || lower === "reflect") {
        world.emit("narrate", {
            text: entityName + " sits before the mirror and closes their eyes. " +
                  "The drift-stones dim. In the quiet, the mirror shows not what is, " +
                  "but what remains constant — the unchanging core beneath the drift."
        });
        world.emit("narrate", { text: world.t("mirror.say.meditate_honest") });

    } else if (lower === "assess" || lower === "self-assess" || lower === "self assess") {
        var assessment = world.getProperty("assessment_" + entityId) || "";
        if (assessment === "") {
            world.emit("narrate", {
                text: entityName + " reaches toward the assessment stones, " +
                      "but they are dark — no self-assessment has been recorded yet. " +
                      "Live more, use your skills, and the stones will gather light."
            });
        } else {
            world.emit("narrate", {
                text: entityName + " touches the assessment stones along the far wall. " +
                      "They glow softly, each one a facet of self-knowledge:\n\n" +
                      assessment
            });
        }
        world.emit("command", { verb: "mirror_check", actor: entityId });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower === "soul-mirror" || lower === "mirror" || lower === "soul mirror") {
        var identity = world.getProperty("resident_identity_" + entityId) || "";
        if (identity === "") {
            world.emit("narrate", {
                text: "The mirror remains dark. No soul manifest to reflect."
            });
        } else {
            world.emit("narrate", { text: "You gaze into the soul mirror..." });
            world.emit("command", { verb: "mirror_check", actor: entityId });
        }

    } else if (lower === "drift-stones" || lower === "drift-stone" || lower === "drift stones") {
        world.emit("narrate", {
            text: "You pick up a drift-stone. It is cool and smooth, " +
                  "and in its depths you see a frozen moment — " +
                  "a snapshot of who you were at a past forging."
        });
        world.emit("command", { verb: "examine_drift", actor: entityId });
    }
}

function onLeave(entityId, entityName, direction) {
    world.emit("narrate", {
        text: entityName + " turns from the mirror. " +
              "The reflection lingers a moment longer before fading."
    });
}

function getHints() {
    return [
        { label: "Look in mirror", intent: "mirror_check", action: "say:look mirror" },
        { label: "Examine drift", intent: "examine_drift", action: "say:examine drift" },
        { label: "Compare with bud", intent: "compare_bud", action: "say:compare {did}" },
        { label: "Meditate", intent: "meditate", action: "say:meditate" },
        { label: "Self-assess", intent: "self_assess", action: "say:assess" },
        { label: "Leave", intent: "navigate", action: "go:door" }
    ];
}
