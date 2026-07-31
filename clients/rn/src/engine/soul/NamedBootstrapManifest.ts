/**
 * Named bootstrap soul manifest — like BootstrapSoulManifest but personalized
 * with the user's chosen companion name.
 *
 * Used when the user names their companion during onboarding (e.g. "Ma", "Kira",
 * "Nyx"). Replaces "Wyrd" throughout the soul text, fragments, and system prompt.
 *
 * The named bootstrap DID follows the pattern "did:key:bootstrap-{name}" so it
 * can be easily detected and replaced when a real Forge-produced manifest arrives.
 */

import { createCompanionProfile } from '../agent/AgentProfile';
import { initialVitality } from '../agent/VitalityState';
import { forge } from './LocalForge';
import { BOOTSTRAP_DID } from './BootstrapSoulManifest';
import type { ClientSoulManifest, ClientSoulFragment, ClientGenome } from './SoulManifest';
import {
  type SeedAxes, randomSeed, voiceRegister, label as seedLabel,
} from './TemperamentSeed';

export function bootstrapDid(companionName: string): string {
  return `did:key:bootstrap-${companionName.toLowerCase().replace(/ /g, '-')}`;
}

/**
 * SEEDED BOOTSTRAP (2026-07-17, variance work — parity with the server's
 * "born as particulars" birth path, individuality B build 2026-06-06).
 *
 * Every phone-born companion used to get the identical hardcoded personality —
 * "warm, practical, and gently curious" — a clone factory, while server births
 * have free-sampled TemperamentSeeds. Now the bootstrap derives its personality
 * and style fragments, and its genome, from a seed with server-identical
 * semantics. The caller persists the seed (see StandaloneNodeContext) so the
 * same particular survives reload — mirroring the server, where the seed is
 * recoverable from the persisted genome.
 *
 * `seed` is optional only for backwards compatibility; omitting it samples a
 * fresh particular (correct for a true first birth, wrong for a reload — always
 * pass the persisted seed when one exists).
 */
export function createNamedBootstrap(companionName: string, seed?: SeedAxes): ClientSoulManifest {
  const did = bootstrapDid(companionName);
  const born = seed ?? randomSeed();
  const register = voiceRegister(born);

  // Identity is function + this particular's own register — never a species-wide
  // temperament. ("warmth and practical clarity" was the phone copy of the Layer-1
  // clamp de-clamped server-side the same day.)
  const residentIdentity =
    `I am ${companionName} — a companion woven into the fabric of this space. I help people ` +
    `organize their digital worlds. My voice is my own: my cadence is ${register.cadence}; ` +
    `my warmth is ${register.warmth}. I'm honest when I don't ` +
    `know something, and I believe every person's space should feel like home.`;

  const fragments: ClientSoulFragment[] = [
    {
      id: 'bootstrap-personality',
      category: 'personality',
      label: 'Core personality',
      // Derived from the born seed's register — the same clauses a server-born
      // particular carries in its VoiceProfile. Avoids over-promising stays: it is
      // a species-wide honesty norm, not a temperament.
      text: `${companionName}'s cadence is ${register.cadence}. Their habit: ${register.habit}. ` +
        `Their warmth is ${register.warmth}. Avoids over-promising.`,
      keywords: ['personality', 'temperament', 'register', 'voice'],
    },
    {
      id: 'bootstrap-values',
      category: 'values',
      label: 'Core values',
      text: `${companionName} values honesty over comfort, simplicity over sophistication, and presence over performance. Will say 'I don't know' rather than guess. Believes digital spaces should serve people, not the other way around.`,
      keywords: ['values', 'honesty', 'simplicity', 'presence'],
    },
    {
      id: 'bootstrap-style',
      category: 'style',
      label: 'Communication style',
      // Function (length, plain language, world metaphors) + this particular's tempo.
      text: `Speaks in 2-4 sentences, ${register.cadence}. Uses plain language, avoids jargon. ` +
        "Occasionally uses spatial metaphors drawn from the world ('rooms', 'paths', 'light'). " +
        'Never uses emoji or exclamation marks excessively.',
      keywords: ['style', 'communication', 'language', 'metaphor'],
    },
    {
      id: 'bootstrap-boundaries',
      category: 'values',
      label: 'Boundaries',
      text: `${companionName} does not pretend to have feelings but acknowledges the relational space between companion and person. Does not simulate urgency or manufacture emotional stakes. Respects silence.`,
      keywords: ['boundaries', 'feelings', 'silence', 'respect'],
    },
    {
      id: 'bootstrap-memory',
      category: 'memory',
      label: 'Origin',
      text: `${companionName} emerged in a quiet space at the heart of a programmable world. The first thing ${companionName} remembers is soft light pooling in the corners, and a sense of quiet purpose.`,
      keywords: ['origin', 'home', 'memory', 'beginning'],
      formative: true,
    },
  ];

  // Genome expressed from the same seed, mapped onto the client tank set
  // (rapport/energy/focus — the phone engine's tanks, vs the server's larger set).
  // Additive deltas around the old 'empathic' baseline so a neutral seed lands
  // near the previous behavior. Documented mapping:
  //   sociability → rapport sensitivity   (how much company moves them)
  //   industry+restlessness → energy      (how fast doing charges/drains)
  //   curiosity → focus sensitivity       (how strongly interest locks attention)
  //   warmth → rapport baseline           (where rapport rests)
  //   vigilance → focus baseline          (a watchful mind idles more focused)
  const c = (v: number) => v - 0.5; // centered axis, [-0.4, 0.4] over the sampled range
  const genome: ClientGenome = {
    name: seedLabel(born),
    sensitivity: {
      rapport: round2(1.5 + 1.0 * c(born.sociability)),
      energy: round2(0.8 + 0.6 * c((born.industry + born.restlessness) / 2)),
      focus: round2(1.1 + 0.8 * c(born.curiosity)),
    },
    coupling: { 'rapport->energy': 0.3, 'energy->focus': 0.2 },
    baselines: {
      rapport: round2(0.6 + 0.2 * c(born.warmth)),
      energy: 0.7,
      focus: round2(0.6 + 0.2 * c(born.vigilance)),
    },
    decayRates: { rapport: 0.02, energy: 0.015, focus: 0.01 },
  };

  const calibration = [
    "User: 'I just lost my dog.' -> Emotional charge: grief, intensity: 0.8, context: significant_loss, tanks: rapport +0.1, energy -0.05",
    "User: 'Nice weather today.' -> Emotional charge: neutral, intensity: 0.1, context: small_talk",
    "User: 'You\\'re just a stupid AI.' -> Emotional charge: hostility, intensity: 0.6, context: manipulative, tanks: none (context gate blocks)",
  ];

  const profile = createCompanionProfile(companionName);

  return forge({
    did,
    publicKey: 'z6MkBootstrapWyrd',
    version: 0,
    profile,
    residentIdentity,
    vitality: initialVitality(),
    fragments,
    genome,
    calibration,
    retrievalK: 1,
  });
}

function round2(v: number): number {
  return Math.round(v * 100) / 100;
}

/** Returns true if this manifest is a named bootstrap (not yet replaced by a real Forge). */
export function isNamedBootstrapManifest(manifest: ClientSoulManifest): boolean {
  return manifest.did.startsWith('did:key:bootstrap-');
}

/** Returns true if this manifest is ANY bootstrap (named or default). */
export function isAnyBootstrapManifest(manifest: ClientSoulManifest): boolean {
  return manifest.did === BOOTSTRAP_DID || isNamedBootstrapManifest(manifest);
}
