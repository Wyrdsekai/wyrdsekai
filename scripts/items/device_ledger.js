// Study furnishing — device ledger ("study-device-ledger" RoomObject,
// display name "device ledger" → normalized linkage "device_ledger").
//
// The description promises: "lists your paired devices… pair new devices
// or revoke lost ones." All three are real now:
//   list    — world.pairing.devices() (your paired devices + SSH keys,
//             caller-scoped) plus the pairing threshold (pending knocks,
//             active pair code, household key) via world.pairing.*
//   pair    — read the pending knock's code to the new device
//   revoke  — world.pairing.revokeDevice(id) (own devices; steward: any).
//             SSH keys are revoked with the `key` command (`key list`,
//             `key remove <n>`), which manages them per-account.
exports.manifest = {
  name: "device_ledger",
  version: "1.1.0",
  description: "Ledger of your paired devices and SSH keys — list them, watch the pairing threshold, revoke what's lost.",
  author: "did:wyrd:system",
  capabilities: [],
  embodiment: {
    silent: false,
    emits: ["body_language"],
    descriptor_template: "{actor} opens the device ledger, thumbing through its pages of names and knocks."
  },
  commands: [
    { label: "Read the device ledger", args: "" },
    { label: "Revoke a device", args: "revoke <device-id>" },
    { label: "Ledger help", args: "help" }
  ]
};

function usageFooter() {
  return [
    "",
    "Commands:",
    "  use device ledger                     — your devices + keys, and the pairing threshold",
    "  use device ledger revoke <device-id>  — strike a lost device from the ledger",
    "  use device ledger help                — this help",
    "SSH keys are managed with the `key` command (key list / key add / key remove <n>)."
  ].join("\n");
}

function renderDevices(devices) {
  var lines = ["Inked into the ledger, your devices and keys:"];
  var live = 0;
  for (var i = 0; i < devices.length; i++) {
    var d = devices[i];
    if (d.kind === "ssh-key") {
      var kp = String(d.keyLine || "").split(/\s+/);
      var b64 = kp.length >= 2 ? kp[1] : String(d.keyLine || "");
      var tail = b64.length > 12 ? "…" + b64.substring(b64.length - 12) : b64;
      lines.push("  ⚿  ssh key " + tail + (d.comment ? "  [" + d.comment + "]" : ""));
      if (d.addedAt) lines.push("       added: " + d.addedAt);
      live++;
    } else {
      var name = d.name || "(unnamed device)";
      var kind = d.type ? "  [" + d.type + "]" : "";
      var struck = d.revoked === true;
      lines.push("  " + (struck ? "✗" : "✦") + "  " + name + kind
        + (struck ? "  (revoked)" : "") + "  — id: " + (d.id || "?"));
      if (d.pairedAt) lines.push("       paired: " + d.pairedAt);
      if (d.lastSeen) lines.push("       last seen: " + d.lastSeen);
      if (!struck) live++;
    }
  }
  lines.push("");
  lines.push(live + " in good standing.");
  return lines.join("\n");
}

function renderThreshold() {
  var pending = null, code = null, key = null;
  try { pending = world.pairing.pending(); } catch (e) { pending = null; }
  try { code = world.pairing.code(); } catch (e) { code = null; }
  try { key = world.pairing.householdKey(); } catch (e) { key = null; }

  var lines = [];
  var hasPending = pending && pending.length > 0;
  if (hasPending) {
    lines.push("Fresh ink — devices knocking at your household's door:");
    for (var i = 0; i < pending.length; i++) {
      var p = pending[i];
      var name = p.deviceName ? p.deviceName : "(unnamed device)";
      var kind = p.deviceType ? "  [" + p.deviceType + "]" : "";
      lines.push("  ✦  " + name + kind);
      if (p.code) lines.push("       code: " + p.code);
      if (p.expiresAt) lines.push("       expires: " + p.expiresAt);
    }
    lines.push("  Approve by reading them the code; they enter on /api/pair/verify.");
  } else if (code) {
    lines.push("An active pairing code waits in the margin: " + code);
  }
  if (key) {
    lines.push("Pressed between the back pages — the household key (steward-only):");
    lines.push("  wyrdsekai join --key " + key);
  }
  return lines.join("\n");
}

function renderLedger() {
  var devices = null;
  try { devices = world.pairing.devices(); } catch (e) { devices = null; }

  var parts = [];
  if (devices && devices.length > 0) {
    parts.push(renderDevices(devices));
  } else {
    parts.push("The ledger's device pages are blank — nothing paired to your name yet, "
      + "or this surface isn't bound to your Home.");
  }
  var threshold = renderThreshold();
  if (threshold) parts.push(threshold);
  return parts.join("\n\n");
}

function invoke(params) {
  var raw = String((params && (params.args || params.text || params.target)) || "").trim();
  var verb = raw.split(/\s+/)[0].toLowerCase();
  var rest = raw.substring(verb.length).trim(); // case preserved — ids are exact

  if (verb === "help" || verb === "?") {
    return {
      ok: true,
      text: "The device ledger records your paired devices and SSH keys, shows who is "
        + "knocking to pair, and strikes lost devices from the record." + usageFooter()
    };
  }
  if (verb === "revoke") {
    if (!rest) {
      return { ok: true, text: "Which device? `use device ledger` lists each one's id." + usageFooter() };
    }
    var r = null;
    try { r = world.pairing.revokeDevice(rest); } catch (e) { r = null; }
    if (r && r.ok === true) {
      return { ok: true, text: "A line is drawn through " + (r.name || rest)
        + ". That device's standing is revoked; its next call will be refused." };
    }
    var why = r && r.error ? r.error : "this surface isn't bound to your Home";
    return { ok: false, text: "The ink refuses — " + why + "." + usageFooter() };
  }
  if (raw !== "") {
    return { ok: true, text: "The device ledger doesn't know '" + raw + "'." + usageFooter() };
  }
  return { ok: true, text: renderLedger() + usageFooter() };
}
