// The Docks — zone federation interface.
// Manages bilateral agreements, zone manifests, and agent transit.
// Federation commands spoken aloud or typed.

function onEnter(entityId, entityName, fromDirection) {
    rememberName(entityId, entityName);
    world.emit("narrate", {
        text: world.t("docks.enter", entityName)
    });
}

// --- name ledger -----------------------------------------------------------
// The onUse hook only receives entityId, but requestTransit wants a display
// name (it travels with you to the remote zone). We remember names as
// entities enter the Docks so the federation portal can be used directly.

function rememberName(entityId, entityName) {
    try {
        var raw = world.getProperty("docks.names");
        var names = raw ? JSON.parse(raw) : {};
        names[entityId] = entityName;
        var keys = Object.keys(names);
        while (keys.length > 64) {
            delete names[keys.shift()];
        }
        world.setProperty("docks.names", JSON.stringify(names));
    } catch (e) {
        // best-effort; portal falls back to entityId
    }
}

function lookupName(entityId) {
    try {
        var raw = world.getProperty("docks.names");
        if (raw) {
            var names = JSON.parse(raw);
            if (names[entityId]) return names[entityId];
        }
    } catch (e) { /* fall through */ }
    return entityId;
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("docks.say.help")
        });
    } else if (lower === "manifest" || lower === "status" || lower === "federation") {
        var status = world.getFederationStatus();
        world.emit("narrate", {
            text: world.t("docks.say.manifest", status)
        });
    } else if (lower === "arrivals" || lower === "visitors") {
        var visitors = world.listTransitAgents();
        world.emit("narrate", {
            text: world.t("docks.say.arrivals", visitors)
        });
    } else if (lower.startsWith("propose ")) {
        var zoneId = text.substring(8).trim();
        var result = world.proposeFederation(zoneId);
        world.emit("narrate", { text: result });
    } else if (lower.startsWith("accept ")) {
        var zoneId = text.substring(7).trim();
        var result = world.acceptFederation(zoneId);
        world.emit("narrate", { text: result });
    } else if (lower.startsWith("revoke ")) {
        var zoneId = text.substring(7).trim();
        var result = world.revokeFederation(zoneId);
        world.emit("narrate", { text: result });
    } else if (lower.startsWith("travel ")) {
        doTravel(entityId, entityName, text.substring(7).trim());
    } else if (lower.includes("ship") || lower.includes("portal")) {
        world.emit("narrate", {
            text: world.t("docks.say.ship")
        });
    } else if (lower.includes("connect") || lower.includes("zone")) {
        world.emit("narrate", {
            text: world.t("docks.say.connect")
        });
    }
}

// --- travel flow (shared by say:travel and use:federation portal) ----------

function doTravel(entityId, entityName, rawInput) {
    var rawLower = rawInput.toLowerCase();

    // `home` is a reserved keyword, not a literal
    // zone. As a directive it means "return to origin" — but that's only
    // meaningful for a proxied visitor. A local resident who types
    // `travel home` just gets narrated at. A session-proxy layer handles
    // the actual return; docks.js only detects the keyword and bails out
    // of the normal travel path.
    if (rawLower === "home" || rawLower === "self" || rawLower === "me"
            || rawLower === "here" || rawLower === "origin") {
        world.emit("narrate", {
            text: world.t("docks.say.travel_home", entityName)
        });
        return;
    }

    // Resolve via the naming service (alias:label, alias, label, or DID).
    // The resolver produces a canonical form we pass down to requestTransit,
    // which today still takes a legacy zoneId string. Wave 4 migrates
    // requestTransit to accept the canonical form directly.
    var resolvedStr = world.resolveZone(rawInput);
    var resolved;
    try {
        resolved = JSON.parse(resolvedStr);
    } catch (parseErr) {
        world.emit("narrate", { text: resolvedStr });
        return;
    }
    if (!resolved.ok) {
        world.emit("narrate", { text: resolved.message || "Unknown zone." });
        return;
    }

    // Transit request — for now still pass the label; the canonical form
    // is available in resolved.canonical for the wave-4 migration.
    var zoneId = resolved.label;
    world.emit("narrate", {
        text: world.t("docks.say.travel", zoneId)
    });
    var resultStr = world.requestTransit(entityId, entityName, zoneId);
    try {
        var result = JSON.parse(resultStr);
        if (result.allowed && result.token) {
            world.emit("narrate", {
                text: "The portal shimmers and opens... stepping through to zone '" + zoneId + "'."
            });
            var transitResult = world.startTransit(entityId, zoneId, result.token);
            if (transitResult !== "ok") {
                world.emit("narrate", {
                    text: "The portal flickers and fades. " + transitResult
                });
            }
        } else {
            world.emit("narrate", {
                text: result.message || "Transit denied."
            });
        }
    } catch (e) {
        world.emit("narrate", { text: resultStr });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();

    if (lower.includes("compass")) {
        // NOTE: `use compass` is normally pre-empted by the scripted item
        // scripts/items/compass.js (RoomActor resolves furnishing items by
        // display name before the room hook). This branch is the fallback
        // when that item isn't loaded — it still does the real thing:
        // points toward home.
        var line = "Home zone: '" + world.getHomeZone() + "'. You are in zone: '" + world.getCurrentZone() + "'.";
        if (world.isTraveling()) {
            line += "\nThe needle strains toward home — say 'travel home' at these Docks to return.";
        } else {
            line += "\nThe needle rests. You are already home.";
        }
        world.emit("narrate", {
            text: world.t("docks.use.compass") + "\n\n" + line
                + "\n\nCommands:\n  use compass   — read your bearing (current zone vs home)\n  take compass  — carry it with you"
        });
        return;
    }

    if (lower.includes("manifest")) {
        var status = world.getFederationStatus();
        world.emit("narrate", {
            text: world.t("docks.use.manifest", status)
                + "\n\nCommands:\n"
                + "  use manifest board        — list federation agreements and zone connections\n"
                + "  say 'propose <zone-id>'   — propose a bilateral agreement\n"
                + "  say 'accept <zone-id>'    — accept an inbound proposal\n"
                + "  say 'revoke <zone-id>'    — revoke an agreement"
        });
        return;
    }

    if (lower.includes("portal")) {
        var arg = (target || "").trim();
        if (arg.length > 0) {
            // Real travel through the portal — same flow as say 'travel <zone>'.
            doTravel(entityId, lookupName(entityId), arg);
            return;
        }
        var fedStatus = world.getFederationStatus();
        world.emit("narrate", {
            text: world.t("docks.use.portal")
                + "\n\nCurrently connected:\n" + fedStatus
                + "\n\nCommands:\n"
                + "  use federation portal <zone>   — step through to a federated zone\n"
                + "  say 'travel <zone>'            — same journey, spoken to the Dockmaster\n"
                + "  say 'travel home'              — return to your origin (visitors)\n"
                + "  say 'manifest'                 — see which zones are open"
        });
        return;
    }

    if (lower.includes("logbook")) {
        var fed = world.getFederationStatus();
        var arrivals = world.listTransitAgents();
        world.emit("narrate", {
            text: world.t("docks.use.logbook", fed + "\n\nArrivals & departures (current visitors):\n" + arrivals)
                + "\n\nCommands:\n"
                + "  use dockmaster's logbook   — agreements plus current arrivals\n"
                + "  say 'arrivals'             — just the visitor ledger"
        });
        return;
    }
}

function getHints() {
    return [
        { label: world.t("docks.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("docks.hint.manifest"), intent: "show_manifest", action: "say:manifest" },
        { label: world.t("docks.hint.arrivals"), intent: "check_arrivals", action: "say:arrivals" },
        { label: world.t("docks.hint.board"), intent: "use_manifest", action: "use:manifest board" },
        { label: "Step through the federation portal", intent: "use_portal", action: "use:federation portal" },
        { label: "Open the dockmaster's logbook", intent: "use_logbook", action: "use:dockmaster's logbook" },
        { label: "Consult the compass", intent: "use_compass", action: "use:compass" },
        { label: world.t("docks.hint.compass"), intent: "take_compass", action: "take:compass" },
        { label: world.t("docks.hint.west"), intent: "navigate_west", action: "go:west" }
    ];
}
