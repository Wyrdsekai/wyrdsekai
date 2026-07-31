// Market furnishing — market board ("market-board" RoomObject in the std
// market room template; display name "market board" → normalized linkage
// "market_board").
//
// The listings side of the market: current offers and recent trade history,
// read through world.market.list_listings() / world.market.history() — the
// same TradingPostService data the Trading Post runs on. Bare `use market
// board` renders the listings AND the command list.
//
// Posting, withdrawing, and buying are stall-work, not board-work — the
// merchant stall beside this board carries those (world.market.list_offer /
// cancel / accept). The board points there honestly.
exports.manifest = {
  name: "market_board",
  version: "1.0.0",
  description: "A broad board of current market listings and recent trades, chalk-fresh from the trading ledger.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} scans the market board, chalk-dust bright where the newest listings went up."
  },
  commands: [
    { label: "Browse listings", args: "" },
    { label: "Recent trades", args: "history" },
    { label: "Board help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use market board            — current listings",
    "  use market board history    — recent trades",
    "  use market board help       — this help",
    "To post, withdraw, or buy, work the merchant stall:",
    "  use merchant stall offer <item> <price> | cancel <listing> | buy <listing>"
  ].join("\n");
}

function renderListings() {
  var listings = null;
  try { listings = world.market.list_listings(); } catch (e) { listings = null; }
  if (!listings || listings.length === 0) {
    return "The board is freshly wiped — no listings are up, or the trading "
      + "ledger isn't bound on this surface.";
  }
  var lines = ["Chalked on the board, " + listings.length
    + (listings.length === 1 ? " listing:" : " listings:")];
  for (var i = 0; i < listings.length; i++) {
    var l = listings[i];
    var line = "  " + (l.name || l.listingId || "?") + " — " + (l.price || 0) + " CU";
    if (l.seller) line += "  (seller: " + l.seller + ")";
    if (l.status && l.status !== "AVAILABLE") line += "  [" + l.status + "]";
    lines.push(line);
    if (l.description) lines.push("     " + l.description);
    if (l.listingId) lines.push("     listing: " + l.listingId);
  }
  return lines.join("\n");
}

function renderHistory() {
  var hist = null;
  try { hist = world.market.history(10); } catch (e) { hist = null; }
  if (!hist || hist.length === 0) {
    return "The trade column is blank — no trades recorded yet on this surface.";
  }
  var lines = ["Recent trades, oldest chalk fading:"];
  for (var i = 0; i < hist.length; i++) {
    var h = hist[i];
    lines.push("  " + (h.name || h.listingId || "?")
      + (h.price ? " — " + h.price + " CU" : "")
      + (h.buyer ? "  → " + h.buyer : "")
      + (h.status ? "  [" + h.status + "]" : ""));
  }
  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The market board lists what's for sale and what has sold." + usageFooter() };
  }
  if (args === "history" || args === "trades") {
    return { ok: true, text: renderHistory() + usageFooter() };
  }
  if (args === "" || args === "listings" || args === "browse") {
    return { ok: true, text: renderListings() + usageFooter() };
  }
  return { ok: true, text: "The board has no column for '" + args + "'." + usageFooter() };
}
