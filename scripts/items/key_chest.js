// Study furnishing — key chest ("study-key-chest" RoomObject, display
// name "key chest" → normalized linkage "key_chest").
//
// A cedar chest clasped in brass: the household's backup snapshots via
// world.safe.snapshots() — id, timestamp, size, and source for each —
// plus the real levers: `create` packs a new snapshot now
// (world.maintenance.backupNow), `restore <id>` STAGES a restore that
// the next restart applies (world.maintenance.stageRestore — the live
// database is never touched in place), and `restore cancel` un-stages
// it. The service enforces the steward check itself — the chest renders
// refusals honestly instead of pre-judging the caller.
exports.manifest = {
  name: "key_chest",
  version: "2.0.0",
  description: "Cedar chest clasped in brass — every backup snapshot the household keeps; it can pack a new one or stage an old one for restore.",
  author: "did:wyrd:system",
  capabilities: ["maintenance.backup", "maintenance.stage_restore"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} unclasps the key chest; the scent of cedar rises over neat, dated bundles."
  },
  commands: [
    { label: "Open the chest", args: "" },
    { label: "Pack a snapshot", args: "create" },
    { label: "Stage a restore", args: "restore <id>" },
    { label: "Cancel staged restore", args: "restore cancel" },
    { label: "Chest help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use key chest                 — the household's backup snapshots",
    "  use key chest create          — pack a new snapshot now",
    "  use key chest restore <id>    — stage a snapshot for restore at next restart",
    "  use key chest restore cancel  — un-stage a pending restore",
    "  use key chest help            — this help",
    "Packing and staging answer only to the steward's hand. `wyrd backup` /",
    "`wyrd restore` still work at the door."
  ].join("\n");
}

function humanSize(bytes) {
  var b = Number(bytes) || 0;
  if (b >= 1024 * 1024 * 1024) return (Math.round(b / (1024 * 1024 * 1024) * 10) / 10) + " GB";
  if (b >= 1024 * 1024) return (Math.round(b / (1024 * 1024) * 10) / 10) + " MB";
  if (b >= 1024) return (Math.round(b / 1024 * 10) / 10) + " KB";
  return b + " B";
}

function stagedNotice() {
  var s = null;
  try { s = world.maintenance.status(); } catch (e) { s = null; }
  if (!s || !s.ok || !s.staged) return "";
  return "\n\n⚠ A restore is staged: snapshot " + s.staged.snapshotId
    + (s.staged.stagedBy ? " by " + s.staged.stagedBy : "")
    + ". It applies at the next restart — turn the Scroll of Settings"
    + " ('use scroll apply') to take effect, or 'restore cancel' here.";
}

function renderSnapshots() {
  var snaps = null;
  try { snaps = world.safe.snapshots(); } catch (e) { snaps = null; }
  if (!snaps || snaps.length === 0) {
    return "The chest opens on bare cedar — no snapshots yet. "
      + "'create' here (or `wyrd backup` at the door) packs the first one."
      + stagedNotice();
  }
  var lines = ["The clasp gives; inside, the household's kept snapshots:"];
  for (var i = 0; i < snaps.length; i++) {
    var s = snaps[i];
    var line = "  📦  " + (s.id || "?");
    if (s.timestamp) line += "  " + s.timestamp;
    line += "  (" + humanSize(s.sizeBytes) + ")";
    if (s.source) line += "  from " + s.source;
    lines.push(line);
  }
  lines.push("");
  lines.push(snaps.length + " snapshot" + (snaps.length === 1 ? "" : "s") + " kept.");
  return lines.join("\n") + stagedNotice();
}

function doCreate() {
  var res = null;
  try { res = world.maintenance.backupNow(); } catch (e) { res = null; }
  if (!res) {
    return "The chest's clasp won't give — the backup machinery isn't reachable from this surface."
      + usageFooter();
  }
  if (!res.ok) {
    return "Nothing was packed: " + (res.error || "the backup was refused") + "." + usageFooter();
  }
  return "Cedar and brass close over a fresh bundle: snapshot " + (res.id || "?")
    + (res.sizeBytes ? " (" + humanSize(res.sizeBytes) + ")" : "") + " is kept.";
}

function doRestore(rest) {
  var arg = (rest || "").trim();
  if (!arg) {
    return "Restore which? restore <id> — the chest's list shows each snapshot's id."
      + usageFooter();
  }
  if (arg.toLowerCase() === "cancel") {
    var cleared = null;
    try { cleared = world.maintenance.clearStagedRestore(); } catch (e) { cleared = null; }
    if (!cleared) {
      return "The chest can't reach the staging shelf from this surface." + usageFooter();
    }
    if (!cleared.ok) {
      return "Nothing was un-staged: " + (cleared.error || "the cancel was refused") + "."
        + usageFooter();
    }
    return "The staged bundle goes back on its shelf — no restore will run at the next restart.";
  }
  var res = null;
  try { res = world.maintenance.stageRestore(arg); } catch (e) { res = null; }
  if (!res) {
    return "The chest can't reach the staging shelf from this surface." + usageFooter();
  }
  if (!res.ok) {
    return "The bundle stays put: " + (res.error || "the restore was refused") + "." + usageFooter();
  }
  return [
    "Snapshot " + res.snapshotId + " is set on the staging shelf — the live world is untouched.",
    "It takes effect at the next restart: turn the Scroll of Settings ('use scroll apply')",
    "or the maintenance dial to restart and take effect. 'restore cancel' un-stages it."
  ].join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The key chest keeps the household's backup snapshots — each one dated, sized, "
        + "and named for where it came from. It can pack a new one ('create') and stage an "
        + "old one for restore ('restore <id>'); a staged restore applies at the next "
        + "restart, never to the live world in place." + usageFooter()
    };
  }
  if (verb === "create") {
    return { ok: true, text: doCreate() };
  }
  if (verb === "restore") {
    return { ok: true, text: doRestore(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The chest doesn't answer to '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderSnapshots() + usageFooter() };
}
