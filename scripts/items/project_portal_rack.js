// CodePlane Workshop furnishing — project portal rack
// ("workshop-portal-rack" RoomObject, display name "project portal rack"
// → normalized linkage "project_portal_rack").
//
// The brass rack meant to hold doorways into external repositories. The
// portal LIFECYCLE (link / mount / unlink) is
// §17.7 — a follow-on task with no world.* surface yet, and the rack says
// so honestly instead of miming a mount.
//
// What IS real today: the coding backends that would work behind a portal.
// Bare `use project portal rack` lists them via world.coding.backends()
// and names the real dispatch path (say 'code <task>' in the Workshop).
exports.manifest = {
  name: "project_portal_rack",
  version: "1.0.0",
  description: "A brass rack awaiting project portals — shows which coding backends stand ready; portal linking itself is not yet wired (§17.7).",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} tests an empty hook on the portal rack; the brass rings softly, patient."
  },
  commands: [
    { label: "Inspect the rack", args: "" },
    { label: "Rack help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use project portal rack        — hooks, and the backends behind them",
    "  use project portal rack help   — this help",
    "Honest note: portal linking (mounting an external repo on a hook) is",
    "designed but not wired yet. Real coding work",
    "dispatches today by saying 'code <task>' (or 'explore <task>') in the",
    "Workshop, or through your companion."
  ].join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The rack will one day hold project portals; today it reports what "
        + "stands ready behind it." + usageFooter()
    };
  }
  if (args !== "") {
    return {
      ok: true,
      text: "The rack has no hook labeled '" + args + "'. Portal linking isn't "
        + "wired yet (§17.7)." + usageFooter()
    };
  }

  var backends = null;
  try { backends = world.coding.backends(); } catch (e) { backends = null; }

  var lines = ["The brass rack stands with its hooks empty — no project portals "
    + "are linked (portal lifecycle is §17.7, not yet wired)."];
  if (backends && backends.length > 0) {
    lines.push("");
    lines.push("Behind the rack, ready to work once a task is dispatched:");
    for (var i = 0; i < backends.length; i++) {
      var b = backends[i];
      var line = "  " + (b.name || "?");
      if (typeof b.available !== "undefined") {
        line += b.available ? "  [ready]" : "  [cold]";
      }
      if (b.description) line += " — " + b.description;
      lines.push(line);
    }
  } else {
    lines.push("");
    lines.push("No coding backends report on this surface right now.");
  }
  return { ok: true, text: lines.join("\n") + usageFooter(), backends: backends || [] };
}
