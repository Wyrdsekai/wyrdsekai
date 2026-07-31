// Study furnishing — maintenance dial ("study-maintenance-dial"
// RoomObject, display name "maintenance dial" → normalized linkage
// "maintenance_dial").
//
// The dial is wired to the machinery beneath: world.maintenance, backed
// by MaintenanceService. Bare use reads the status board — maintenance
// mode, backup schedule, last backup, staged restore if any. The steward
// may flip maintenance mode (non-stewards can't log in while it's on),
// run a backup now, and set the backup cadence. The service enforces the
// steward check itself — the dial renders refusals honestly instead of
// pre-judging the caller. Restores are staged from the key chest and
// applied by a restart.
exports.manifest = {
  name: "maintenance_dial",
  version: "2.0.0",
  description: "A brass dial for node maintenance — maintenance mode, backup now, backup schedule — wired to the machinery beneath.",
  author: "did:wyrd:system",
  capabilities: ["maintenance.set_mode", "maintenance.backup"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} rests a hand on the maintenance dial; it turns with weight now, gears taking up the motion below."
  },
  commands: [
    { label: "Read the dial", args: "" },
    { label: "Maintenance on", args: "mode on <reason>" },
    { label: "Maintenance off", args: "mode off" },
    { label: "Back up now", args: "backup now" },
    { label: "Set backup schedule", args: "schedule <hours|off>" },
    { label: "Dial help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use maintenance dial                    — read the status board",
    "  use maintenance dial mode on <reason>   — maintenance mode ON (steward-only entry)",
    "  use maintenance dial mode off           — maintenance mode OFF",
    "  use maintenance dial backup now         — run a backup snapshot right now",
    "  use maintenance dial schedule <hours>   — back up every N hours ('off' stops it)",
    "  use maintenance dial help               — this help",
    "The dial answers only to the steward's hand; restores are staged at the key chest."
  ].join("\n");
}

function renderStatus() {
  var s = null;
  try { s = world.maintenance.status(); } catch (e) { s = null; }
  if (!s || !s.ok) {
    return "The dial turns loosely — the maintenance machinery isn't reachable from this surface"
      + (s && s.error ? " (" + s.error + ")" : "") + ".";
  }
  var lines = ["The dial's face lights up, needle steady over the household's state:", ""];
  if (s.on) {
    lines.push("  ◦ MAINTENANCE — ON" + (s.reason ? " — " + s.reason : ""));
    if (s.setBy) lines.push("      set by " + s.setBy + (s.since ? " at " + s.since : ""));
    lines.push("      only the steward may enter while the dial is set.");
  } else {
    lines.push("  ◦ MAINTENANCE — off; the household is open.");
  }
  if (s.scheduleHours && s.scheduleHours > 0) {
    lines.push("  ◦ BACKUPS     — every " + s.scheduleHours + " hour"
      + (s.scheduleHours === 1 ? "" : "s")
      + (s.lastScheduledBackup ? "; last scheduled run " + s.lastScheduledBackup : "; hasn't fired yet"));
  } else {
    lines.push("  ◦ BACKUPS     — no schedule set on the dial ('schedule <hours>' to arm one).");
  }
  if (s.snapshotCount && s.snapshotCount > 0) {
    lines.push("  ◦ KEPT        — " + s.snapshotCount + " snapshot"
      + (s.snapshotCount === 1 ? "" : "s")
      + (s.latestSnapshotId ? "; newest " + s.latestSnapshotId
        + (s.latestSnapshotAt ? " (" + s.latestSnapshotAt + ")" : "") : ""));
  } else {
    lines.push("  ◦ KEPT        — no snapshots yet ('backup now' makes the first).");
  }
  if (s.staged) {
    lines.push("");
    lines.push("  ⚠ RESTORE STAGED — snapshot " + s.staged.snapshotId
      + (s.staged.stagedBy ? " by " + s.staged.stagedBy : "") + ".");
    lines.push("    It applies at the next restart: turn the Scroll of Settings");
    lines.push("    ('use scroll apply'), or cancel at the key chest ('restore cancel').");
  }
  return lines.join("\n");
}

function doMode(rest) {
  var parts = rest.split(/\s+/);
  var setting = (parts[0] || "").toLowerCase();
  if (setting !== "on" && setting !== "off") {
    return "The dial clicks between ON and OFF only: mode on <reason> / mode off." + usageFooter();
  }
  var reason = setting === "on" ? rest.slice(rest.indexOf(parts[0]) + parts[0].length).trim() : "";
  var res = null;
  try { res = world.maintenance.setMode(setting === "on", reason); } catch (e) { res = null; }
  if (!res) {
    return "The dial resists — the maintenance machinery isn't reachable from this surface."
      + usageFooter();
  }
  if (!res.ok) {
    return "The dial will not turn: " + (res.error || "the change was refused") + "." + usageFooter();
  }
  if (setting === "on") {
    return "The dial settles on MAINTENANCE" + (reason ? " — " + reason : "")
      + ". Only the steward may enter until it turns back.";
  }
  return "The dial eases back; the household is open again.";
}

function doBackup(rest) {
  if ((rest || "").trim().toLowerCase() !== "now") {
    return "The dial takes 'backup now' — one firm turn." + usageFooter();
  }
  var res = null;
  try { res = world.maintenance.backupNow(); } catch (e) { res = null; }
  if (!res) {
    return "The dial resists — the maintenance machinery isn't reachable from this surface."
      + usageFooter();
  }
  if (!res.ok) {
    return "No snapshot was taken: " + (res.error || "the backup was refused") + "." + usageFooter();
  }
  return "The machinery hums and settles: snapshot " + (res.id || "?") + " packed away"
    + (res.sizeBytes ? " (" + res.sizeBytes + " bytes)" : "")
    + ". The key chest keeps it.";
}

function doSchedule(rest) {
  var arg = (rest || "").trim().toLowerCase();
  var hours;
  if (arg === "off" || arg === "0") {
    hours = 0;
  } else {
    hours = parseInt(arg, 10);
    if (!isFinite(hours) || isNaN(hours) || hours < 0 || String(hours) !== arg) {
      return "The schedule ring takes a whole number of hours, or 'off': schedule <hours|off>."
        + usageFooter();
    }
  }
  var res = null;
  try { res = world.maintenance.setSchedule(hours); } catch (e) { res = null; }
  if (!res) {
    return "The dial resists — the maintenance machinery isn't reachable from this surface."
      + usageFooter();
  }
  if (!res.ok) {
    return "The schedule ring will not turn: " + (res.error || "the change was refused") + "."
      + usageFooter();
  }
  if (hours === 0) {
    return "The schedule ring clicks to rest — no more timed backups from the dial.";
  }
  return "The schedule ring engages: a snapshot every " + hours + " hour"
    + (hours === 1 ? "" : "s") + ", starting " + hours + " hour"
    + (hours === 1 ? "" : "s") + " from now. It survives restarts.";
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var firstSpace = raw.indexOf(" ");
  var verb = (firstSpace === -1 ? raw : raw.slice(0, firstSpace)).toLowerCase();
  var rest = firstSpace === -1 ? "" : raw.slice(firstSpace + 1).trim();

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The maintenance dial sets maintenance mode (steward-only entry while on), "
        + "runs a backup now, and arms a backup schedule. Restores are staged at the "
        + "key chest and applied by a restart (Scroll of Settings, 'use scroll apply')."
        + usageFooter()
    };
  }
  if (verb === "mode") {
    return { ok: true, text: doMode(rest) };
  }
  if (verb === "backup") {
    return { ok: true, text: doBackup(rest) };
  }
  if (verb === "schedule") {
    return { ok: true, text: doSchedule(rest) };
  }
  if (raw !== "") {
    return { ok: true, text: "The dial doesn't answer to '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderStatus() + usageFooter() };
}
