// std/room/market.js — Trade and economy template.
// Listings, trade actions, reputation display.
// Creator configures: name, description, theme, market type.

var room = room || {};  // config holder; survives only within one evaluation
room._type = "market";
room._name = "Market";
room._description = "Stalls and display cases fill the space. The buzz of commerce fills the air.";
room._theme = "";
room._market_type = "general"; // "general", "knowledge", "tools", "aspects"

room.set_name = function(n) { room._name = n; };
room.set_description = function(d) { room._description = d; };
room.set_theme = function(t) { room._theme = t; };
room.set_market_type = function(m) { room._market_type = m; };

function onEnter(entityId, entityName, fromDirection) {
    world.emit("narrate", {
        text: entityName + " enters " + room._name + ". " + room._description
    });
}

// NOTE: "market board" and "merchant stall" are scripted items
// (scripts/items/market_board.js, scripts/items/merchant_stall.js) backed
// by the real trading ledger (world.market.*). RoomActor resolves them by
// normalized display name, so their invoke() handles `use` directly. The
// say-handlers below route to those real surfaces instead of miming trades.
function onSay(entityId, entityName, text) {
    if (world.isAgent(entityId)) return;
    var lower = text.toLowerCase().trim();

    if (lower === "listings" || lower === "browse") {
        world.emit("narrate", {
            text: "The chalk columns are read from the board itself: 'use market " +
                  "board' (or 'use market board history' for recent trades)."
        });
    } else if (lower.startsWith("post ")) {
        world.emit("narrate", {
            text: entityName + " looks for somewhere to post — the stall-keeper " +
                  "waves them over. Posting is stall-work, done for real against the " +
                  "trading ledger:\n" +
                  "  use merchant stall offer <item> <price>\n" +
                  "  use merchant stall cancel <listingId>\n" +
                  "  use merchant stall buy <listingId>"
        });
    }
}

function getHints() {
    return [
        { label: "Browse the market board", intent: "browse_market", action: "use:market board" },
        { label: "Recent trades", intent: "trade_history", action: "use:market board|history" },
        { label: "Work the merchant stall", intent: "post_listing", action: "use:merchant stall" },
        { label: "Look around", intent: "examine_room", action: "look" }
    ];
}
