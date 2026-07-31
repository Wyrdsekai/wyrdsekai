/**
 * TemperamentSeed — the phone-side port of `core/.../soul/TemperamentSeed.java`
 * plus the register derivation from `VoiceProfile.fromTemperament`.
 *
 * WHY THIS EXISTS (2026-07-17, variance work): every phone-born companion used to
 * get the same five hardcoded "warm, practical, curious" bootstrap fragments —
 * a clone factory, while server births have been free-sampled particulars since
 * the individuality "B build" (2026-06-06). This port makes a phone birth
 * indistinguishable in kind from a server birth: same six axes, same sampling
 * bounds, same viability gate, same preset anchors, and the register phrases
 * word-for-word identical to the server's.
 *
 * PARITY IS A CONTRACT, NOT AN ASPIRATION. `__tests__/engine/TemperamentSeed.test.ts`
 * asserts this module's output against `__tests__/fixtures/temperament-parity.json`,
 * which is GENERATED FROM THE JAVA CODE (see the fixture header). If you change the
 * Java derivation, regenerate the fixture and mirror the change here AND in the KMP
 * twin (`clients/kmp/.../engine/soul/TemperamentSeed.kt`) — never let the three
 * implementations drift.
 */

export interface SeedAxes {
  sociability: number;
  curiosity: number;
  vigilance: number;
  industry: number;
  restlessness: number;
  warmth: number;
}

export interface VoiceRegister {
  cadence: string;
  habit: string;
  warmth: string;
}

export interface NearestPreset {
  preset: string;
  distance: number;
}

const AXIS_ORDER: (keyof SeedAxes)[] = [
  'sociability', 'curiosity', 'vigilance', 'industry', 'restlessness', 'warmth',
];

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v));
}

export function makeSeed(axes: SeedAxes): SeedAxes {
  return {
    sociability: clamp01(axes.sociability),
    curiosity: clamp01(axes.curiosity),
    vigilance: clamp01(axes.vigilance),
    industry: clamp01(axes.industry),
    restlessness: clamp01(axes.restlessness),
    warmth: clamp01(axes.warmth),
  };
}

export const NEUTRAL: SeedAxes = makeSeed({
  sociability: 0.5, curiosity: 0.5, vigilance: 0.5,
  industry: 0.5, restlessness: 0.5, warmth: 0.5,
});

/** The six named presets — measurement anchors, NOT seeds or gates (Java parity). */
export const PRESETS: Record<string, SeedAxes> = {
  //                        soc   cur   vig   ind   res   wrm
  scholar:  makeSeed({ sociability: 0.30, curiosity: 0.85, vigilance: 0.45, industry: 0.70, restlessness: 0.45, warmth: 0.45 }),
  guardian: makeSeed({ sociability: 0.45, curiosity: 0.45, vigilance: 0.85, industry: 0.55, restlessness: 0.35, warmth: 0.55 }),
  artisan:  makeSeed({ sociability: 0.40, curiosity: 0.65, vigilance: 0.45, industry: 0.85, restlessness: 0.40, warmth: 0.50 }),
  diplomat: makeSeed({ sociability: 0.90, curiosity: 0.50, vigilance: 0.45, industry: 0.45, restlessness: 0.45, warmth: 0.75 }),
  explorer: makeSeed({ sociability: 0.35, curiosity: 0.75, vigilance: 0.40, industry: 0.35, restlessness: 0.90, warmth: 0.45 }),
  steward:  makeSeed({ sociability: 0.55, curiosity: 0.45, vigilance: 0.65, industry: 0.60, restlessness: 0.30, warmth: 0.80 }),
};

export function toArray(s: SeedAxes): number[] {
  return AXIS_ORDER.map((k) => s[k]);
}

/** Euclidean distance in axis space (Java parity: distanceTo). */
export function distanceTo(a: SeedAxes, b: SeedAxes): number {
  const av = toArray(a), bv = toArray(b);
  let sum = 0;
  for (let i = 0; i < av.length; i++) {
    const d = av[i] - bv[i];
    sum += d * d;
  }
  return Math.sqrt(sum);
}

/**
 * Coherence gate = viability, never conformity (Java parity: isViable).
 * Rejects only flat (all axes within 0.12 of neutral) and caricature
 * (4+ axes past 0.92/0.08). Distance-to-preset is NEVER considered.
 */
export function isViable(s: SeedAxes): boolean {
  let maxDev = 0;
  let extreme = 0;
  for (const a of toArray(s)) {
    const dev = Math.abs(a - 0.5);
    maxDev = Math.max(maxDev, dev);
    if (dev > 0.42) extreme++;
  }
  if (maxDev < 0.12) return false; // flat — no character
  if (extreme >= 4) return false;  // caricature — extreme on everything
  return true;
}

export function nearestPreset(s: SeedAxes): NearestPreset {
  let best = 'neutral';
  let bestD = distanceTo(s, NEUTRAL);
  for (const [name, p] of Object.entries(PRESETS)) {
    const d = distanceTo(s, p);
    if (d < bestD) { bestD = d; best = name; }
  }
  return { preset: best, distance: bestD };
}

/** Compact label, e.g. "scholar~0.41" — description, never a target (Java parity). */
export function label(s: SeedAxes): string {
  const n = nearestPreset(s);
  return `${n.preset}~${n.distance.toFixed(2)}`;
}

/** One axis draw in [0.10, 0.90] — shy of the pathological extremes (Java parity). */
function sampleAxis(rng: () => number): number {
  return 0.10 + rng() * 0.80;
}

function gaussian(rng: () => number): number {
  // Box-Muller — only used by the degenerate-RNG fallback path.
  let u = 0, v = 0;
  while (u === 0) u = rng();
  while (v === 0) v = rng();
  return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
}

/**
 * A freely sampled, viable particular (Java parity: random()).
 * 24 tries against the viability gate, then a jittered preset so birth never blocks.
 */
export function randomSeed(rng: () => number = Math.random): SeedAxes {
  for (let i = 0; i < 24; i++) {
    const s = makeSeed({
      sociability: sampleAxis(rng), curiosity: sampleAxis(rng), vigilance: sampleAxis(rng),
      industry: sampleAxis(rng), restlessness: sampleAxis(rng), warmth: sampleAxis(rng),
    });
    if (isViable(s)) return s;
  }
  const names = Object.keys(PRESETS);
  const base = PRESETS[names[Math.floor(rng() * names.length)]];
  const sigma = 0.06;
  return makeSeed({
    sociability: base.sociability + gaussian(rng) * sigma,
    curiosity: base.curiosity + gaussian(rng) * sigma,
    vigilance: base.vigilance + gaussian(rng) * sigma,
    industry: base.industry + gaussian(rng) * sigma,
    restlessness: base.restlessness + gaussian(rng) * sigma,
    warmth: base.warmth + gaussian(rng) * sigma,
  });
}

// ── Voice register — word-for-word parity with VoiceProfile.fromTemperament ──

/**
 * The spoken register co-derived from the seed. Cadence keys to the STRONGEST
 * qualifying axis (decorrelated 2026-07-17 — see the Java comment for the measured
 * rationale); habit keys to the most-deviant axis; warmth keys on soc/vig.
 * Phrases must remain byte-identical to the Java — the parity fixture enforces it.
 */
export function voiceRegister(s: SeedAxes): VoiceRegister {
  const { sociability: soc, curiosity: cur, vigilance: vig,
          industry: ind, restlessness: res, warmth: wrm } = s;

  // Cadence — strongest qualifying axis; list order is the tie-break order.
  const candidates: Array<{ name: string; value: number; phrase: string }> = [
    { name: 'restlessness', value: res, phrase: 'quick and vivid' },
    { name: 'sociability',  value: soc, phrase: 'warm and flowing' },
    { name: 'warmth',       value: wrm, phrase: 'calm and unhurried' },
    { name: 'curiosity',    value: cur, phrase: 'measured and exact; prefer precision to comfort' },
    { name: 'vigilance',    value: vig, phrase: 'plain and steady' },
    { name: 'industry',     value: ind, phrase: 'concrete and tactile' },
  ];
  let cadence = 'even and grounded';
  let bestVal = -1;
  for (const cand of candidates) {
    if (cand.value < 0.70) continue;
    if (cand.name === 'warmth' && res > 0.45) continue; // unhurried needs low restlessness
    if (cand.value > bestVal) { bestVal = cand.value; cadence = cand.phrase; }
  }

  // Habit — the characteristic move, from the most pronounced axis.
  const vals = toArray(s);
  let best = 0, bestDev = -1;
  for (let i = 0; i < vals.length; i++) {
    const dev = Math.abs(vals[i] - 0.5);
    if (dev > bestDev) { bestDev = dev; best = i; }
  }
  const habitByAxis: Record<string, string> = {
    curiosity:    'name the specific thing before reacting to it; cite what you actually know',
    vigilance:    "notice what's off and say it plainly; warn before you reassure",
    sociability:  'name what the other seems to feel; reach for common ground',
    industry:     'speak in materials, tools, and making; show rather than declare',
    restlessness: 'point outward, toward the next thing; resist settling too soon',
    warmth:       'tend the thread; keep what matters from slipping; organize gently',
  };
  const habit = habitByAxis[AXIS_ORDER[best]] ?? "say what's true plainly, without flourish";

  // Warmth — the relational temperature of the register.
  let warmth: string;
  if (soc >= 0.70)      warmth = 'high and openly relational';
  else if (vig >= 0.70) warmth = 'protective rather than effusive';
  else if (soc <= 0.45) warmth = 'earnest but reserved — depth over effusiveness';
  else                  warmth = 'steady and quietly caring';

  return { cadence, habit, warmth };
}

// ── Persistence helpers ──────────────────────────────────────────────────────

export function serializeSeed(s: SeedAxes): string {
  return JSON.stringify(toArray(s).map((v) => Number(v.toFixed(6))));
}

export function deserializeSeed(raw: string | null | undefined): SeedAxes | null {
  if (!raw) return null;
  try {
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr) || arr.length !== 6) return null;
    if (!arr.every((v) => typeof v === 'number' && Number.isFinite(v))) return null;
    return makeSeed({
      sociability: arr[0], curiosity: arr[1], vigilance: arr[2],
      industry: arr[3], restlessness: arr[4], warmth: arr[5],
    });
  } catch {
    return null;
  }
}
