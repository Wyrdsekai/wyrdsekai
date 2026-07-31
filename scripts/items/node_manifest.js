// Study furnishing — node manifest ("study-node-manifest" RoomObject,
// display name "node manifest" → normalized linkage "node_manifest").
//
// A mechanical wall display of the household's enrolled nodes via
// world.nodes.list() — the local node first, then mesh peers with
// connection state, latency, and app version. On a single-node install
// the list is empty and the display says so honestly. Enrolling or
// removing nodes is NOT in-world yet — that flows through `wyrd relay
// join` or the installer, and the manifest points there instead of
// pretending.
exports.manifest = {
  name: "node_manifest",
  version: "1.0.0",
  description: "Mechanical wall display of the household's enrolled nodes — which machines carry the world, and how they fare.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The node manifest's brass plates clatter and realign as {actor} studies it, each plate a machine of the household."
  },
  commands: [
    { label: "Read the node manifest", args: "" },
    { label: "Manifest help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use node manifest        — the household's enrolled nodes",
    "  use node manifest help   — this help",
    "Enrolling or removing nodes isn't done from the manifest yet —",
    "`wyrd relay join` enrolls a machine, and the installer sets up new ones."
  ].join("\n");
}

function nodeLine(n) {
  var status = n.connected ? "● connected" : "○ unreachable";
  var line = "  " + status + "  " + (n.nodeId || "?");
  if (n.self) line += "  (this node)";
  if (n.latencyMs !== undefined && n.latencyMs !== null && !n.self) {
    line += "  " + n.latencyMs + "ms";
  }
  if (n.appVersion) line += "  v" + n.appVersion;
  return line;
}

function renderNodes() {
  var nodes = null;
  try { nodes = world.nodes.list(); } catch (e) { nodes = null; }
  if (!nodes || nodes.length === 0) {
    return "The manifest shows a single unlabeled plate — this world stands on one node "
      + "alone, with no household mesh enrolled. (That is a fine way to live; "
      + "`wyrd relay join` on another machine would add a second plate.)";
  }
  // Self node first, then peers in given order.
  var selfNodes = [], peers = [];
  for (var i = 0; i < nodes.length; i++) {
    if (nodes[i] && nodes[i].self) selfNodes.push(nodes[i]);
    else peers.push(nodes[i]);
  }
  var lines = ["The brass plates align; the household's machines:"];
  var j;
  for (j = 0; j < selfNodes.length; j++) lines.push(nodeLine(selfNodes[j]));
  for (j = 0; j < peers.length; j++) lines.push(nodeLine(peers[j]));
  var connected = 0;
  for (j = 0; j < nodes.length; j++) if (nodes[j] && nodes[j].connected) connected++;
  lines.push("");
  lines.push(nodes.length + " node" + (nodes.length === 1 ? "" : "s") + " enrolled, "
    + connected + " connected.");
  return lines.join("\n");
}

function invoke(params) {
  var args = String((params && (params.args || params.text || params.target)) || "").trim().toLowerCase();

  if (args === "help" || args === "?") {
    return {
      ok: true,
      text: "The node manifest shows every machine enrolled in the household mesh — "
        + "which carry the world, which are reachable, and what they run." + usageFooter()
    };
  }
  if (args !== "") {
    return { ok: true, text: "The manifest's plates don't answer to '" + args + "'." + usageFooter() };
  }
  return { ok: true, text: renderNodes() + usageFooter() };
}
