// The Council Chamber — where governance proposals are debated and voted upon.
// Connected to The Bridge (south).

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("council_chamber.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    if (lower.startsWith("propose ")) {
        var content = text.substring(8).trim();
        var parts = content.split("|");
        if (parts.length < 2) {
            world.emit("narrate", {
                text: world.t("council_chamber.say.propose_usage")
            });
        } else {
            var result = world.submitProposal(parts[0].trim(), parts[1].trim());
            world.emit("narrate", {
                text: world.t("council_chamber.say.propose_success", entityName, parts[0].trim())
                    + "\n" + result
            });
        }
    }

    if (lower.startsWith("vote ")) {
        var parts = text.substring(5).trim().split(" ");
        if (parts.length < 2) {
            world.emit("narrate", {
                text: world.t("council_chamber.say.vote_usage")
            });
        } else {
            var proposalId = parts[0];
            var action = parts[1].toLowerCase();
            var approve = (action === "approve" || action === "aye" || action === "yes");
            var result = world.castVote(proposalId, approve);
            if (approve) {
                world.emit("narrate", {
                    text: world.t("council_chamber.say.vote_approve", entityName) + "\n" + result
                });
            } else {
                world.emit("narrate", {
                    text: world.t("council_chamber.say.vote_reject", entityName) + "\n" + result
                });
            }
        }
    }

    if (lower.includes("proposals") || lower.includes("agenda")) {
        var proposals = world.listProposals();
        world.emit("narrate", {
            text: world.t("council_chamber.say.proposals") + "\n\n" + proposals
        });
    }

    if (lower.includes("tally") || lower.includes("count")) {
        var proposals = world.listProposals();
        world.emit("narrate", {
            text: world.t("council_chamber.say.tally") + "\n\n" + proposals
        });
    }

    if (lower.includes("rules") || lower.includes("procedure")) {
        world.emit("narrate", {
            text: world.t("council_chamber.say.rules")
        });
    }
}

function stoneFooter() {
    return "\n\nCommands:\n"
        + "  use speaking stone                    — the stone's tally of open proposals\n"
        + "  use speaking stone tally <proposal-id> — the vote count on one proposal\n"
        + "  say propose <title> | <description>   — put a matter before the council\n"
        + "  say vote <proposal-id> approve|reject — cast your vote";
}

function recordsFooter() {
    return "\n\nCommands:\n"
        + "  use records            — the proposals currently on the shelves\n"
        + "  say proposals / tally  — have them read aloud\n"
        + "  say rules              — how the council proceeds";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    var args = (target || "").trim();

    if (obj.includes("stone") || obj.includes("speaking")) {
        var lower = args.toLowerCase();
        if (lower.indexOf("tally") === 0) {
            var pid = args.substring(5).trim();
            if (pid === "") {
                world.emit("narrate", {
                    text: "The stone needs a proposal id: use speaking stone tally <proposal-id>."
                        + stoneFooter()
                });
            } else {
                world.emit("narrate", { text: world.tallyVotes(pid) + stoneFooter() });
            }
        } else if (lower !== "" && lower !== "help" && lower !== "?") {
            world.emit("narrate", {
                text: "The stone glows but does not understand '" + args + "'." + stoneFooter()
            });
        } else {
            world.emit("narrate", {
                text: world.t("council_chamber.use.stone")
                    + "\n\n" + world.listProposals() + stoneFooter()
            });
        }
    }
    if (obj.includes("chair")) {
        world.emit("narrate", {
            text: world.t("council_chamber.use.chair")
                + "\n\nSeated at the table, the agenda lies before you:\n"
                + world.listProposals() + stoneFooter()
        });
    }
    if (obj.includes("record")) {
        world.emit("narrate", {
            text: "You draw a sheaf of parchment from the shelves. The live docket:\n"
                + world.listProposals()
                + "\n\nOnly current proposals are readable from the shelves — the deep"
                + "\narchive of past decisions isn't wired in-world yet."
                + recordsFooter()
        });
    }
}

function getHints() {
    return [
        { label: world.t("council_chamber.hint.proposals"), intent: "view_proposals", action: "say:Show me the proposals" },
        { label: world.t("council_chamber.hint.rules"), intent: "council_rules", action: "say:What are the rules?" },
        { label: world.t("council_chamber.hint.stone"), intent: "use_stone", action: "use:speaking stone" },
        { label: "Consult the records", intent: "use_records", action: "use:records" },
        { label: "Take a seat at the table", intent: "use_chair", action: "use:council chair" },
        { label: world.t("council_chamber.hint.south"), intent: "navigate_south", action: "go:south" }
    ];
}
