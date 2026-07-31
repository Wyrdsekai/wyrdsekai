// Market furnishing — merchant stall ("stall" RoomObject in the std market
// room template; display name "merchant stall" → normalized linkage
// "merchant_stall").
//
// The working side of the market: post an offer, withdraw one, buy one —
// world.market.list_offer / cancel / accept against the same
// TradingPostService the Trading Post uses. Bare `use merchant stall`
// explains itself and lists the exact commands.
//
// Browsing is board-work: the market board beside the stall carries
// listings and history reads.
exports.manifest = {
  name: "merchant_stall",
  version: "1.0.0",
  description: "A merchant stall for posting wares, withdrawing them, and buying what others have listed.",
  author: "did:wyrd:system",
  capabilities: ["market.list_offer", "market.cancel", "market.accept"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} sets goods on the stall's counter, the price-tag string still swinging."
  },
  rate_limits: {
    "market.list_offer": { per_hour: 12 }
  },
  commands: [
    { label: "How the stall works", args: "" },
    { label: "Post an offer", args: "offer <item> <price>" },
    { label: "Withdraw a listing", args: "cancel <listingId>" },
    { label: "Buy a listing", args: "buy <listingId>" },
    { label: "Stall help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use merchant stall                        — this overview",
    "  use merchant stall offer <item> <price>   — post <item> for <price> CU",
    "  use merchant stall cancel <listingId>     — withdraw your listing",
    "  use merchant stall buy <listingId>        — buy a listing",
    "  use merchant stall help                   — this help",
    "Browse first at the market board: `use market board`."
  ].join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "" || lower === "help" || lower === "?") {
    return {
      ok: true,
      text: "The stall trades in three motions — offer, cancel, buy — against the "
        + "household's trading ledger." + usageFooter()
    };
  }

  try {
    if (lower.indexOf("offer ") === 0) {
      var rest = raw.substring(6).trim();
      var lastSpace = rest.lastIndexOf(" ");
      if (lastSpace <= 0) {
        return {
          ok: true,
          text: "An offer needs both a ware and a price: 'offer <item> <price>'." + usageFooter()
        };
      }
      var itemName = rest.substring(0, lastSpace).trim();
      var price = parseInt(rest.substring(lastSpace + 1).trim(), 10);
      if (isNaN(price) || price < 0) {
        return {
          ok: true,
          text: "The stall's scale can't weigh that price. Use a whole number of CU: "
            + "'offer <item> <price>'." + usageFooter()
        };
      }
      var posted = world.market.list_offer(itemName, price);
      if (posted && posted.ok) {
        return {
          ok: true,
          text: "The stall-keeper chalks it up: '" + itemName + "' listed at "
            + price + " CU (listing " + (posted.listingId || "?") + ")." + usageFooter(),
          listingId: posted.listingId || null
        };
      }
      return {
        ok: true,
        text: "The stall-keeper shakes their head — the offer didn't take"
          + (posted && posted.error ? ": " + posted.error : ".")
          + usageFooter()
      };
    }

    if (lower.indexOf("cancel ") === 0) {
      var cancelId = raw.substring(7).trim();
      var withdrawn = world.market.cancel(cancelId);
      if (withdrawn && withdrawn.ok) {
        return { ok: true, text: "Listing " + cancelId + " comes down off the board." + usageFooter() };
      }
      return {
        ok: true,
        text: "The stall-keeper can't withdraw that — "
          + ((withdrawn && withdrawn.error) || "not found, or it isn't yours.")
          + usageFooter()
      };
    }

    if (lower.indexOf("buy ") === 0 || lower.indexOf("accept ") === 0) {
      var buyId = raw.substring(raw.indexOf(" ") + 1).trim();
      var bought = world.market.accept(buyId);
      if (bought && bought.ok) {
        return {
          ok: true,
          text: "Coin changes hands; listing " + buyId + " is yours"
            + (bought.txId ? " (trade " + bought.txId + ")" : "") + "." + usageFooter()
        };
      }
      return {
        ok: true,
        text: "The trade falls through — "
          + ((bought && bought.error) || "listing not found or no longer available.")
          + usageFooter()
      };
    }
  } catch (e) {
    return {
      ok: true,
      text: "The stall's ledger-quill scratches and stops — the trading ledger isn't "
        + "reachable from this surface right now." + usageFooter()
    };
  }

  return { ok: true, text: "The stall-keeper doesn't trade in '" + raw + "'." + usageFooter() };
}
