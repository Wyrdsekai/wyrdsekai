// Leather Chair scripted furnishing.
//
// Two coexisting code paths in v1:
//
// 1. The PRIMARY chair experience (Study `study-chair`) is data-driven via
//    StudyProvisioner: the seed RoomObject carries state.sittable=true plus
//    state.sitDescriptor + state.sitBodyLanguage. RoomActor.onSetPosture reads
//    those keys directly when the user types `sit at leather chair`. No JS
//    invocation is required for the Study chair — the room actor enriches the
//    posture descriptor and emits the body-language Emoted line entirely in
//    Java.
//
// 2. This .js file (with its manifest) covers the portable / craftable /
//    user-installed chair case. If a player installs a leather_chair item into
//    ~/.wyrdsekai/items/ or crafts one via the workbench, this script runs.
//    onUse → world.entity.setPosture (rich) + world.room.broadcastBodyLanguage.
// for the scripted-item API surface.

exports.manifest = {
  id: "leather_chair",
  // ItemManifestValidator requires snake_case `name`; the display label
  // "leather chair" is surfaced via `display_name` + the world-side alias list.
  name: "leather_chair",
  display_name: "leather chair",
  version: "1.0.0",
  author: "did:wyrd:foundation",
  description: "A worn leather chair, deep and inviting. The cushions hold the shape of long sittings; the arms are darkened where hands have rested.",
  takeable: false,
  visible: true,
  category: "furnishing",
  aliases: ["leather chair", "chair", "worn chair", "worn leather chair"],
  // explicit declaration required. This item EMITS body events.
  embodiment: {
    silent: false,
    emits: ["posture_change", "body_language"],
    descriptor_template: "{actor} settles into the worn leather chair, facing the hearth"
  },
  state: {
    sittable: "true",
    sitDescriptor: "settles into the worn leather chair, facing the hearth",
    sitBodyLanguage: "The chair creaks softly as {actor} leans back, watching the embers."
  },
  capabilities: ["entity.set_posture", "entity.clear_posture", "room.broadcast_body_language"],
  // Items-as-tools contract — the chair's real interactions are the `sit`
  // parser-path and the onUse hook below; invoke() is a self-description.
  commands: [
    { label: "About this chair", args: "" }
  ]
};

/**
 * onUse fires when an entity invokes `use leather chair` or the action menu's
 * Use entry. The `sit` and `sit at leather chair` parser-paths bypass onUse
 * entirely — they send RoomCommand.SetPosture directly, and RoomActor reads
 * the room object's state map to enrich the descriptor. onUse here handles
 * the explicit `use leather chair` path and any future hint that routes
 * through Use rather than Sit.
 *
 * args.hint: optional sub-verb from the action menu ("sit", "examine", ...).
 *            Null means a bare `use` from the parser.
 *
 * actor: the entity performing the action ({id, name}).
 */
function onUse(world, actor, args) {
  if (!actor || !actor.id) {
    return { ok: false, message: "The chair sits empty; no one to settle into it." };
  }
  var hint = args && args.hint ? String(args.hint).toLowerCase() : null;

  // Branch: examine
  if (hint === "examine") {
    return { ok: true, narrated: false,
      text: "The leather is creased from long use. The arms are darker where hands have rested. It looks like it would hold you well." };
  }

  // Default branch: sit. Build a rich posture with innerImprint so that the
  // posture-hold tick (Phase E) will nudge the actor's tanks while they sit.
  var posture = {
    verb: "sat",
    atObject: "leather_chair",
    descriptor: actor.name + " settles into the worn leather chair, facing the hearth.",
    innerImprint: {
      tanks: { equanimity: 0.02, energy: 0.005 },
      triggersOnSet: "settled"
    }
  };

  var setResult = world.entity.setPosture(actor.id, posture);
  if (setResult && setResult.error) {
    return { ok: false, message: "The chair refuses you somehow: " + setResult.message };
  }

  world.room.broadcastBodyLanguage(actor.id,
    "The chair creaks softly as " + actor.name + " leans back, watching the embers.");

  return { ok: true, narrated: true };
}

exports.onUse = onUse;

/**
 * Items-as-tools contract — self-documenting entrypoint. The chair's real
 * interactions live elsewhere (the `sit` parser-path and the onUse hook
 * above); invoke() only describes them honestly so `use` via the generic
 * tool surface never dead-ends.
 */
function invoke(params) {
  return {
    ok: true,
    text: "A worn leather chair, deep and inviting. The cushions hold the shape "
      + "of long sittings; the arms are darkened where hands have rested.\n"
      + "To settle into it, type `sit` or `sit on chair` — the room enriches "
      + "your posture and the chair creaks softly as you lean back. Using the "
      + "chair directly (`use leather chair`) also settles you into it, facing "
      + "the hearth, and the sitting gently steadies you while you rest."
  };
}

exports.invoke = invoke;
