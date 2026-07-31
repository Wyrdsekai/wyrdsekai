/**
 * Resolving the phone's mode from the app's actual state, and applying it.
 *
 * `decideMode` in PhoneMode.ts is pure and knows nothing about where facts
 * live. This module is the seam: it gathers the five inputs from storage and
 * stores, and it applies the resulting decision to the inference router.
 *
 * Keeping the two apart is deliberate — the decision table is shared with the
 * KMP client via clients/parity/parity.json, and it can only be shared if it
 * has no dependency on React Native storage.
 *
 */
import { secureStorage } from '../../state/secureStorage';
import { useAppModeStore } from '../../state/appModeStore';
import { decideMode } from './PhoneMode';
import type { Backing, ModeDecision, ModeInputs, PhoneMode } from './PhoneMode';
import type { InferenceRouter } from '../../inference/InferenceRouter';

/**
 * Read the mode inputs from persisted state.
 *
 * `hasOnDeviceModel` is passed in rather than probed: at boot the model file
 * may exist while the llama context is not yet loaded, and those are different
 * questions. The caller knows which one it means.
 */
export async function collectModeInputs(opts: {
  hasOnDeviceModel: boolean;
}): Promise<ModeInputs> {
  const store = useAppModeStore.getState();

  const [zoneId, relayUrl, apiKey, apiProvider] = await Promise.all([
    secureStorage.getItem('@wyrd_zone_id'),
    secureStorage.getItem('@wyrd_relay_url'),
    secureStorage.getItem('@wyrd_api_key'),
    secureStorage.getItem('@wyrd_api_provider'),
  ]);

  return modeInputsFrom({
    onDeviceModelOptIn: store.onDeviceModelOptIn,
    mode: store.mode,
    zoneId,
    relayUrl: relayUrl ?? store.relayUrl,
    householdId: store.householdId,
    apiKey,
    apiProvider,
    preferredBacking: store.preferredBacking,
    hasOnDeviceModel: opts.hasOnDeviceModel,
  });
}

/** Raw persisted values, before they mean anything. */
export interface RawModeState {
  /**
   * The user's EXPERIMENTAL opt-in to running a model on this device.
   *
   * Today this is the only signal feeding viability (see onDeviceModelViable).
   * It is a separate field from the tree's input on purpose: when a measurement
   * can answer the question, it joins here and the tree is untouched.
   */
  onDeviceModelOptIn?: boolean;
  mode: string | null;
  zoneId: string | null;
  relayUrl: string | null;
  householdId: string | null;
  apiKey: string | null;
  apiProvider: string | null;
  preferredBacking: string | null;
  hasOnDeviceModel: boolean;
}

/**
 * The mapping itself, over raw persisted values.
 *
 * Split out from {@link collectModeInputs} so both clients can be held to one
 * table: the cases live in clients/parity/parity.json → modeInputs, and the
 * KMP twin is `modeInputsFrom` in engine/mode/CurrentMode.kt.
 *
 * This is the drift-prone half. The decision table is easy to keep in step
 * because it is small and abstract; what counts as "has a home zone" is where
 * two clients quietly diverge.
 */
export function modeInputsFrom(raw: RawModeState): ModeInputs {
  return {
    onDeviceModelViable: onDeviceModelViable({ optIn: raw.onDeviceModelOptIn ?? false }),
    // 'local' means the user asked for their own node. 'remote' means
    // terminal. 'unset'/absent is first-run.
    wantsOwnNode: raw.mode === 'local',
    // Any of these means a household is known or reachable. Empty strings do
    // NOT count — cleared preferences write "" on some platforms, and treating
    // that as a configured zone strands the phone in a mode with nothing
    // behind it.
    hasHomeZone: Boolean(raw.zoneId || raw.relayUrl || raw.householdId),
    // A key alone is not enough — without a provider there is no URL to send
    // it to, and the request would go out unaddressed.
    hasCloudKey: Boolean(raw.apiKey && raw.apiProvider),
    hasOnDeviceModel: raw.hasOnDeviceModel,
    // Anything unrecognised (or absent) means the user never chose, and 'home'
    // is the right default for someone who paired with a household.
    preferredBacking: raw.preferredBacking === 'cloud' ? 'cloud' : 'home',
  };
}

/**
 * Is running the companion's model on THIS device viable?
 *
 * The single place that policy lives. It is deliberately a function taking a
 * bag of signals rather than a bare boolean read, because the set of signals is
 * expected to grow and the tree must not care:
 *
 *   - today   → the user's explicit EXPERIMENTAL opt-in, and nothing else.
 *              Measured phone throughput does not clear a usable bar (see the
 *              evidence in PhoneMode.ts), so no device is auto-promoted.
 *   - later   → add `measuredTokensPerSecond` and return true when it beats a
 *              floor. A loaded tablet then qualifies on its own merits, the
 *              opt-in becomes a manual override rather than the only door, and
 *              decideMode does not change by one line.
 *
 * Keeping this OUT of decideMode is what makes that future edit a one-function
 * change instead of a re-litigation of the mode tree.
 */
export function onDeviceModelViable(signals: {
  optIn: boolean;
  /** Reserved: measured decode throughput, once we measure it. */
  measuredTokensPerSecond?: number;
}): boolean {
  return signals.optIn;
}

/**
 * Throughput at which an on-device model stops being an annoyance.
 *
 * People read at roughly 7-10 tokens/s, so below this the companion is visibly
 * behind the reader. Unused until something measures throughput; named now so
 * the future check has a number to point at rather than inventing one.
 */
export const USABLE_TOKENS_PER_SECOND = 10;

/** Gather inputs and decide. */
export async function resolvePhoneMode(opts: {
  hasOnDeviceModel: boolean;
}): Promise<ModeDecision> {
  return decideMode(await collectModeInputs(opts));
}

/**
 * Apply a mode to the inference router.
 *
 * The routing chains in InferenceRouter already express "voice prefers the
 * device, drive borrows first", and that is right for every mode. What the
 * mode decides here is narrower and more concrete: who owns the single remote
 * slot when both a household GPU and a cloud API are configured.
 *
 * Mode 5 pins it, because the user chose cloud and walking into the house must
 * not silently change that. Every other mode releases the pin so LAN discovery
 * can install the direct household endpoint, which is the better one when it
 * is reachable.
 */
export function applyModeToRouter(mode: PhoneMode | null, router: InferenceRouter): void {
  router.pinRemote(mode === 5);
}

/** Human-readable name for a mode, for settings and diagnostics. */
export function modeLabel(mode: PhoneMode | null): string {
  switch (mode) {
    case 1: return 'Remote terminal';
    case 2: return 'On this phone, cloud API';
    case 3: return 'On this phone, on-device model';
    case 4: return 'On this phone, home zone behind it';
    case 5: return 'On this phone, cloud API behind it';
    default: return 'Setup incomplete';
  }
}

/**
 * Which backings the user can actually choose between right now.
 *
 * Offering 'cloud' with no API key configured would be offering a mode that
 * cannot answer, so the settings surface asks this first.
 */
export function availableBackings(i: ModeInputs): Backing[] {
  const out: Backing[] = [];
  if (i.hasHomeZone) out.push('home');
  if (i.hasCloudKey) out.push('cloud');
  return out;
}
