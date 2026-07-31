// Safe furnishing — silver runes ("safe-runes" RoomObject, display name
// "silver runes" → normalized linkage "silver_runes"). Collision-checked:
// no other room or template names an object "silver runes".
//
// "Protective runes etched in silver, pulsing with ward energy." What the
// wards actually preserve: the node's backup snapshots, read through
// world.safe.snapshots() (BackupOrchestrator manifests — id, location,
// timestamp, size), plus the named vault slots via world.safe.list_slots().
// Read-only by design this pass: snapshots are TAKEN on the node's own
// schedule and RESTORED host-side by the server admin — the runes say so
// honestly instead of pretending. Self-documenting: bare use renders the
// reading AND the command list.
exports.manifest = {
  name: "silver_runes",
  version: "1.0.0",
  description: "Protective runes over the vault — the backup snapshots the wards preserve, and the named slots they guard.",
  author: "did:wyrd:system",
  capabilities: ["safe.list_slots", "safe.has"],
  embodiment: {
    silent: false,
    emits: ["body_language", "ambient_shift"],
    descriptor_template: "{actor} lays a palm on the silver runes; the ward-light traces every sealed thing they keep."
  },
  commands: [
    { label: "Read the wards", args: "" },
    { label: "Backup snapshots", args: "snapshots" },
    { label: "Vault slots", args: "slots" },
    { label: "Runes help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use silver runes            — snapshots preserved and slots guarded",
    "  use silver runes snapshots  — the backup snapshots, newest first",
    "  use silver runes slots      — the named vault slots",
    "  use silver runes help       — this help",
    "The runes are read-only: snapshots are taken on the node's own schedule,",
    "and restoring one is a host-side operation by the server admin."
  ].join("\n");
}

function fmtSize(bytes) {
  var n = Number(bytes) || 0;
  if (n >= 1048576) return (Math.round(n / 104857.6) / 10) + " MB";
  if (n >= 1024) return Math.round(n / 1024) + " KB";
  return n + " B";
}

function renderSnapshots() {
  var snaps = null;
  try { snaps = world.safe.snapshots(); } catch (e) { snaps = null; }
  if (snaps === null) {
    return "The runes pulse but show nothing — the snapshot ledger isn't bound on this surface.";
  }
  if (snaps.length === 0) {
    return "The runes are dim: no backup snapshots preserved yet. The node takes them on its own schedule once backups are configured.";
  }
  var lines = ["The ward-light traces " + snaps.length + " preserved snapshot(s), newest first:"];
  for (var i = 0; i < snaps.length; i++) {
    var s = snaps[i];
    lines.push("  " + (s.id || "?")
      + (s.timestamp ? "  " + s.timestamp : "")
      + "  " + fmtSize(s.sizeBytes)
      + (s.source ? "  (" + s.source + ")" : ""));
    if (s.location) lines.push("      " + s.location);
  }
  return lines.join("\n");
}

function renderSlots() {
  var slots = null;
  try { slots = world.safe.list_slots(); } catch (e) { slots = null; }
  if (slots === null) {
    return "The alcoves stay sealed — the vault-slot ledger isn't bound on this surface.";
  }
  if (slots.length === 0) {
    return "No named vault slots yet — the alcoves stand empty behind the wards.";
  }
  var lines = ["Named slots behind the wards (" + slots.length + "):"];
  for (var i = 0; i < slots.length; i++) {
    lines.push("  " + slots[i]);
  }
  lines.push("The runes name the slots but never read their contents aloud.");
  return lines.join("\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var args = raw.toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The runes guard the vault: backup snapshots preserved, named slots sealed." + usageFooter() };
  }
  if (args === "snapshots" || args === "backups") {
    return { ok: true, text: renderSnapshots() + usageFooter() };
  }
  if (args === "slots") {
    return { ok: true, text: renderSlots() + usageFooter() };
  }
  if (args === "") {
    return { ok: true, text: renderSnapshots() + "\n\n" + renderSlots() + usageFooter() };
  }
  return { ok: true, text: "The runes do not answer to '" + raw + "'." + usageFooter() };
}
