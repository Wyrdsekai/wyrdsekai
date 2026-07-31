// Study furnishing — parental controls scroll ("study-parental-controls"
// RoomObject, display name "parental controls scroll" → normalized
// linkage "parental_controls_scroll").
//
// The scroll's clauses are now SWORN: ParentalControlService enforces
// per-member time limits (login gate + 60s accrual ticker), room
// restrictions (RoomActor entry check, glob-matched), inference quotas
// (CompanionActor speech-trigger gate), and content filters (per-session
// prose screen). This scroll reads and writes those rules through
// world.parental.*; the service answers writes only to the steward's hand,
// and the scroll renders every refusal honestly.
exports.manifest = {
  name: "parental_controls_scroll",
  version: "2.0.0",
  description: "The household's sworn scroll of per-member rules — time limits, room restrictions, inference quotas, content filters — read and rewritten by the steward's hand.",
  author: "did:wyrd:system",
  capabilities: ["parental.set", "parental.clear"],
  data_sensitivity: "private", // household rules about members must not leak via give_copy
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} unrolls the parental controls scroll, tracing the household's sworn clauses."
  },
  commands: [
    { label: "Read the scroll", args: "" },
    { label: "Set a daily time limit", args: "set <username> minutes <n|off>" },
    { label: "Set a daily inference quota", args: "set <username> inference <n|off>" },
    { label: "Set the content filter", args: "set <username> filter <strict|off>" },
    { label: "Bar a room", args: "block <username> <room-glob>" },
    { label: "Unbar a room", args: "unblock <username> <room-glob>" },
    { label: "Strike a member's clauses", args: "clear <username>" },
    { label: "Scroll help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use parental controls scroll                                — read the sworn clauses",
    "  use parental controls scroll set <username> minutes <n|off>   — daily time limit (minutes)",
    "  use parental controls scroll set <username> inference <n|off> — daily inference quota",
    "  use parental controls scroll set <username> filter <strict|off> — content filter",
    "  use parental controls scroll block <username> <room-glob>     — bar rooms (e.g. gpu-chamber, study-*)",
    "  use parental controls scroll unblock <username> <room-glob>   — lift a room bar",
    "  use parental controls scroll clear <username>                 — strike every clause for a member",
    "  use parental controls scroll help                             — this help",
    "The scroll answers writes only to the steward's hand."
  ].join("\n");
}

function fmtLimit(v) {
  return (v === null || v === undefined) ? "unlimited" : String(v);
}

function renderScroll() {
  var members = null;
  try { members = world.parental.list(); } catch (e) { members = null; }
  if (!members) {
    return "The scroll's clauses blur — the parental service isn't reachable from this surface.";
  }
  if (members.length === 0) {
    return "You unroll the parental controls scroll. Its clauses are sworn and binding,\n"
      + "but no member's name is written on it yet — no limits hold anywhere.\n"
      + "The steward's pen may add them: set, block, filter.";
  }
  var lines = ["You unroll the parental controls scroll; the household's sworn clauses:"];
  for (var i = 0; i < members.length; i++) {
    var m = members[i];
    lines.push("");
    var head = "  " + (m.username || "?");
    if (m.displayName && m.displayName !== m.username) {
      head += "  “" + m.displayName + "”";
    }
    lines.push(head);
    lines.push("    time      : " + fmtLimit(m.dailyMinutes) + " min/day"
      + "  (" + (m.minutesUsedToday || 0) + " spent today)");
    lines.push("    inference : " + fmtLimit(m.dailyInference) + " /day"
      + "  (" + (m.inferencesUsedToday || 0) + " spent today)");
    lines.push("    filter    : " + (m.contentFilter || "off"));
    var rooms = m.blockedRooms || [];
    lines.push("    rooms barred: " + (rooms.length ? rooms.join(", ") : "none"));
  }
  lines.push("");
  lines.push(members.length + " name" + (members.length === 1 ? "" : "s") + " bound by the scroll.");
  return lines.join("\n");
}

function doSet(rest) {
  var parts = rest.split(/\s+/).filter(function (p) { return p !== ""; });
  if (parts.length < 3) {
    return "The pen hovers — set needs a name, a field, and a value: "
      + "set <username> <minutes|inference|filter> <value>." + usageFooter();
  }
  var username = parts[0];
  var field = parts[1].toLowerCase();
  var value = parts[2];
  if (field !== "minutes" && field !== "inference" && field !== "filter") {
    return "The scroll knows no clause named '" + field
      + "' — only minutes, inference, and filter take the pen here." + usageFooter();
  }
  var res = null;
  try { res = world.parental.set(username, field, value); } catch (e) { res = null; }
  if (!res) {
    return "The scroll's ink refuses to take — the parental service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "The clause is sworn: " + username + "'s " + field + " is now " + value + ".";
  }
  return "The scroll's ink resists: " + (res.error || "the change was refused") + "." + usageFooter();
}

function doRoom(verb, rest) {
  var parts = rest.split(/\s+/).filter(function (p) { return p !== ""; });
  if (parts.length < 2) {
    return "Which door, for whom? " + verb + " <username> <room-glob>." + usageFooter();
  }
  var username = parts[0];
  var glob = parts[1];
  var field = verb === "block" ? "block-room" : "unblock-room";
  var res = null;
  try { res = world.parental.set(username, field, glob); } catch (e) { res = null; }
  if (!res) {
    return "The scroll's ink refuses to take — the parental service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return verb === "block"
      ? "The clause is sworn: the door of " + glob + " no longer opens for " + username + "."
      : "The bar is lifted: " + glob + " opens for " + username + " again.";
  }
  return "The scroll's ink resists: " + (res.error || "the change was refused") + "." + usageFooter();
}

function doClear(rest) {
  var username = rest.split(/\s+/)[0];
  if (!username) {
    return "Strike whose clauses? clear <username>." + usageFooter();
  }
  var res = null;
  try { res = world.parental.clear(username); } catch (e) { res = null; }
  if (!res) {
    return "The scroll's ink refuses to take — the parental service isn't reachable from this surface."
      + usageFooter();
  }
  if (res.ok) {
    return "A line is drawn through every clause: " + username + " walks unbound.";
  }
  return "The line will not be drawn: " + (res.error || "the clear was refused") + "." + usageFooter();
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The parental controls scroll binds per-member rules the substrate now enforces: "
        + "daily time limits (sessions close when the hours are spent), room restrictions "
        + "(barred doors refuse entry), inference quotas (companions rest their thinking), "
        + "and content filters (rough remarks are blotted before they reach a strict-filtered member)."
        + usageFooter()
    };
  }
  if (verb === "set") {
    return { ok: true, text: doSet(rest) };
  }
  if (verb === "block" || verb === "unblock") {
    return { ok: true, text: doRoom(verb, rest) };
  }
  if (verb === "clear") {
    return { ok: true, text: doClear(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The scroll doesn't answer to '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderScroll() + usageFooter() };
}
