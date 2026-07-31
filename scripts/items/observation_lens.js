// Observatory furnishing — observation lens ("observation-lens" RoomObject
// in the std observatory room template; display name "observation lens"
// → normalized linkage "observation_lens").
//
// The crystalline lens focused on zone patterns. Read surfaces are
// world.bridge.zone_status(), world.bridge.topology(), and
// world.bridge.system_metrics() (§4.19, Tier 4 — declared below). Bare
// `use observation lens` reads the zone AND lists commands.
//
// The pattern board beside the lens is a separate instrument with its own
// keeper — this lens doesn't speak for it.
exports.manifest = {
  name: "observation_lens",
  version: "1.0.0",
  description: "A crystalline lens for reading the zone — status, topology, and the machinery's pulse.",
  author: "did:wyrd:system",
  capabilities: ["bridge.zone_status", "bridge.topology", "bridge.system_metrics"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} bends to the observation lens; the zone swims into cold, bright focus."
  },
  commands: [
    { label: "Read the zone", args: "" },
    { label: "Read the topology", args: "topology" },
    { label: "Read the machinery", args: "metrics" },
    { label: "Lens help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use observation lens             — zone status at a glance",
    "  use observation lens topology    — how the nodes hang together",
    "  use observation lens metrics     — the machinery's pulse",
    "  use observation lens help        — this help"
  ].join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return { ok: true, text: "The lens reads the zone's shape and pulse." + usageFooter() };
  }

  if (args === "topology") {
    var topo = null;
    try { topo = world.bridge.topology(); } catch (e) { topo = null; }
    if (!topo) {
      return {
        ok: true,
        text: "The lens finds no topology to focus on from this surface." + usageFooter()
      };
    }
    return { ok: true, text: "Through the lens, the weave of nodes:\n  " + topo + usageFooter() };
  }

  if (args === "metrics") {
    var metrics = null;
    try { metrics = world.bridge.system_metrics(); } catch (e) { metrics = null; }
    if (!metrics) {
      return {
        ok: true,
        text: "The machinery's pulse doesn't reach this surface right now." + usageFooter()
      };
    }
    var mLines = ["The machinery's pulse, read through the lens:"];
    var found = false;
    for (var k in metrics) {
      if (metrics[k] === null || typeof metrics[k] === "object") continue;
      mLines.push("  " + k + ": " + metrics[k]);
      found = true;
    }
    if (!found) mLines.push("  (steady, and saying nothing more)");
    return { ok: true, text: mLines.join("\n") + usageFooter(), metrics: metrics };
  }

  if (args === "" || args === "status" || args === "observe" || args === "zone") {
    var status = null;
    try { status = world.bridge.zone_status(); } catch (e) { status = null; }
    if (!status) {
      return {
        ok: true,
        text: "The lens clouds — zone status isn't readable from this surface "
          + "right now." + usageFooter()
      };
    }
    var lines = ["The zone, in cold focus:"];
    for (var key in status) {
      if (status[key] === null || typeof status[key] === "object") continue;
      lines.push("  " + key + ": " + status[key]);
    }
    return { ok: true, text: lines.join("\n") + usageFooter(), status: status };
  }

  return { ok: true, text: "The lens won't focus on '" + args + "'." + usageFooter() };
}
