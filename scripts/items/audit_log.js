// Study furnishing — audit log ("study-audit-log" RoomObject, display
// name "audit log" → normalized linkage "audit_log"). Steward object.
//
// The Study's security record: "security events… the ink is permanent."
// Recent events come from world.audit.recent(limit) — the same surface
// the Home "embers" furnishing watches — rendered as timestamp/verb/
// resource lines. A numeric arg sets how many entries to show (max 50).
//
// A deeper world.audit.security() ledger may land from another
// workstream; the log tries it defensively (typeof-guard) and says
// honestly when it isn't bound rather than pretending.
exports.manifest = {
  name: "audit_log",
  version: "1.0.0",
  description: "The household's permanent record of security events — grants, doors, entries. The ink does not fade.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} turns the audit log's heavy pages, ink-permanent lines of everything that has happened."
  },
  commands: [
    { label: "Read recent events", args: "" },
    { label: "Read more entries", args: "20" },
    { label: "Security ledger", args: "security" },
    { label: "Log help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use audit log            — the most recent events",
    "  use audit log 20         — a number of entries to show (up to 50)",
    "  use audit log security   — the deeper security ledger, if bound",
    "  use audit log help       — this help"
  ].join("\n");
}

function renderEntries(entries, heading) {
  var lines = [heading];
  for (var i = 0; i < entries.length; i++) {
    var e = entries[i];
    var line = "  " + (e.timestamp || "") + "  " + (e.verb || "?") + "  " + (e.resource || "");
    if (e.outcome && e.outcome !== "ok") line += "  [" + e.outcome + "]";
    lines.push(line);
  }
  return lines.join("\n");
}

function renderRecent(limit) {
  var entries = null;
  try { entries = world.audit.recent(limit); } catch (e) { entries = null; }
  if (!entries || entries.length === 0) {
    // Home-audit empty — surface the steward security ledger (member joins,
    // role changes) so the household's own recorded events aren't hidden
    // behind the 'security' subcommand.
    var sec = fetchSecurity(limit);
    if (sec && sec.length > 0) {
      return renderSecurityEntries(sec,
        "The audit log, its ink permanent — the last " + sec.length + " events:");
    }
    return "The audit log lies open to a clean page — no household events recorded yet.\n"
      + "It writes itself as the household acts: members added or removed, roles\n"
      + "changed, grants issued, nodes joining. Nothing to set up — it fills over time.";
  }
  return renderEntries(entries,
    "The audit log, its ink permanent — the last " + entries.length + " events:");
}

function fetchSecurity(limit) {
  try {
    if (typeof world.audit !== "undefined" && typeof world.audit.security === "function") {
      return world.audit.security(limit);
    }
  } catch (e) {}
  return null;
}

function renderSecurityEntries(entries, heading) {
  var lines = [heading];
  for (var i = 0; i < entries.length; i++) {
    var e = entries[i];
    var who = e.actorName || e.actor || "someone";
    var what = e.description || (e.type || "action");
    var line = "  " + (e.timestamp || "") + "  " + who + " — " + what;
    if (e.approved === false) line += "  [denied]";
    lines.push(line);
  }
  return lines.join("\n");
}

function renderSecurity() {
  var entries = fetchSecurity(20);
  if (entries && entries.length === 0) {
    return "The security ledger's back section is clean — no login, role, or grant\n"
      + "events recorded yet. It fills as stewards act on the household.";
  }
  if (!entries) {
    return "The log's sealed security section isn't available on this surface. The\n"
      + "recent record (bare 'use audit log') still holds the household's events.";
  }
  return renderSecurityEntries(entries, "The sealed back section — security events, ink permanent:");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The audit log records your household's security events — grants issued, doors "
        + "opened, visitors checked in. The ink is permanent." + usageFooter()
    };
  }
  if (args === "security") {
    return { ok: true, text: renderSecurity() + usageFooter() };
  }
  if (args === "") {
    return { ok: true, text: renderRecent(20) + usageFooter() };
  }
  if (/^[0-9]+$/.test(args)) {
    var limit = Math.min(parseInt(args, 10) || 20, 50);
    if (limit < 1) limit = 20;
    return { ok: true, text: renderRecent(limit) + usageFooter() };
  }
  return { ok: true, text: "The audit log doesn't know '" + args + "'." + usageFooter() };
}
