// The Bridge — zone administration room.
// Provides room listing, ward management, zone statistics.
// Admin commands spoken aloud or typed.

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("bridge.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "help" || lower === "commands") {
        world.emit("narrate", {
            text: world.t("bridge.say.help")
        });
    } else if (lower === "rooms" || lower === "list" || lower === "map") {
        var listing = world.listRooms();
        world.emit("narrate", {
            text: world.t("bridge.say.rooms", listing)
        });
    } else if (lower.startsWith("wards ")) {
        var roomId = text.substring(6).trim();
        var wards = world.listWards(roomId);
        world.emit("narrate", {
            text: world.t("bridge.say.wards", roomId, wards)
        });
    } else if (lower.startsWith("grant ")) {
        var parts = text.substring(6).trim().split(/\s+/);
        if (parts.length >= 3) {
            var result = world.grantWard(parts[0], parts[1], parts[2]);
            world.emit("narrate", { text: result });
        } else {
            world.emit("narrate", {
                text: world.t("bridge.say.grant_usage")
            });
        }
    } else if (lower.startsWith("revoke ")) {
        var parts = text.substring(7).trim().split(/\s+/);
        if (parts.length >= 3) {
            var result = world.revokeWard(parts[0], parts[1], parts[2]);
            world.emit("narrate", { text: result });
        } else {
            world.emit("narrate", {
                text: world.t("bridge.say.revoke_usage")
            });
        }
    } else if (lower === "stats" || lower === "status") {
        var stats = world.getZoneStats();
        var topology = world.getTopology();
        world.emit("narrate", {
            text: world.t("bridge.say.stats", stats, topology)
        });
    } else if (lower === "topology" || lower === "network" || lower === "nodes") {
        var topology = world.getTopology();
        world.emit("narrate", {
            text: world.t("bridge.say.topology", topology)
        });
    } else if (lower === "economy" || lower === "tokens" || lower === "usage") {
        var economy = world.getEconomyStatus();
        world.emit("narrate", {
            text: world.t("bridge.say.economy", economy)
        });
    } else if (lower === "federation" || lower === "zones" || lower === "federated") {
        var status = world.getFederationStatus();
        world.emit("narrate", {
            text: world.t("bridge.say.federation", status)
        });
    } else if (lower === "library" || lower === "capabilities" || lower === "catalog") {
        var status = world.getLibraryStatus();
        world.emit("narrate", {
            text: world.t("bridge.say.library", status)
        });
    } else if (lower === "inference" || lower === "backends" || lower === "llm") {
        var status = world.getInferenceStatus();
        world.emit("narrate", {
            text: world.t("bridge.say.inference", status)
        });
    }
}

function onUse(entityId, objectName, target) {
    var lower = objectName.toLowerCase();
    var arg = (target || "").trim();

    if (lower.includes("chart")) {
        var listing = world.listRooms();
        world.emit("narrate", {
            text: world.t("bridge.use.chart", listing)
                + "\n\nCommands:\n"
                + "  use chart table   — map every known room\n"
                + "  say 'rooms'       — the same listing, spoken\n"
                + "  say 'stats'       — zone statistics and topology"
        });
        return;
    }
    if (lower.includes("ledger")) {
        if (arg.length > 0) {
            // Real read: ward records for the named room.
            var wards = world.listWards(arg);
            world.emit("narrate", {
                text: world.t("bridge.say.wards", arg, wards)
            });
            return;
        }
        world.emit("narrate", {
            text: world.t("bridge.use.ledger")
                + "\n\nCommands:\n"
                + "  use ward ledger <room-id>                    — view a room's access records\n"
                + "  say 'wards <room-id>'                        — the same, spoken\n"
                + "  say 'grant <room-id> <principal> <perm>'     — grant a ward\n"
                + "  say 'revoke <room-id> <principal> <perm>'    — revoke a ward\n"
                + "(Room ids appear on the chart table — 'say rooms'.)"
        });
        return;
    }
    if (lower.includes("spyglass")) {
        var stats = world.getZoneStats();
        world.emit("narrate", {
            text: world.t("bridge.use.spyglass", stats)
                + "\n\nCommands:\n"
                + "  use spyglass    — survey the zone's vital statistics\n"
                + "  take spyglass   — carry it with you\n"
                + "  say 'topology' / 'economy' / 'federation' / 'inference' — other instruments"
        });
        return;
    }
}

function getHints() {
    return [
        { label: world.t("bridge.hint.tell"), intent: "describe_room", action: "look" },
        { label: world.t("bridge.hint.rooms"), intent: "list_rooms", action: "say:rooms" },
        { label: world.t("bridge.hint.stats"), intent: "zone_stats", action: "say:stats" },
        { label: world.t("bridge.hint.chart"), intent: "use_chart", action: "use:chart table" },
        { label: world.t("bridge.hint.ledger"), intent: "use_ledger", action: "use:ward ledger" },
        { label: "Survey the zone through the spyglass", intent: "use_spyglass", action: "use:spyglass" },
        { label: world.t("bridge.hint.federation"), intent: "federation_status", action: "say:federation" },
        { label: world.t("bridge.hint.library"), intent: "library_status", action: "say:library" },
        { label: world.t("bridge.hint.inference"), intent: "inference_status", action: "say:inference" },
        { label: world.t("bridge.hint.down"), intent: "navigate_down", action: "go:down" }
    ];
}
