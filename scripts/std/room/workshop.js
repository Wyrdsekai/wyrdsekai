// std/room/workshop.js — Creation space template.
// Workbench, template catalog, blueprint rack. Craft actions.
// Creator configures: name, description, theme, specialization.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "workshop";
room._name = "Workshop";
room._description = "Tool racks line the walls. A workbench dominates the center, ready for crafting.";
room._theme = "";
room._specialization = "general"; // "general", "smithing", "scripting", "alchemy", etc.

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_specialization = function(s) { room._specialization = s; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "catalog" || lower === "templates") {
        // The live registry read is the template_catalog scripted item —
        // point there rather than miming a listing.
        world.emit("narrate", {
            text: "The catalog shimmers. To actually read it: 'use template catalog' " +
                  "(or 'use template catalog search <word>')."
        });
    } else if (lower.startsWith("craft ")) {
        var what = text.substring(6).trim();
        // Honest: crafting is performed by a companion at the workbench
        // (workbench_submit); a bare human 'craft' is a request, not a result.
        world.emit("narrate", {
            text: entityName + " lays out '" + what + "' on the workbench. If a " +
                  "companion is present, she may take up the crafting — the " +
                  "workbench itself validates, tests, and packages what she submits."
        });
    }
}

function onUse(entityId, objectName, target) {
    // NOTE: "template catalog" is a scripted item
    // (scripts/items/template_catalog.js) — RoomActor resolves it by
    // normalized display name and its invoke() pre-empts this hook.
    var name = (objectName || "").toLowerCase();
    if (name === "workbench") {
        world.emit("narrate", {
            text: "The workbench surface is scarred by years of crafting.\n\n" +
                  "Commands:\n" +
                  "  say 'craft <name>'                   — lay out a crafting request " +
                  "(a present companion does the making)\n" +
                  "  use template catalog                 — browse everything craftable\n" +
                  "  use template catalog search <word>   — find a template\n" +
                  "Full workshop verbs (code/shape/skills/forms/…) live in the " +
                  "foundation Workshop off the Terminal."
        });
    } else if (name === "blueprint rack" || name === "blueprint-rack") {
        world.emit("narrate", {
            text: "Standard blueprints for common items. The rack is an index, " +
                  "honestly — the live registry behind it is the template catalog:\n" +
                  "  use template catalog                 — browse everything craftable\n" +
                  "  use template catalog search <word>   — find a blueprint"
        });
    }
}

function getHints() {
    return [
        { label: "Browse templates", intent: "browse_catalog", action: "use:template catalog" },
        { label: "Use workbench", intent: "use_workbench", action: "use:workbench" },
        { label: "Browse the blueprint rack", intent: "blueprints", action: "use:blueprint rack" },
        { label: "Craft something", intent: "craft", action: "say:craft" }
    ];
}
