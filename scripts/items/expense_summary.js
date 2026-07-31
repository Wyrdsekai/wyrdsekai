// §4.32 financial read sample.
//
// An expense summarizer that pulls the last 30 days of Plaid transactions
// (read-only, no money movement) and groups them by category. Demonstrates:
//   * Tier 5 plaid.read capability
//   * world.plaid.list_transactions adapter dispatch
//   * Read-only by design — no spend, no steward-token requirement
//
// Manifest fields used:
//   capabilities: ["plaid.read"]
//   data_sensitivity: "private"   // financial reads must not leak via give_copy
//   rate_limits.plaid.read: per-day to avoid Plaid quota exhaustion
exports.manifest = {
  name: "expense_summary",
  version: "1.0.0",
  description: "Summarize the last 30 days of bank transactions by category.",
  author: "did:wyrd:system",
  capabilities: ["plaid.read"],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "A neat ledger-sheet unfurls — figures inked in calm columns, totals at the foot."
  },
  rate_limits: {
    "plaid.read": { per_minute: 5, per_hour: 30, per_day: 100 }
  },
  data_sensitivity: "private",
  // Items-as-tools contract — invoke() reads structured params
  // (params.account, params.days), not the args string; the bare entry
  // runs the default 30-day, all-accounts summary.
  commands: [
    { label: "Summarize the last 30 days of spending", args: "" }
  ],
  // Optional: the no-arg default (last 30 days, all accounts) is genuinely useful.
  params: [
    { name: "account", type: "string", required: false,
      description: "Limit the summary to one account. Omit for all accounts." },
    { name: "days", type: "number", required: false,
      description: "How many days back to summarize. Defaults to 30." }
  ]
};

function invoke(params) {
  var account = (params && params.account) || null;
  var days = (params && params.days) || 30;

  var since = new Date(Date.now() - days * 86400000)
    .toISOString().slice(0, 10);
  var until = new Date().toISOString().slice(0, 10);

  var args = { since: since, until: until, limit: 500 };
  if (account) args.account = account;

  // Adapter dispatch — resolves via ExternalAdapterRegistry.
  var resp = world.plaid.list_transactions(args);
  if (!resp || !resp.success) {
    return {
      ok: false,
      error: resp && resp.error ? resp.error.code : "no_response",
      message: resp && resp.error ? resp.error.message : "plaid call failed"
    };
  }

  var txns = (resp.data && resp.data.transactions) || [];
  var byCategory = {};
  var totalSpend = 0;
  for (var i = 0; i < txns.length; i++) {
    var tx = txns[i];
    var amount = Number(tx.amount) || 0;
    if (amount <= 0) continue;          // ignore credits
    var cats = tx.category || ["uncategorized"];
    var top = cats[0] || "uncategorized";
    byCategory[top] = (byCategory[top] || 0) + amount;
    totalSpend += amount;
  }

  var rows = Object.keys(byCategory).map(function(k) {
    return { category: k, amount: Math.round(byCategory[k] * 100) / 100 };
  }).sort(function(a, b) { return b.amount - a.amount; });

  return {
    ok: true,
    window: { since: since, until: until, days: days },
    total_spend: Math.round(totalSpend * 100) / 100,
    transaction_count: txns.length,
    by_category: rows
  };
}

exports.invoke = invoke;
