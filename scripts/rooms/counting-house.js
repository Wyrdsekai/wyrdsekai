// The Counting House — resource metering and economy overview.
// Shows inference token usage via world.getEconomyStatus().

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("counting_house.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();
    if (lower.includes("ledger") || lower.includes("status") || lower.includes("usage")
            || lower.includes("tokens") || lower.includes("economy")) {
        var status = world.getEconomyStatus();
        world.emit("narrate", {
            text: world.t("counting_house.say.ledger", status)
        });
    }
    if (lower.includes("cost") || lower.includes("budget") || lower.includes("balance")) {
        world.emit("narrate", {
            text: world.t("counting_house.say.cost")
        });
    }
}

function ledgerFooter() {
    return "\n\nCommands:\n"
        + "  use master ledger              — today's page: token usage and the zone economy\n"
        + "  use master ledger reputation   — the standing of every entity, as the ledger records it\n"
        + "  say ledger / status / usage    — have the page read aloud";
}

function abacusFooter() {
    return "\n\nCommands:\n"
        + "  use crystal abacus             — the beads' current tally (compute usage)\n"
        + "  use crystal abacus help        — this help\n"
        + "The abacus counts the same tallies the master ledger records.";
}

function tokenDoc() {
    return "A Compute Unit (CU) — the coin of this house. Every inference, every\n"
        + "spell of computation, is metered in these. This sample is a keepsake:\n"
        + "take it with 'take sample token'. Spending happens on its own as you\n"
        + "act in the world; the master ledger records every debit.\n\n"
        + "Commands:\n"
        + "  take sample token       — pocket the token\n"
        + "  use master ledger       — see where the CUs actually go";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    var args = (target || "").trim().toLowerCase();

    if (obj.includes("ledger")) {
        if (args === "reputation") {
            world.emit("narrate", { text: world.getReputationSummary() + ledgerFooter() });
        } else if (args === "" || args === "help" || args === "?") {
            var status = world.getEconomyStatus();
            world.emit("narrate", {
                text: world.t("counting_house.use.ledger", status) + ledgerFooter()
            });
        } else {
            world.emit("narrate", {
                text: "The ledger has no page for '" + target + "'." + ledgerFooter()
            });
        }
    }
    if (obj.includes("abacus")) {
        if (args !== "" && args !== "help" && args !== "?") {
            world.emit("narrate", {
                text: "The beads do not answer to '" + target + "'." + abacusFooter()
            });
        } else {
            var status = world.getEconomyStatus();
            world.emit("narrate", {
                text: world.t("counting_house.use.abacus", status) + abacusFooter()
            });
        }
    }
    if (obj.includes("token")) {
        world.emit("narrate", { text: tokenDoc() });
    }
}

function getHints() {
    return [
        { label: world.t("counting_house.hint.ledger"), intent: "use_ledger", action: "use:master ledger" },
        { label: world.t("counting_house.hint.abacus"), intent: "use_abacus", action: "use:crystal abacus" },
        { label: world.t("counting_house.hint.cost"), intent: "check_usage", action: "say:Show me the usage" },
        { label: world.t("counting_house.hint.token"), intent: "take_token", action: "take:sample token" },
        { label: world.t("counting_house.hint.north"), intent: "navigate_north", action: "go:north" }
    ];
}
