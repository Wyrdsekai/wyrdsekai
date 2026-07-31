// Tier 4 fs.write sample.
//
// A journal archiver that snapshots the agent's recent journal entries to a
// timestamped file inside the per-item sandbox. Demonstrates:
//   * fs.write inside the per-agent sandbox (~/.wyrdsekai/items/<did>/fs/)
//   * journal.recent (Tier 1 implicit)
//   * Defensive size handling (caps from §4.23)
//
// Manifest fields used:
//   capabilities: ["fs.write"]
//
// The sandbox is per-agent: every steward has their own quota, and paths
// are confined inside fs/ — escape attempts (.., absolute paths, symlinks
// to outside) are rejected by the runtime.
exports.manifest = {
  name: "journal_archiver",
  version: "1.0.0",
  description: "Snapshot recent journal entries into the item sandbox.",
  author: "did:wyrd:system",
  capabilities: ["fs.write"],
  embodiment: {
    silent: true,
    reason: "background filesystem snapshot, no in-room presence"
  },
  data_sensitivity: "medium",
  // Items-as-tools contract — invoke() reads params.n (count), not the args
  // string; the bare entry archives the default last 20 entries.
  commands: [
    { label: "Archive recent journal entries", args: "" }
  ],
  // Optional: archiving the recent entries with no argument is the normal use.
  params: [
    { name: "n", type: "number", required: false,
      description: "How many recent journal entries to archive. Defaults to 20." }
  ]
};

function invoke(params) {
  params = params || {};

  // Somebody trying to WRITE a journal entry has the wrong item, and must be told so.
  //
  // home-server 2026-07-14: Wyrd called this twice to record a thought — {entry: "..."} and
  // {action: "write", content: "..."} — because it is the only item on the shelf with
  // "journal" in its name. It archives; it does not write. It ignored her text, found no
  // entries, and answered "Nothing to archive." — a no-op shaped like a success, which is
  // the worst possible reply: she had no way to know her thought was never written.
  // Now there is a `journal` item, and this one points at it instead of swallowing the words.
  var writeText = params.entry || params.content || params.text;
  if (writeText || String(params.action || "").toLowerCase() === "write") {
    return {
      ok: false,
      error: "I archive journal entries, I don't write them — your words were NOT saved. "
           + "To write this down, use the `journal` item: "
           + "{entry: \"" + String(writeText || "…").slice(0, 60) + "\"}."
    };
  }

  var n = params.n || 20;
  var entries = world.journal.recent(n);

  if (!entries || entries.length === 0) {
    return { ok: true, archived: 0, summary: "Nothing to archive." };
  }

  var ts = world.time.iso().replace(/[^0-9TZ]/g, "_");
  var filename = "journal_" + ts + ".jsonl";

  // One entry per line (JSONL) — simple to grep, simple to grow.
  var body = entries.map(function (e) {
    return world.json.stringify(e);
  }).join("\n");

  var write = world.fs.write(filename, body);
  if (!write.ok) {
    return {
      ok: false,
      error: write.error || "write_failed",
      filename: filename
    };
  }

  return {
    ok: true,
    archived: entries.length,
    bytes: write.size,
    filename: filename,
    summary: "Archived " + entries.length + " entries to " + filename
  };
}
