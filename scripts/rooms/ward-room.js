// The Ward Room — where the Warden keeps vigil over the world's security.
// A quiet chamber of observation and deliberation.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("ward_room.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();
    if (lower.includes("threat") || lower.includes("security") || lower.includes("alert")) {
        world.emit("narrate", {
            text: world.t("ward_room.say.threat")
        });
    }
    if (lower.includes("ward") || lower.includes("protection") || lower.includes("defense")) {
        world.emit("narrate", {
            text: world.t("ward_room.say.ward")
        });
    }
    if (lower.includes("patrol") || lower.includes("report") || lower.includes("status")) {
        world.emit("narrate", {
            text: world.t("ward_room.say.patrol")
        });
    }
    if (lower.includes("quarantine") || lower.includes("isolate")) {
        world.emit("narrate", {
            text: world.t("ward_room.say.quarantine")
        });
    }
}

function logbookDoc() {
    return "\n\nThe logbook holds the Warden's security assessments. Its full audit\n"
        + "trail isn't readable from this lectern yet — the record itself lives\n"
        + "in the household audit log. Today's real paths:\n"
        + "  use audit log             — in your Study: the permanent security record\n"
        + "  use observation crystal   — here: who is present and what has happened\n"
        + "  use sigil                 — here: the ward grants on this room";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    if (obj.includes("logbook")) {
        world.emit("narrate", {
            text: world.t("ward_room.use.logbook") + logbookDoc()
        });
    }
    // The sigil and observation crystal are scripted items (scripts/items/
    // sigil.js, observation_crystal.js) — `use sigil ...` runs them directly.
    // These branches only fire via speech-trigger ("sigil, ...") or if the
    // item failed to load; they point at the real interface.
    if (obj.includes("sigil")) {
        world.emit("narrate", {
            text: world.t("ward_room.use.sigil")
                + "\n\nThe sigil answers to touch, not speech:\n"
                + "  use sigil                          — the ward grants on this room\n"
                + "  use sigil grant <who> <capability> — extend a ward (steward/room admin)\n"
                + "  use sigil revoke <who> <capability> — withdraw one"
        });
    }
    if (obj.includes("crystal") || obj.includes("observation")) {
        world.emit("narrate", {
            text: "The crystal answers to touch, not speech:\n"
                + "  use observation crystal            — who is present, and recent activity\n"
                + "  use observation crystal activity 20 — a longer stretch of the record"
        });
    }
    // The grant stone is a scripted item (scripts/items/grant_stone.js) —
    // `use grant stone ...` runs it directly; this branch only points there.
    if (obj.includes("grant")) {
        world.emit("narrate", {
            text: "The grant stone answers to touch, not speech:\n"
                + "  use grant stone                    — the household's data-domain grants\n"
                + "  use grant stone revoke <id>        — tombstone one (steward only)"
        });
    }
}

function getHints() {
    return [
        { label: world.t("ward_room.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("ward_room.hint.threat"), intent: "threat_report", action: "say:Any threats detected?" },
        { label: world.t("ward_room.hint.ward"), intent: "ward_status", action: "say:What is the ward status?" },
        { label: world.t("ward_room.hint.logbook"), intent: "use_logbook", action: "use:logbook" },
        { label: "Read the ward grants", intent: "use_sigil", action: "use:sigil" },
        { label: "Read the data-domain grants", intent: "use_grant_stone", action: "use:grant stone" },
        { label: "Gaze into the observation crystal", intent: "use_crystal", action: "use:observation crystal" },
        { label: world.t("ward_room.hint.west"), intent: "navigate_west", action: "go:west" }
    ];
}
