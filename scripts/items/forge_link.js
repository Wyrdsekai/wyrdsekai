// CodePlane Workshop furnishing — forge link ("workshop-forge-link"
// RoomObject, display name "forge link" → normalized linkage "forge_link").
//
// The archway by which the day's coding experience becomes soul fragments
// during sleep passes. Read surface is world.forge.cycle_status() and
// world.forge.history() (Tier 1 — same surfaces forge_workbench.js reads).
// Bare `use forge link` reads the archway's state AND lists commands.
//
// The consolidation itself is not triggered from this side: sleep passes
// run on their own cycle, or a steward speaks 'forge' / 'grow' in The
// Forge. The link says so honestly.
exports.manifest = {
  name: "forge_link",
  version: "1.0.0",
  description: "A faintly glowing archway to the Forge — read the consolidation cycle's state; the sleep passes themselves run from the Forge side.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The forge link brightens a shade as {actor} nears, ember-light tracing the archway's rim."
  },
  commands: [
    { label: "Read the link", args: "" },
    { label: "Recent consolidations", args: "history" },
    { label: "Link help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use forge link            — the consolidation cycle's current state",
    "  use forge link history    — recent consolidation passes",
    "  use forge link help       — this help",
    "The passes themselves run on the sleep cycle, or from The Forge by speaking",
    "'forge <name>' / 'grow <name>' there. This side only watches the glow."
  ].join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The forge link is the path from day-work to soul fragments." + usageFooter()
    };
  }

  if (args === "history") {
    var recent = null;
    try { recent = world.forge.history(5); } catch (e) { recent = null; }
    if (!recent || recent.length === 0) {
      return {
        ok: true,
        text: "The archway holds no memory of recent passes — none recorded on this "
          + "surface yet." + usageFooter()
      };
    }
    var hLines = ["Ember-marks of recent passes:"];
    for (var i = 0; i < recent.length; i++) {
      var h = recent[i];
      var line = "  ";
      var wrote = false;
      for (var k in h) {
        if (h[k] === null || typeof h[k] === "object") continue;
        line += (wrote ? ", " : "") + k + "=" + h[k];
        wrote = true;
      }
      hLines.push(wrote ? line : "  (an unlabeled pass)");
    }
    return { ok: true, text: hLines.join("\n") + usageFooter(), history: recent };
  }

  if (args === "") {
    var status = null;
    try { status = world.forge.cycle_status(); } catch (e) { status = null; }
    if (!status) {
      return {
        ok: true,
        text: "The archway is dark — the Forge cycle isn't readable from this "
          + "surface right now." + usageFooter()
      };
    }
    var lines = ["The archway's glow, read closely:"];
    if (typeof status.fragmentsThisCycle !== "undefined") {
      lines.push("  Experience gathered this cycle: " + status.fragmentsThisCycle
        + " fragment(s) waiting for the next pass");
    }
    if (status.lastRunAt && status.lastRunAt > 0) {
      lines.push("  Last pass: " + new Date(status.lastRunAt).toISOString());
    } else {
      lines.push("  Last pass: none recorded on this surface");
    }
    if (status.nextRunAt && status.nextRunAt > 0) {
      lines.push("  Next pass: " + new Date(status.nextRunAt).toISOString());
    }
    return { ok: true, text: lines.join("\n") + usageFooter(), cycleStatus: status };
  }

  return { ok: true, text: "The archway doesn't answer to '" + args + "'." + usageFooter() };
}
