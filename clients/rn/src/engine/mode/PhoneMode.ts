/**
 * Which of the five phone modes the app is in.
 *
 * The filename there is historical — there are
 * five, not three.
 *
 *   1  Remote terminal      home zone, NO local node — the zone renders everything
 *   2  Local + cloud        no home zone, cloud API key
 *   3  Local + on-device    no home zone, on-device model
 *   4  Local + home GPU     own local node, borrowing the household's 9B
 *   5  Local + cloud        own local node, cloud behind it
 *
 * Modes 1 and 4 are NOT alternatives — they are different products. Mode 1 is a
 * window onto the house; mode 4 is the phone as its own small place that
 * borrows the house's muscle. Both have a home zone configured, which is why
 * "has a home zone" can never select a mode on its own — assuming it could is
 * what produced the relay-drop defect in §0b.
 */
export type PhoneMode = 1 | 2 | 3 | 4 | 5;

/** Where heavy work goes when the phone runs its own node AND has a home zone. */
export type Backing = 'home' | 'cloud';

/**
 * The inputs a mode decision actually depends on.
 *
 * Two axes, deliberately: *does the phone want its own node?* and *where does
 * inference come from?* Reading only one of them is the §0b bug.
 */
export interface ModeInputs {
  /**
   * EXPERIMENTAL, off by default: run the companion's model on this device.
   *
   * Phone hardware is not there yet, and this is a measured claim rather than
   * a cautious guess:
   *
   *   - A 4B at 4-bit needs ~3GB resident. iOS jetsam kills an app at roughly
   *     half of total RAM, so a benchmark on a 12GB iPhone 17 Pro could not
   *     load a 4B at all — the largest that ran was 1.7B.
   *   - Decode on a current Android flagship is ~10 tok/s for a 4B, and 3-6
   *     on a mid-range device. Reading speed is ~7-10 tok/s, so the companion
   *     is at best keeping pace and usually behind.
   *   - Sustained generation throttles hard: prime-core frequency roughly
   *     halves as the device heats.
   *
   * So the DEFAULT is no model on the phone — mode 1 or 2, where the thinking
   * happens somewhere that can do it well.
   *
   * NOTE the name: this asks whether running a model here is VIABLE, not
   * whether a flag is set. Today the only thing that can answer it is the user
   * opting in (a loaded tablet, a developer, someone who wants it anyway), and
   * the surface offering that must say EXPERIMENTAL. When hardware catches up,
   * a measurement answers it instead — first-run tokens/s against a floor — and
   * NOTHING in this tree changes. That is the point of putting the policy in
   * the caller and only the consequence here.
   */
  onDeviceModelViable: boolean;
  /**
   * Does the USER want their own node — their own world and Study — rather
   * than a window onto the house?
   *
   * Capability and preference are separate questions and were conflated in the
   * first cut of this file: `runsLocalNode` read the preference alone, so a
   * phone too weak to run a companion was still handed one, and rendered its
   * voice locally and slowly with the household GPU sitting idle.
   */
  wantsOwnNode: boolean;
  /** True when a home zone is configured (a relay leg exists). */
  hasHomeZone: boolean;
  /** True when a cloud API key is configured. */
  hasCloudKey: boolean;
  /** True when an on-device model is downloaded and loadable. */
  hasOnDeviceModel: boolean;
  /**
   * User's choice of backing when both are possible. Only consulted when the
   * phone runs its own node AND has a home zone — that is the 4-vs-5 fork, and
   * it is the user's call, not something to infer from hardware.
   */
  preferredBacking: Backing;
}

/**
 * A mode could not be determined. Kept as a value rather than a thrown error so
 * callers can render an honest "finish setup" state instead of crashing.
 */
export type ModeUndecided = { mode: null; reason: string };

export type ModeDecision = { mode: PhoneMode; reason: string } | ModeUndecided;

/**
 * Decide the mode from the two axes.
 *
 * Pure and total: every input combination returns a decision, and the ones that
 * cannot be a mode say why. The reason strings are user-facing-ish — they are
 * what a "why am I in this mode?" surface would show.
 *
 * The cases are mirrored in clients/parity/parity.json → phoneMode so the KMP
 * twin is held to the same table.
 */
export function decideMode(i: ModeInputs): ModeDecision {
  // The safe path first. With no model on the phone there is nothing to be
  // slow, so the only question is where the thinking happens: the household
  // (mode 1) or a cloud API (mode 2).
  if (!i.onDeviceModelViable) {
    // Own node with a cloud API needs no local model — the phone keeps its own
    // world and Study, and the API does the thinking.
    if (i.wantsOwnNode && i.hasCloudKey) {
      return { mode: 2, reason: 'Your own companion on this phone, thinking via your cloud API.' };
    }
    if (i.hasHomeZone) {
      return { mode: 1, reason: 'Running as a terminal onto your home zone — the household does the thinking.' };
    }
    if (i.hasCloudKey) {
      return { mode: 2, reason: 'Your own companion on this phone, thinking via your cloud API.' };
    }
    return {
      mode: null,
      reason:
        'This phone needs a home zone or a cloud API key. Running the model on the phone itself is possible but experimental — most phones are not fast enough yet.',
    };
  }

  // EXPERIMENTAL from here: the user has accepted a model on the device.
  const runsLocalNode = i.wantsOwnNode;

  // A terminal with nothing to be a terminal onto is not a mode. Say so rather
  // than silently booting a local node the user did not ask for — a silent
  // fallback here is how a relay-login ended up in the local mini-zone and
  // downloaded a 2.5GB model it never needed.
  if (!runsLocalNode && !i.hasHomeZone) {
    return { mode: null, reason: 'No home zone to connect to, and no local node requested.' };
  }

  // Mode 1 — the phone is a window onto the house. No local node, no model.
  if (!runsLocalNode) {
    return { mode: 1, reason: 'Home zone configured, running as a terminal onto it.' };
  }

  // From here the phone runs its own node. The question is only what stands
  // behind it for the heavy work.
  if (i.hasHomeZone) {
    if (i.preferredBacking === 'home') {
      return { mode: 4, reason: 'Own node, borrowing the household GPU for heavy work.' };
    }
    return { mode: 5, reason: 'Own node, using a cloud API for heavy work.' };
  }

  // No home zone: cloud if there is a key, else the device alone.
  if (i.hasCloudKey) {
    return { mode: 2, reason: 'Own node with a cloud API — no home zone configured.' };
  }
  if (i.hasOnDeviceModel) {
    return { mode: 3, reason: 'Own node, on-device model only.' };
  }
  return {
    mode: null,
    reason:
      'Own node requested but nothing can answer: no home zone, no API key, and no on-device model downloaded yet.',
  };
}

/** True when this mode boots PhoneNode. Mode 1 is the only one that does not. */
export function runsLocalNode(mode: PhoneMode): boolean {
  return mode !== 1;
}


/**
 * True when this mode should download an on-device model.
 *
 * Only the experimental modes. Modes 1 and 2 are the defaults precisely
 * BECAUSE they need nothing on the device — a terminal has the household, and
 * a cloud-API phone has the API. Downloading gigabytes for either would be
 * spending a user's storage and battery on something that never gets asked a
 * question, which is what the original complaint was about.
 */
export function wantsOnDeviceModel(mode: PhoneMode): boolean {
  return mode === 3 || mode === 4 || mode === 5;
}
