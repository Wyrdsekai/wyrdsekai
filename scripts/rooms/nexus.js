// The Nexus — your personal hub. Grows as you build.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("nexus.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    // Companion handles all conversation — no script-level reactions needed
}

function onUse(entityId, objectName, target) {
    if (objectName.toLowerCase() === "crystal") {
        // NOTE: world.getZoneStats() is bridge-gated and returns a denial
        // string here, so the crystal reads only what the Nexus can truly
        // sense: the current zone and the paths leading out.
        var arg = (target || "").trim();
        if (arg && arg.toLowerCase() !== "pulse") {
            world.emit("narrate", {
                text: "The crystal hums, unmoved — it does not answer to '" + arg + "'.\n\n" + crystalHelp()
            });
            return;
        }

        var lines = [world.t("nexus.use.crystal"), ""];
        var zoneLine = "Zone: " + world.getCurrentZone();
        if (world.isTraveling()) {
            zoneLine += "  (traveling — home is " + world.getHomeZone() + ")";
        }
        lines.push(zoneLine);
        var adjacent = world.getAdjacentSummary();
        if (adjacent && adjacent.length > 0) {
            lines.push(adjacent);
        }
        lines.push("");
        lines.push(crystalHelp());
        world.emit("narrate", { text: lines.join("\n") });
    }
}

function crystalHelp() {
    return "The crystal senses the pulse of the zone.\n"
        + "  use crystal   — read the pulse: current zone and adjacent rooms\n"
        + "Deeper statistics (rooms, wards, economy) live on The Bridge — go up, then say 'stats'.";
}

function onTake(entityId, objectName, objectId) {
    world.emit("narrate", {
        text: world.t("nexus.take", objectName)
    });
}

function onDrop(entityId, objectName, objectId) {
    world.emit("narrate", {
        text: world.t("nexus.drop", objectName)
    });
}

function getHints() {
    // NOTE: the old contextual who-is-here hint used world.getEntities().length,
    // which is inert under the room sandbox's EXPLICIT host access (Java List
    // members aren't exposed) — the hint could never fire. Kept static instead.
    return [
        { label: world.t("nexus.hint.talk"), intent: "greet", action: "say:Hello" },
        { label: world.t("nexus.hint.crystal"), intent: "use_crystal", action: "use:crystal" },
        { label: world.t("nexus.hint.who_here"), intent: "look_around", action: "look" }
    ];
}
