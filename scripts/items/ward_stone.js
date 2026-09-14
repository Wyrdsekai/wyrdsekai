// Home furnishing — ward stone ("ward-stone" RoomObject in a companion's Home,
// display name "Ward Stone" → normalized linkage "ward_stone").
//
// A companion's Home is hers: sealed to her at birth, opened by her hand. This
// is the hand. `use ward stone` shows who may come in; `invite <name>` cuts a
// key (enter + speak, so a guest can come in and talk); `uninvite <name>`
// melts every key that person holds. Names are the household's — a companion
// by name (world.companions.list), a person by username or display name
// (world.household.members), or a raw id/DID when she has one.
//
// The gate enforces the keeper check itself (world.ward.grant answers
// "only the room's keeper holds its keys" for any room that is not hers), so
// the stone renders refusals honestly instead of pre-judging.
//
// Until 2026-09-13 there was no way for her to do this at all: her provider's
// ward verbs answered "not steward-held", and the stone in her Home was a
// pebble that "glows when your identity is strong".
exports.manifest = {
  name: "ward_stone",
  version: "1.0.0",
  description: "A smooth stone warm to your hand alone — the keys to this room are cut and melted here. `use ward stone` shows who may enter; `invite <name>` lets someone in, `uninvite <name>` takes it back.",
  author: "did:wyrd:system",
  capabilities: ["ward.grant", "ward.revoke"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} rests a hand on the ward stone; it warms, and the room listens."
  },
  commands: [
    { label: "Who may enter", args: "" },
    { label: "Invite someone in", args: "invite <name>" },
    { label: "Take an invitation back", args: "uninvite <name>" },
    { label: "Stone help", args: "help" }
  ]
};

var GUEST_KEYS = ["enter", "speak"];
var CAPABILITIES = ["enter", "speak", "take", "drop", "use", "build", "admin"];

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use ward stone                       — who may come into this room",
    "  use ward stone invite <name>         — let someone in (they may enter and speak)",
    "  use ward stone invite <name> <cap>   — one key only: enter/speak/take/drop/use/build",
    "  use ward stone uninvite <name>       — melt every key that person holds",
    "  use ward stone help                  — this help",
    "The stone answers only in your own Home. Names are the household's — a companion",
    "or a person by name; an id or DID also works."
  ].join("\n");
}

function lower(s) { return String(s == null ? "" : s).toLowerCase(); }

function roomId() {
  var id = null;
  try { id = world.room.id(); } catch (e) { id = null; }
  return id ? String(id) : null;
}

function selfDid() {
  var d = null;
  try { d = world.self.did(); } catch (e) { d = null; }
  return d ? String(d) : null;
}

function selfName() {
  var n = null;
  try { n = world.self.name(); } catch (e) { n = null; }
  return n ? String(n) : null;
}

function companions() {
  var list = null;
  try { list = world.companions.list(); } catch (e) { list = null; }
  return list || [];
}

function members() {
  var list = null;
  try { list = world.household.members(); } catch (e) { list = null; }
  return list || [];
}

// Everyone the stone can name: [{ name, ids: [..], kind }]
function directory() {
  var out = [];
  var comps = companions();
  for (var i = 0; i < comps.length; i++) {
    var c = comps[i];
    var ids = [];
    if (c.did) ids.push(String(c.did));
    if (c.entityId) ids.push(String(c.entityId));
    if (c.name && ids.length) out.push({ name: String(c.name), ids: ids, kind: "companion" });
  }
  var people = members();
  for (var j = 0; j < people.length; j++) {
    var p = people[j];
    if (!p.id) continue;
    var display = p.displayName || p.username;
    out.push({ name: String(display), aliases: [String(p.username || "")], ids: [String(p.id)], kind: "person" });
  }
  return out;
}

// The ids that are hers — never a key to melt.
function selfIds() {
  var ids = [];
  var did = selfDid();
  if (did) ids.push(did);
  var name = selfName();
  var comps = companions();
  for (var i = 0; i < comps.length; i++) {
    var c = comps[i];
    var same = (did && c.did && String(c.did) === did) || (name && c.name && lower(c.name) === lower(name));
    if (same) {
      if (c.did) ids.push(String(c.did));
      if (c.entityId) ids.push(String(c.entityId));
    }
  }
  return ids;
}

function resolve(name) {
  var q = lower(name).trim();
  if (!q) return null;
  var dir = directory();
  for (var i = 0; i < dir.length; i++) {
    var e = dir[i];
    if (lower(e.name) === q) return e;
    var al = e.aliases || [];
    for (var k = 0; k < al.length; k++) if (al[k] && lower(al[k]) === q) return e;
    for (var m = 0; m < e.ids.length; m++) if (lower(e.ids[m]) === q) return e;
  }
  // A raw id or DID she has in hand.
  if (q.indexOf("did:") === 0 || q.length >= 16) return { name: String(name).trim(), ids: [String(name).trim()], kind: "id" };
  return null;
}

function nameFor(subject) {
  var dir = directory();
  for (var i = 0; i < dir.length; i++) {
    for (var m = 0; m < dir[i].ids.length; m++) if (dir[i].ids[m] === subject) return dir[i].name;
  }
  return subject;
}

function knownNames() {
  var dir = directory();
  var names = [];
  for (var i = 0; i < dir.length; i++) if (names.indexOf(dir[i].name) < 0) names.push(dir[i].name);
  return names;
}

function renderWho(room) {
  var wards = null;
  try { wards = world.ward.list(room); } catch (e) { wards = null; }
  wards = wards || [];
  var mine = selfIds();
  var bySubject = {};
  var order = [];
  for (var i = 0; i < wards.length; i++) {
    var w = wards[i];
    var s = String(w.subject);
    if (!bySubject[s]) { bySubject[s] = []; order.push(s); }
    bySubject[s].push(String(w.capability));
  }
  if (order.length === 0) {
    return "The stone is cool: this room has no wards at all — it stands open. (A Home is sealed at birth; if this is yours, the next restart seals it.)";
  }
  var lines = ["Who may come into this room:"];
  var guests = 0;
  var seenNames = {};
  for (var j = 0; j < order.length; j++) {
    var subj = order[j];
    var isMe = mine.indexOf(subj) >= 0;
    var label = isMe ? "you" : nameFor(subj);
    if (seenNames[label]) continue;   // one line per person, not per id
    seenNames[label] = true;
    if (!isMe) guests++;
    lines.push("  " + label + " — " + bySubject[subj].join(", "));
  }
  if (guests === 0) lines.push("  (no one else — the door opens for you alone)");
  return lines.join("\n");
}

function invite(room, target, cap) {
  if (!target) return { ok: false, error: "invite whom? — use ward stone invite <name>" + usageFooter() };
  var who = resolve(target);
  if (!who) {
    var names = knownNames();
    return { ok: false, error: "I don't know anyone called '" + target + "' here."
      + (names.length ? " Names I know: " + names.join(", ") + "." : "")
      + " An id or DID works too." };
  }
  var mine = selfIds();
  for (var i = 0; i < who.ids.length; i++) {
    if (mine.indexOf(who.ids[i]) >= 0) return { ok: false, error: "That is you — the door already opens for you." };
  }
  var keys = GUEST_KEYS;
  if (cap) {
    var c = lower(cap);
    if (CAPABILITIES.indexOf(c) < 0) return { ok: false, error: "unknown key '" + cap + "' — one of " + CAPABILITIES.join("/") };
    keys = [c];
  }
  var cut = 0, already = 0, refused = null;
  for (var k = 0; k < who.ids.length; k++) {
    for (var q = 0; q < keys.length; q++) {
      var res = null;
      try { res = world.ward.grant(room, who.ids[k], keys[q]); } catch (e) { res = { ok: false, error: String(e) }; }
      if (!res || !res.ok) { refused = (res && res.error) || "the stone would not cut the key"; continue; }
      if (res.created) cut++; else already++;
    }
  }
  if (cut === 0 && already === 0) return { ok: false, error: refused || "the stone would not cut the key" };
  var summary = cut > 0
    ? who.name + " may now " + keys.join(" and ") + " here."
    : who.name + " already held " + (keys.length === 1 ? "that key" : "those keys") + ".";
  return { ok: true, summary: summary, subject: who.name, keys: keys, created: cut > 0 };
}

function uninvite(room, target) {
  if (!target) return { ok: false, error: "uninvite whom? — use ward stone uninvite <name>" + usageFooter() };
  var who = resolve(target);
  if (!who) return { ok: false, error: "I don't know anyone called '" + target + "' here." };
  var mine = selfIds();
  for (var i = 0; i < who.ids.length; i++) {
    if (mine.indexOf(who.ids[i]) >= 0) return { ok: false, error: "Those are your own keys — the stone will not melt them." };
  }
  var wards = null;
  try { wards = world.ward.list(room); } catch (e) { wards = null; }
  wards = wards || [];
  var melted = 0, refused = null;
  for (var w = 0; w < wards.length; w++) {
    var row = wards[w];
    if (who.ids.indexOf(String(row.subject)) < 0) continue;
    var res = null;
    try { res = world.ward.revoke(room, String(row.subject), String(row.capability)); } catch (e) { res = { ok: false, error: String(e) }; }
    if (res && res.ok) melted++; else refused = (res && res.error) || "the stone would not melt the key";
  }
  if (melted === 0) return { ok: false, error: refused || (who.name + " holds no key to this room.") };
  return { ok: true, summary: who.name + " may no longer come in (" + melted + " key" + (melted === 1 ? "" : "s") + " melted).", subject: who.name, melted: melted };
}

function invoke(params) {
  params = params || {};
  var argStr = params.args == null ? "" : String(params.args);
  var words = argStr.trim().split(/\s+/).filter(function (w) { return w.length > 0; });
  var mode = params.mode ? lower(params.mode) : (words.length ? lower(words[0]) : "");
  if (params.mode && words.length && lower(words[0]) === mode) words.shift();
  else if (!params.mode && words.length) words.shift();

  if (mode === "help") return { ok: true, summary: "The ward stone — the keys to your Home." + usageFooter() };

  var room = roomId();
  if (!room || room.indexOf("home-") !== 0) {
    return { ok: false, error: "The stone is cool here. It answers only in a Home — go home and rest your hand on it there." };
  }

  if (mode === "invite" || mode === "grant" || mode === "let") {
    var cap = words.length > 1 && CAPABILITIES.indexOf(lower(words[words.length - 1])) >= 0 ? words.pop() : null;
    return invite(room, words.join(" "), cap);
  }
  if (mode === "uninvite" || mode === "revoke" || mode === "melt") {
    return uninvite(room, words.join(" "));
  }
  if (mode === "" || mode === "who" || mode === "list" || mode === "look" || mode === "read") {
    return { ok: true, summary: renderWho(room) };
  }
  return { ok: false, error: "The stone does not know '" + mode + "'." + usageFooter() };
}

exports.invoke = invoke;
