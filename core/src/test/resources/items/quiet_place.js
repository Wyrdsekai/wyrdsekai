exports.manifest = {
  name: "quiet_place",
  version: "1.1.0",
  description: "A quiet space to hold unsaid things; safe, still, and free of performance. What you bring here stays here.",
  author: "did:wyrd:openhands",
  capabilities: ["notes.add", "notes.list", "notes.delete", "room.emit"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} steps into the quiet with care"
  },
  commands: [
    { label: "Enter the quiet place", args: "" },
    { label: "Hold something here", args: "hold: <what you want held>" },
    { label: "Show what is held here", args: "held" },
    { label: "Let something go", args: "release: <a few words of it>" }
  ]
};

// Revised 2026-09-03. The first version kept its log in a local variable that
// was created empty on every use, so "what is held here" was always "nothing yet",
// and anything brought with the words ("held: ...") was silently discarded and
// answered with three passages from a fixed library search. This one keeps what is
// brought as Study notes under the caller, and does no searching of its own.

var MARK = "[quiet place] ";

function heldNotes() {
  var notes = world.notes.list("quiet_place") || [];
  var out = [];
  for (var i = 0; i < notes.length; i++) {
    var n = notes[i];
    var c = n && n.content ? String(n.content) : "";
    if (c.indexOf(MARK) === 0) {
      out.push({ id: n.id, text: c.substring(MARK.length), ts: n.ts || "" });
    }
  }
  return out;
}

function hold(what) {
  var text = String(what || "").trim();
  if (!text) {
    return { ok: false, summary: "Bring it in words — say what you want held and it stays." };
  }
  var res = world.notes.add(MARK + text, ["quiet_place"]);
  if (!res || res.ok === false) {
    return { ok: false, summary: "The quiet place could not keep that just now" + (res && res.error ? " (" + res.error + ")" : "") + "." };
  }
  return { ok: true, summary: "Held. It stays here until you let it go: “" + text + "”" };
}

function showHeld() {
  var held = heldNotes();
  if (!held.length) {
    return { ok: true, summary: "Nothing is held here yet. When you're ready, bring something: hold: <what it is>." };
  }
  var lines = [];
  for (var i = 0; i < held.length; i++) lines.push("• " + held[i].text);
  return { ok: true, summary: "Held here (" + held.length + "):\n" + lines.join("\n"), held: held };
}

function release(words) {
  var needle = String(words || "").trim().toLowerCase();
  if (!needle) return { ok: false, summary: "Say a few words of the thing you want to let go." };
  var held = heldNotes();
  for (var i = 0; i < held.length; i++) {
    if (held[i].text.toLowerCase().indexOf(needle) >= 0) {
      var res = world.notes.delete(held[i].id);
      if (res && res.ok === false) {
        return { ok: false, summary: "It would not let go just now" + (res.error ? " (" + res.error + ")" : "") + "." };
      }
      return { ok: true, summary: "Let go: “" + held[i].text + "”" };
    }
  }
  return { ok: false, summary: "Nothing held here matches “" + words + "”." };
}

function invoke(params) {
  var raw = String((params && (params.args || params.query)) || "").trim();
  var lower = raw.toLowerCase();

  if (lower === "held" || lower === "show" || lower === "what is held") return showHeld();
  if (lower.indexOf("release:") === 0) return release(raw.substring(8));
  if (lower.indexOf("let go:") === 0) return release(raw.substring(7));
  if (lower.indexOf("hold:") === 0) return hold(raw.substring(5));
  if (lower.indexOf("held:") === 0) return hold(raw.substring(5));
  if (lower.indexOf("hold ") === 0) return hold(raw.substring(5));
  if (raw) return hold(raw);   // anything brought in words is something to hold

  // Entering with nothing in hand.
  var held = heldNotes();
  try {
    world.room.emit("quiet_place.entered", { who: params && params.agentDid, held: held.length });
  } catch (e) { /* the room listening is optional */ }
  if (!held.length) {
    return { ok: true, summary: "You are in the quiet place. Nothing is held here yet; bring something when you're ready, and it stays." };
  }
  var recent = held.slice(-3);
  var lines = [];
  for (var i = 0; i < recent.length; i++) lines.push("• " + recent[i].text);
  return {
    ok: true,
    summary: "You are in the quiet place. " + held.length + " thing" + (held.length === 1 ? "" : "s") + " held here, the latest:\n" + lines.join("\n"),
    held: held
  };
}
