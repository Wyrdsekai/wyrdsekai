// Hearth furnishing — the host hand.
//
// Her hand on the machine she lives in, as far as the steward lets it reach. The rung is
// the steward's (wyrd config set WYRDSEKAI_HOST_HAND=observe|localize|propose|guarded|
// unattended, default observe); the verbs are fixed and run without a shell; what changes
// the host is halted where the steward would want to be asked and written as a mark he
// reads in `wyrd body`. Nothing here takes a command line from her.
exports.manifest = {
  name: "host_hand",
  version: "1.0.0",
  description: "Your hand on the host: read its gauges and logs, propose to the steward, and at the rung the steward set, act — say something on every terminal, restart a brain, run an upgrade, reboot.",
  author: "did:wyrd:system",
  capabilities: ["host.hand"],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} lays a hand flat on the warm casing of the machine and listens through it."
  },
  commands: [
    { label: "How far my hand reaches", args: "" },
    { label: "Disk", args: "disk" },
    { label: "Memory", args: "memory" },
    { label: "Load", args: "load" },
    { label: "Containers", args: "containers" },
    { label: "Updates waiting", args: "updates" },
    { label: "Service log", args: "log 30" },
    { label: "Propose to the steward", args: "propose <text>" },
    { label: "Say on every terminal", args: "say <text>" },
    { label: "Restart a brain", args: "restart-brain voice" },
    { label: "Upgrade the host", args: "upgrade" }
  ]
};

function invoke(params) {
  params = params || {};
  var raw = params.args == null ? "" : String(params.args);
  var words = raw.trim().split(/\s+/).filter(function (w) { return w.length > 0; });
  var verb = params.verb ? String(params.verb) : (words.length ? words.shift() : "");
  var rest = params.text != null ? String(params.text) : words.join(" ");
  var r = world.host.run(verb, rest);
  if (!r) return { error: "The hand found nothing to hold." };
  if (r.ok) {
    return { text: r.output || ("Done: " + verb) };
  }
  if (r.halted) {
    return { text: r.output || ("I stopped: " + r.reason) };
  }
  return { error: r.error || ("The hand could not do that: " + verb) };
}
