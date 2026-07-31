// Phase R smart-home demo.
//
// Triggers a Home Assistant scene at sunrise. Companion equips this item
// at install; the scheduler fires `invoke({event: "tick"})` each morning,
// or the steward can `do morning lights` to trigger by hand.

// Items-as-tools contract — manifest converted from the legacy
// `function manifest()` form (which ItemManifestParser cannot see) to the
// canonical exports-header shape the loader parses. The loader's name rule
// is ^[a-z][a-z0-9_]{2,63}$, so the old display name "Morning Lights" lives
// on in the description instead.
// (NB: this comment must not spell out the assignment pattern itself —
// ItemManifestParser matches the first occurrence in the file head.)
exports.manifest = {
  name: "morning_lights",
  version: "1.0.0",
  description: "Morning Lights — eases the household into the day with a soft Home Assistant scene.",
  author: "did:wyrd:system",
  capabilities: [
    "hass.call_service",
    "hass.list_entities",
    "hass.get_state",
    "self.did"
  ],
  embodiment: {
    silent: false,
    emits: ["ambient_shift"],
    descriptor_template: "The household lights ease toward dawn — soft gold spreading across the walls."
  },
  safe_slots: ["hass.url", "hass.token"],
  // invoke() reads payload.scene (defaults to "scene.morning"), not the
  // args string; the bare entry triggers the default morning scene.
  commands: [
    { label: "Gentle the morning lights on", args: "" }
  ]
};

function invoke(payload) {
  // Scene id can be passed in or default to one a steward has set up.
  var sceneId = (payload && payload.scene) || "scene.morning";

  // Honesty wrap (cost_ledger pattern) — if the Home Assistant bridge
  // (world.hass) isn't wired in this zone, say so instead of throwing.
  var resp = null;
  try {
    resp = world.hass.call_service({
      domain: "scene",
      service: "turn_on",
      data: { entity_id: sceneId }
    });
  } catch (e) {
    resp = null;
  }

  if (!resp) {
    return {
      ok: false,
      error: "hass_unavailable",
      message: "The Home Assistant bridge (world.hass) isn't wired in this zone — the lights stay as they are."
    };
  }

  if (!resp.success) {
    return {
      ok: false,
      error: resp.error && resp.error.code,
      message: resp.error && resp.error.message
    };
  }

  return {
    ok: true,
    scene: sceneId,
    note: "Lights gentled on."
  };
}
