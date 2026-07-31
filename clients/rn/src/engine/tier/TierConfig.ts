/**
 * Resource tiers for the phone node.
 * TypeScript port of KMP's TierConfig.kt.
 *
 * T0 is the floor — the companion always exists.
 * Higher tiers unlock more rooms, local inference, and Between connectivity.
 *
 */

export type Tier = 'T0' | 'T1' | 'T2' | 'T3';

export const TIER_ORDER: readonly Tier[] = ['T0', 'T1', 'T2', 'T3'] as const;

export function tierIndex(tier: Tier): number {
  return TIER_ORDER.indexOf(tier);
}

export type InferenceMode = 'LOCAL' | 'REMOTE' | 'HYBRID';

export type ThermalState = 'NOMINAL' | 'FAIR' | 'SERIOUS' | 'CRITICAL';

export interface TierConfig {
  maxRooms: number;
  maxConcurrentInference: number;
  maxContextTokens: number;
  scriptTimeoutMs: number;
  betweenEnabled: boolean;
  inferenceMode: InferenceMode;
}

const TIER_CONFIGS: Record<Tier, TierConfig> = {
  T0: {
    maxRooms: 0,
    maxConcurrentInference: 1,
    maxContextTokens: 2048,
    scriptTimeoutMs: 0,
    betweenEnabled: false,
    inferenceMode: 'REMOTE',
  },
  T1: {
    maxRooms: 1,
    maxConcurrentInference: 1,
    maxContextTokens: 4096,
    scriptTimeoutMs: 500,
    betweenEnabled: false,
    inferenceMode: 'LOCAL',
  },
  T2: {
    maxRooms: 4,
    maxConcurrentInference: 1,
    maxContextTokens: 8192,
    scriptTimeoutMs: 1000,
    betweenEnabled: true,
    inferenceMode: 'LOCAL',
  },
  T3: {
    maxRooms: 16,
    maxConcurrentInference: 2,
    maxContextTokens: 16384,
    scriptTimeoutMs: 2000,
    betweenEnabled: true,
    inferenceMode: 'LOCAL',
  },
};

export function configForTier(tier: Tier): TierConfig {
  return TIER_CONFIGS[tier];
}

/** Snapshot of device resource state at a point in time. */
export interface ResourceSnapshot {
  availableMemoryMb: number;
  totalMemoryMb: number;
  batteryPercent: number;
  isCharging: boolean;
  thermalState: ThermalState;
  hasWifi: boolean;
}

/** Recommend a tier based on current resources. */
export function recommendTier(snap: ResourceSnapshot): Tier {
  // Thermal throttling always forces T0
  if (snap.thermalState === 'CRITICAL') return 'T0';

  // Battery critical forces T0
  if (snap.batteryPercent < 10 && !snap.isCharging) return 'T0';

  // T3 requires charger + WiFi + comfortable resources
  if (snap.isCharging && snap.hasWifi && snap.availableMemoryMb >= 3000 && snap.thermalState === 'NOMINAL') {
    return 'T3';
  }

  // T2 requires WiFi + good resources
  if (snap.hasWifi && snap.availableMemoryMb >= 2000 && snap.batteryPercent >= 30) {
    return 'T2';
  }

  // T1 is default — one room, local inference
  if (snap.availableMemoryMb >= 1200 && (snap.batteryPercent >= 20 || snap.isCharging)) {
    return 'T1';
  }

  return 'T0';
}

/** Platform abstraction for querying device resources. */
export interface ResourceProbe {
  snapshot(): ResourceSnapshot;
}

/** Default probe for testing — reports comfortable T1 resources. */
export class DefaultResourceProbe implements ResourceProbe {
  snapshot(): ResourceSnapshot {
    return {
      availableMemoryMb: 2000,
      totalMemoryMb: 4000,
      batteryPercent: 80,
      isCharging: false,
      thermalState: 'NOMINAL',
      hasWifi: true,
    };
  }
}

/**
 * A sentence for the user about why the device just changed what it can do.
 *
 * Returns null when there is nothing worth saying. Promotions are silent —
 * getting *more* room needs no announcement — and so is the initial settle.
 *
 * Demotions are not silent, because they are visible: rooms close and the
 * player is moved home. Before this, that happened with no explanation at all,
 * which reads as the app losing your world rather than the device protecting
 * itself.
 */
export function describeTierChange(
  from: Tier,
  to: Tier,
  snap: ResourceSnapshot,
): string | null {
  if (tierIndex(to) >= tierIndex(from)) return null;

  const cause =
    snap.thermalState === 'CRITICAL' ? 'This device is too hot'
    : snap.thermalState === 'SERIOUS' ? 'This device is running hot'
    : snap.batteryPercent < 10 && !snap.isCharging ? 'The battery is nearly empty'
    : snap.batteryPercent < 20 && !snap.isCharging ? 'The battery is low'
    : !snap.hasWifi ? 'This device is off wifi'
    : 'This device is short on memory';

  // T0 is the floor: no rooms, and inference moves off the device entirely.
  const effect =
    to === 'T0'
      ? 'so your companion is resting and borrowing elsewhere to think'
      : 'so some rooms are closed for now';

  return `${cause}, ${effect}. It will pick back up on its own.`;
}
