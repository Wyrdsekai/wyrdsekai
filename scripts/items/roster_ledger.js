// Study furnishing — roster ledger ("study-roster-ledger" RoomObject,
// display name "roster ledger" → normalized linkage "roster_ledger").
//
// The household roster on a reading stand: every account via
// world.household.members() (username, display name, role, joined), plus
// steward-only role changes and removals via setRole/removeMember. The
// provider enforces the steward check itself — the ledger renders the
// service's refusal honestly instead of pre-judging the caller.
//
// TODO: declare household.set_role once registered in KNOWN_CAPABILITIES
// TODO: declare household.remove_member once registered in KNOWN_CAPABILITIES
exports.manifest = {
  name: "roster_ledger",
  version: "1.0.0",
  description: "Bound household ledger — every member's name, role, and joining day; the steward's pen changes roles or strikes names.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} turns the roster ledger's heavy pages, tracing the household's names in careful ink."
  },
  commands: [
    { label: "Read the roster", args: "" },
    { label: "Grant a role", args: "grant <username> <role>" },
    { label: "Remove a member", args: "remove <username>" },
    { label: "Ledger help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use roster ledger                          — read the household roster",
    "  use roster ledger grant <username> <role>  — set a member's role (steward/member/guest/child)",
    "  use roster ledger remove <username>        — strike a member from the household",
    "  use roster ledger help                     — this help",
    "Role changes and removals answer only to the steward's hand."
  ].join("\n");
}

function renderRoster() {
  var members = null;
  try { members = world.household.members(); } catch (e) { members = null; }
  if (!members || members.length === 0) {
    return "The roster ledger lies open to a blank page — no household accounts recorded here, "
      + "or this surface isn't bound to your Home.";
  }
  var lines = ["The roster ledger lies open; the household, in careful ink:"];
  for (var i = 0; i < members.length; i++) {
    var m = members[i];
    var line = "  [" + (m.role || "?") + "]  " + (m.username || "?");
    if (m.displayName && m.displayName !== m.username) {
      line += "  “" + m.displayName + "”";
    }
    if (m.createdAt) line += "  (joined " + m.createdAt + ")";
    lines.push(line);
  }
  lines.push("");
  lines.push(members.length + " name" + (members.length === 1 ? "" : "s") + " on the roster.");
  return lines.join("\n");
}

function doGrant(rest) {
  var parts = rest.split(/\s+/);
  if (parts.length < 2) {
    return "The pen hovers — grant needs both a name and a role: grant <username> <role>."
      + usageFooter();
  }
  var username = parts[0];
  var role = parts[1].toLowerCase();
  var res = null;
  try { res = world.household.setRole(username, role); } catch (e) { res = null; }
  if (!res) {
    return "The ledger's ink refuses to take — the household service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "The ledger accepts the change: " + username + " is now written as " + role + ".";
  }
  return "The ledger's ink resists: " + (res.error || "the change was refused") + "."
    + usageFooter();
}

function doRemove(rest) {
  var username = rest.split(/\s+/)[0];
  if (!username) {
    return "Strike whom? remove <username>." + usageFooter();
  }
  var res = null;
  try { res = world.household.removeMember(username); } catch (e) { res = null; }
  if (!res) {
    return "The ledger's ink refuses to take — the household service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "A line is drawn through the name: " + username
      + " is no longer of this household.";
  }
  return "The line will not be drawn: " + (res.error || "the removal was refused") + "."
    + usageFooter();
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The roster ledger holds the household's names and roles — and the steward's pen may change them."
        + usageFooter()
    };
  }
  if (verb === "grant") {
    return { ok: true, text: doGrant(rest) };
  }
  if (verb === "remove") {
    return { ok: true, text: doRemove(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The ledger doesn't know '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderRoster() + usageFooter() };
}
