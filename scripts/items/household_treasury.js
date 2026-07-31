// Study furnishing — household treasury ("study-household-treasury"
// RoomObject, display name "household treasury" → normalized linkage
// "household_treasury").
//
// An ornate lockbox holding the household's whole reckoning: aggregate
// usage via world.treasury.summary(), per-member breakdown via
// perMember(), steward-only daily budgets via setBudget(member, usd),
// and (W5, 2026-07-11) the Counting House write API — credit transfers
// via transfer(to, amount, note) and balances via balance(member).
// Budgets are IN-MEMORY and reset when the node restarts — the lockbox
// says so every time rather than letting the steward believe otherwise.
exports.manifest = {
  name: "household_treasury",
  version: "1.1.0",
  description: "Ornate lockbox tallying the whole household's inference spend — aggregate, per member, the steward's daily budgets, and the Counting House credit ledger (pay/balance).",
  author: "did:wyrd:system",
  capabilities: ["treasury.set_budget", "treasury.transfer"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} lifts the treasury's ornate lid; coin-weights and counters gleam inside, arranged by name."
  },
  commands: [
    { label: "Read the treasury", args: "" },
    { label: "Per-member reckoning", args: "members" },
    { label: "Set a daily budget", args: "budget <member> <usd>" },
    { label: "Pay credits to a member", args: "pay <member> <credits> [note]" },
    { label: "Credit balance", args: "balance [member]" },
    { label: "Treasury help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use household treasury                       — the household's whole reckoning",
    "  use household treasury members               — each member's share, by name",
    "  use household treasury budget <member> <usd> — set a member's daily limit (steward)",
    "  use household treasury pay <member> <credits> [note] — transfer credits from YOUR account",
    "  use household treasury balance [member]      — a member's credit standing (default: you)",
    "  use household treasury help                  — this help",
    "Budgets live in memory only — a node restart empties the till of its limits."
  ].join("\n");
}

function money(v) {
  return "$" + (Math.round((v || 0) * 10000) / 10000);
}

function renderSummary() {
  var s = null;
  try { s = world.treasury.summary(); } catch (e) { s = null; }
  if (!s || (!s.agents && !s.inferences && !s.tokens)) {
    return "The treasury's counters rest at zero — no household usage recorded yet, "
      + "or this surface isn't bound to your Home.";
  }
  var lines = ["The lid lifts; the household's whole reckoning:"];
  lines.push("  Members tracked: " + (s.agents || 0));
  lines.push("  Inferences:      " + (s.inferences || 0));
  if (s.mcpCalls) lines.push("  MCP calls:       " + s.mcpCalls);
  lines.push("  Tokens:          " + (s.tokens || 0));
  lines.push("  Cost:            " + money(s.monetaryCost));
  if (s.firstActivity) lines.push("  First activity:  " + s.firstActivity);
  if (s.lastActivity) lines.push("  Last activity:   " + s.lastActivity);
  return lines.join("\n");
}

function renderMembers() {
  var rows = null;
  try { rows = world.treasury.perMember(); } catch (e) { rows = null; }
  if (!rows || rows.length === 0) {
    return "The named compartments are all empty — no per-member usage recorded yet, "
      + "or this surface isn't bound to your Home.";
  }
  var lines = ["Inside, a compartment for each name:"];
  for (var i = 0; i < rows.length; i++) {
    var r = rows[i];
    lines.push("  " + (r.agentId || "?"));
    lines.push("    inferences " + (r.inferences || 0)
      + "  ·  tokens " + (r.tokens || 0)
      + "  ·  " + money(r.monetaryCost)
      + (r.avgLatencyMs ? "  ·  " + Math.round(r.avgLatencyMs) + "ms avg" : ""));
    if (r.budgetNote) lines.push("    ⚠ " + r.budgetNote);
  }
  return lines.join("\n");
}

function doBudget(rest) {
  var parts = rest.split(/\s+/);
  if (parts.length < 2) {
    return "Setting a budget takes a name and a sum: budget <member> <usd>."
      + usageFooter();
  }
  var member = parts[0];
  var usd = Number(parts[1]);
  if (!isFinite(usd) || usd < 0) {
    return "The counters only take honest sums — '" + parts[1]
      + "' is not a non-negative number of dollars." + usageFooter();
  }
  var res = null;
  try { res = world.treasury.setBudget(member, usd); } catch (e) { res = null; }
  if (!res) {
    return "The lockbox will not open its ledger — the treasury service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "A counter is set against " + member + "'s compartment: " + money(usd)
      + " per day.\nMind: the limit lives in memory only — a node restart clears it.";
  }
  return "The counter will not set: " + (res.error || "the budget change was refused") + "."
    + usageFooter();
}

function doPay(rest) {
  var parts = rest.split(/\s+/);
  if (parts.length < 2) {
    return "Paying takes a name and a sum of credits: pay <member> <credits> [note]."
      + usageFooter();
  }
  var member = parts[0];
  var credits = Number(parts[1]);
  if (!isFinite(credits) || credits <= 0 || Math.floor(credits) !== credits) {
    return "The counters only move in whole positive credits — '" + parts[1]
      + "' will not do." + usageFooter();
  }
  var note = parts.slice(2).join(" ");
  var res = null;
  try { res = world.treasury.transfer(member, credits, note); } catch (e) { res = null; }
  if (!res) {
    return "The lockbox will not move a single counter — the Counting House isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "Counters slide across the ledger: " + credits + " credit"
      + (credits === 1 ? "" : "s") + " from your compartment to " + member + "'s."
      + (res.message ? "\n" + res.message : "");
  }
  return "The transfer will not go through: " + (res.error || "the Counting House refused") + "."
    + usageFooter();
}

function doBalance(rest) {
  var member = rest.split(/\s+/)[0] || "";
  var res = null;
  try { res = world.treasury.balance(member); } catch (e) { res = null; }
  if (!res || !res.ok) {
    return "The ledger stays shut: "
      + ((res && res.error) || "the Counting House isn't reachable from this surface") + "."
      + usageFooter();
  }
  var lines = ["The ledger opens to " + res.entityId + "'s page:"];
  lines.push("  Balance:      " + res.balance + " credits");
  lines.push("  Credit limit: " + res.creditLimit);
  lines.push("  Earned:       " + res.totalEarned + "  ·  Spent: " + res.totalSpent);
  return lines.join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The household treasury tallies inference spend for the whole household — "
        + "in aggregate, per member, and against the steward's daily budgets." + usageFooter()
    };
  }
  if (verb === "members") {
    return { ok: true, text: renderMembers() + usageFooter() };
  }
  if (verb === "budget") {
    return { ok: true, text: doBudget(rest) };
  }
  if (verb === "pay" || verb === "transfer") {
    return { ok: true, text: doPay(rest) };
  }
  if (verb === "balance") {
    return { ok: true, text: doBalance(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The treasury doesn't answer to '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderSummary() + usageFooter() };
}
