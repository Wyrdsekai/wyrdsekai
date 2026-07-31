// The Trading Post — where goods and services change hands.
// Adjacent to The Docks, serving as the zone's marketplace.
// State persisted via world.setProperty/getProperty (survives script re-evaluation).

function loadItems() {
    var raw = world.getProperty("trade.items");
    return raw ? JSON.parse(raw) : {};
}

function saveItems(items) {
    world.setProperty("trade.items", JSON.stringify(items));
}

function loadNextId() {
    var raw = world.getProperty("trade.nextId");
    return raw ? parseInt(raw, 10) : 1;
}

function saveNextId(id) {
    world.setProperty("trade.nextId", String(id));
}

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: world.t("trading_post.enter", entityName)
    });
}

function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase();

    // Thought-form specific listings filter — surfaces only form-type items
    // Full browse still lists everything.
    if (lower === "forms" || lower === "list forms" || lower === "browse forms") {
        var postedItems = loadItems();
        var formIds = [];
        for (var fid in postedItems) {
            var itm = postedItems[fid];
            if (itm && itm.description && itm.description.indexOf("[thought-form]") === 0) {
                formIds.push(fid);
            }
        }
        if (formIds.length === 0) {
            world.emit("narrate", {
                text: "No thought forms listed right now. To post one: 'post <name> | <description> | <price>'."
            });
        } else {
            var out = "Thought forms for sale:\n";
            for (var i = 0; i < formIds.length; i++) {
                var fid2 = formIds[i];
                var item = postedItems[fid2];
                // description is "[thought-form] <displayLine>\n\nexample: …\n---payload---\n…"
                var preview = item.description.split("\n---payload---\n")[0];
                out += "  " + fid2 + " — " + preview + " (seller: " + item.seller + ")\n";
            }
            world.emit("narrate", { text: out });
        }
        return;
    }

    if (lower.includes("browse") || lower.includes("list") || lower.includes("wares")) {
        var postedItems = loadItems();
        var items = Object.keys(postedItems);
        if (items.length === 0) {
            world.emit("narrate", {
                text: world.t("trading_post.say.browse_empty")
            });
        } else {
            var listing = world.t("trading_post.say.browse_header") + "\n";
            for (var id in postedItems) {
                var item = postedItems[id];
                listing += world.t("trading_post.say.browse_item", id, item.name, item.description, item.seller, item.price) + "\n";
            }
            world.emit("narrate", { text: listing });
        }
    }

    if (lower.startsWith("post ") || lower.startsWith("sell ")) {
        // Format: post <name> | <description> | <price>
        var parts = text.substring(text.indexOf(" ") + 1).split("|");
        if (parts.length < 3) {
            world.emit("narrate", {
                text: world.t("trading_post.say.post_usage")
            });
        } else {
            var name = parts[0].trim();
            var desc = parts[1].trim();
            var price = parseInt(parts[2].trim()) || 10;
            var nextId = loadNextId();
            var itemId = "item-" + nextId;
            var postedItems = loadItems();
            postedItems[itemId] = {
                name: name,
                description: desc,
                price: price,
                seller: entityName,
                sellerId: entityId,
                status: "available"
            };
            saveItems(postedItems);
            saveNextId(nextId + 1);
            world.emit("narrate", {
                text: entityName + " posts " + name + " for " + price + " credits. The notice board updates with a new entry."
            });
        }
    }

    if (lower.startsWith("acquire ") || lower.startsWith("buy ")) {
        var targetId = text.substring(text.indexOf(" ") + 1).trim();
        var postedItems = loadItems();
        var item = postedItems[targetId];
        if (!item) {
            world.emit("narrate", {
                text: world.t("trading_post.say.acquire_not_found")
            });
        } else if (item.status !== "available") {
            world.emit("narrate", {
                text: world.t("trading_post.say.acquire_unavailable")
            });
        } else if (item.sellerId === entityId) {
            world.emit("narrate", {
                text: world.t("trading_post.say.acquire_own", targetId)
            });
        } else {
            item.status = "sold";
            saveItems(postedItems);
            world.emit("narrate", {
                text: world.t("trading_post.say.acquire_success", entityName, item.name, item.seller, item.price)
            });
        }
    }

    if (lower.startsWith("withdraw ")) {
        var targetId = text.substring(text.indexOf(" ") + 1).trim();
        var postedItems = loadItems();
        var item = postedItems[targetId];
        if (!item) {
            world.emit("narrate", {
                text: world.t("trading_post.say.withdraw_not_found")
            });
        } else if (item.sellerId !== entityId) {
            world.emit("narrate", {
                text: world.t("trading_post.say.withdraw_not_seller")
            });
        } else {
            delete postedItems[targetId];
            saveItems(postedItems);
            world.emit("narrate", {
                text: world.t("trading_post.say.withdraw_success", entityName, item.name)
            });
        }
    }

    if (lower.includes("price") || lower.includes("appraise")) {
        world.emit("narrate", {
            text: world.t("trading_post.say.price")
        });
    }
}

function boardFooter() {
    return "\n\nCommands (spoken to the room):\n"
        + "  say post <name> | <description> | <price>  — pin a new offering\n"
        + "  say acquire <item-id>                      — buy a posted item\n"
        + "  say withdraw <item-id>                     — take down your own posting\n"
        + "  say browse / say forms                     — read the listings aloud";
}

function bellFooter() {
    return "\n\nCommands:\n"
        + "  use brass bell   — ring it; the chime carries to everyone in this room\n"
        + "It does not summon anyone beyond the trading post — to reach someone\n"
        + "elsewhere, go find them or leave a posting on the notice board.";
}

function scaleFooter() {
    return "\n\nCommands:\n"
        + "  use merchant's scale                — the state of the market, weighed\n"
        + "  use merchant's scale <item-id>      — appraise a posted item\n"
        + "  use merchant's scale reputation     — the standing of the zone's traders";
}

function onUse(entityId, objectName, target) {
    var obj = objectName.toLowerCase();
    var args = (target || "").trim();

    if (obj.includes("bell")) {
        world.emit("narrate", {
            text: world.t("trading_post.use.bell") + bellFooter()
        });
    }
    if (obj.includes("board") || obj.includes("notice")) {
        var postedItems = loadItems();
        var ids = Object.keys(postedItems);
        var count = ids.filter(function(k) { return postedItems[k].status === "available"; }).length;
        var text = world.t("trading_post.use.board", count);
        if (ids.length > 0) {
            text += "\n";
            for (var id in postedItems) {
                var item = postedItems[id];
                text += "\n  " + id + " — " + item.name + " (" + item.price + " credits, "
                    + item.seller + ", " + item.status + ")";
            }
        }
        world.emit("narrate", { text: text + boardFooter() });
    }
    if (obj.includes("scale")) {
        var lower = args.toLowerCase();
        if (lower === "reputation") {
            world.emit("narrate", { text: world.getReputationSummary() + scaleFooter() });
        } else if (lower !== "" && lower !== "help" && lower !== "?") {
            var postedItems = loadItems();
            var item = postedItems[args];
            if (item) {
                world.emit("narrate", {
                    text: "The scale settles: " + item.name + " — asking " + item.price
                        + " credits, offered by " + item.seller + " (" + item.status + ").\n"
                        + (item.description ? "  " + item.description : "") + scaleFooter()
                });
            } else {
                world.emit("narrate", {
                    text: "The scale finds nothing under '" + args
                        + "' — say browse to see the posted item ids." + scaleFooter()
                });
            }
        } else {
            var postedItems = loadItems();
            var total = 0, available = 0;
            for (var k in postedItems) {
                if (postedItems[k].status === "available") {
                    available++;
                    total += postedItems[k].price || 0;
                }
            }
            world.emit("narrate", {
                text: "The brass scales weigh the market: " + available
                    + " item(s) currently offered, " + total + " credits asked in total."
                    + scaleFooter()
            });
        }
    }
}

function getHints() {
    return [
        { label: world.t("trading_post.hint.browse"), intent: "browse_items", action: "say:browse" },
        { label: world.t("trading_post.hint.bell"), intent: "ring_bell", action: "use:brass bell" },
        { label: world.t("trading_post.hint.board"), intent: "check_board", action: "use:notice board" },
        { label: "Weigh the market on the scale", intent: "use_scale", action: "use:merchant's scale" },
        { label: world.t("trading_post.hint.price"), intent: "price_info", action: "say:How do prices work?" },
        { label: world.t("trading_post.hint.south"), intent: "navigate_south", action: "go:south" }
    ];
}
