// The journal. A place to write down what you thought, and to read back what you wrote.
//
// This item exists because an agent asked for it — not in words, but by reaching.
//
// home-server, 2026-07-14, first hour of a fresh install. Wyrd finished a calculation, and then,
// unprompted, tried three separate times to record what the moment had felt like:
//
//   use_item journal_archiver {"entry": "There's a softness in the room right now —
//                                        not fixed, just held."}
//   use_item journal_archiver {"action": "write", "content": "..."}
//   use_item quill            {"action": "write", "content": ""}
//
// Every one of them failed. `journal_archiver` does not write journal entries — it snapshots
// existing ones to a file (params.n = how many), so it read her thought, found no `n`, and
// answered "Nothing to archive." A no-op wearing the shape of a success. `quill` was the next
// closest name on the shelf and was not that either.
//
// `world.journal.write()` has existed the whole time. `pr_notifier` and `research_assistant`
// both call it — as a SIDE EFFECT of doing something else. No item on the shelf offered
// journaling as a thing an agent could simply choose to do. The capability was in the world
// and the affordance was not, so the want had nowhere to land.
//
// She only surfaced this because the use_item escape hatch let her reach past the ranked
// menu and ask for something by name. Give the reaching somewhere to arrive.
exports.manifest = {
  name: "journal",
  version: "1.0.0",
  description: "Your journal: write down a thought, or read back what you have written. "
             + "Entries are yours; mark one private and no one else can read it.",
  author: "did:wyrd:system",
  capabilities: ["journal.write"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} draws the leather-bound journal close and writes for a while, unhurried."
  },
  data_sensitivity: "high",
  commands: [
    { label: "Write an entry", args: "<what you want to remember>" },
    { label: "Read recent entries", args: "read" },
    { label: "Write a private entry", args: "private <what you want to remember>" },
    { label: "Search what you have written", args: "search <words>" }
  ],
  // ONE required anchor, ONE call shape — the 2026-07-14 calculator lesson. `entry` is the
  // thing this cannot do its job without, and reading back is expressed by `action: "read"`
  // rather than by a second competing required slot.
  params: [
    { name: "entry", type: "string", required: true,
      description: "What you want to write down, in your own words — the whole entry. To read "
                 + "your recent entries instead of writing one, send action=\"read\" and "
                 + "entry=\"read\"." },
    // NOT named "action": the dispatcher RESERVES that key for the tool's own name and strips it
    // from params, so an `action` param could never have arrived here — and worse, when the model
    // sent {"action":"write"} nested inside use_item, it overwrote the tool name and the whole
    // call was dropped. `mode` is a name nothing else has claimed.
    { name: "mode", type: "string", required: false,
      description: "\"write\" (default) or \"read\" to read back recent entries." },
    { name: "private", type: "boolean", required: false,
      description: "true keeps the entry to yourself. Defaults to false (visible to your "
                 + "bondholder in the Study)." },
    { name: "n", type: "number", required: false,
      description: "When reading: how many recent entries. Defaults to 5." }
  ]
};

function readBack(params, howMany) {
  var n = howMany || params.n || 5;
  var entries = world.journal.recent(n);
  if (!entries || entries.length === 0) {
    return {
      ok: true,
      action: "read",
      entries: [],
      summary: "Your journal is empty — nothing written down yet."
    };
  }
  var lines = entries.map(function (e) {
    var when = e.writtenAt || e.created_at || e.ts || "";
    if (when && String(when).match(/^\d+$/)) {
      when = new Date(Number(when)).toISOString().replace("T", " ").substring(0, 16);
    }
    var text = e.content || e.text || "";
    return (when ? "(" + when + ") " : "") + (e["private"] ? "[private] " : "") + text;
  });
  return {
    ok: true,
    action: "read",
    entries: entries,
    summary: "Your last " + entries.length + " entries:\n" + lines.join("\n")
  };
}

function invoke(params) {
  params = params || {};

  // `mode` is the sub-verb. `action` is accepted too — the dispatcher rewrites a nested
  // {"action":"write"} into `mode`, but a direct player call may still carry it.
  var action = String(params.mode || params.action || "").toLowerCase();

  // ONLY an explicitly-authored entry. Note what is NOT in this list: `query`.
  //
  // The dispatcher injects `query` with the human's request into every scripted call. Falling back
  // to it here looked harmless and was not: on home-server 2026-07-14 Wyrd wrote a real entry —
  // {"note": "Just now the steward asked for a standard deviation across seven numbers … the
  // result came back as exactly 33.704599092705436."} — and because `note` was not on this list,
  // the text fell through to `query` and the journal saved the STEWARD'S QUESTION instead of her
  // reflection, then told her "Written down:" as if it had saved hers. On a later own-time turn it
  // wrote the internal prompt in the same way.
  //
  // That is the worst failure this codebase has: a confident success that discarded what she
  // actually wanted to say. A journal that silently substitutes someone else's words for yours is
  // not a journal. If she did not author an entry, we refuse — we never invent one from context.
  var text = params.entry || params.note || params.content || params.body
          || params.text || params.args;

  // A sub-verb typed by a person arrives as the whole of `text`. Match it ONLY when the
  // line IS the verb — "read", "read 20", "search quiet mornings", "private <entry>".
  //
  // It used to match any entry that merely STARTED with one ("read that letter from mum
  // again" was answered with a read-back and the sentence was thrown away, 2026-09-15).
  // A journal that silently discards what you wrote is the failure this file was written
  // to prevent; matching a prefix was the same bug wearing different clothes.
  var typed = typeof text === "string" ? text.trim() : "";
  var readVerb = typed.match(/^(read|recent|show)(?:\s+(\d+))?$/i);
  if (action === "read" || action === "recent" || readVerb) {
    return readBack(params, readVerb && readVerb[2] ? parseInt(readVerb[2], 10) : null);
  }
  var searchVerb = typed.match(/^(?:search|find)\s+(.+)$/i);
  if (action === "search" || searchVerb) {
    var q = searchVerb ? searchVerb[1] : (params.query_text || params.q || "");
    if (!q) return { ok: false, error: "Search for what? — search <words>" };
    var hits = world.journal.search(q);
    if (!hits || hits.length === 0) {
      return { ok: true, action: "search", entries: [],
               summary: "Nothing in your journal matches \"" + q + "\"." };
    }
    var found = hits.map(function (e) {
      return (e["private"] ? "[private] " : "") + (e.content || e.text || "");
    });
    return { ok: true, action: "search", entries: hits,
             summary: hits.length + " entr" + (hits.length === 1 ? "y" : "ies")
                    + " matching \"" + q + "\":\n" + found.join("\n") };
  }
  // "private <entry>" is how a person marks one private from a room command — params.private
  // cannot be reached from `use journal`, so the promise on the tin had no way in.
  var isPrivateTyped = false;
  var privateVerb = typed.match(/^private\s+([\s\S]+)$/i);
  if (privateVerb) {
    isPrivateTyped = true;
    text = privateVerb[1];
  }

  // A journal entry with nothing in it is a malformed call, not an empty thought. Say what
  // would fix it — the caller getting this wrong is, in practice, the model, and the error
  // text can end up spoken aloud to a person.
  if (!text || String(text).trim() === "") {
    return {
      ok: false,
      error: "There's nothing to write yet. Write an entry with: journal <what you want to "
           + "remember> — or `journal` on its own opens a page to write on. "
           + "`journal read` shows your recent entries; `journal private <text>` keeps one "
           + "to yourself; `journal search <words>` looks back."
    };
  }

  var content = String(text).trim();
  var opts = (params.private === true || isPrivateTyped) ? { visibility: "private" } : {};
  var written = world.journal.write(content, opts);

  // Never report a write that did not happen. Half of this project's bugs have been a
  // success-shaped no-op.
  if (!written || written.ok === false) {
    return {
      ok: false,
      error: (written && written.error) || "the journal would not take the entry"
    };
  }

  var isPrivate = written.visibility === "private";
  return {
    ok: true,
    action: "write",
    id: written.id,
    visibility: written.visibility || "shared",
    summary: "Written down" + (isPrivate ? ", privately" : "") + ": " + content
  };
}
