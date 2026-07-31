/**
 * Manages tier transitions based on device resources.
 * TypeScript port of KMP's TierManager.kt.
 *
 * Probes resources periodically and recommends tier changes.
 * Does NOT directly start/stop rooms — emits tier change events
 * for PhoneNode to act on.
 *
 */

import {
  type Tier,
  type TierConfig,
  type ResourceProbe,
  type ResourceSnapshot,
  configForTier,
  recommendTier,
  tierIndex,
} from './TierConfig';

export interface TierTransition {
  from: Tier;
  to: Tier;
  reason: string;
  snapshot: ResourceSnapshot;
}

export function isPromotion(t: TierTransition): boolean {
  return tierIndex(t.to) > tierIndex(t.from);
}

export function isDemotion(t: TierTransition): boolean {
  return tierIndex(t.to) < tierIndex(t.from);
}

export type TierTransitionListener = (transition: TierTransition) => void;

export class TierManager {
  private _currentTier: Tier = 'T0';
  private _config: TierConfig = configForTier('T0');
  private listeners: TierTransitionListener[] = [];
  private monitorTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private readonly probe: ResourceProbe,
    private readonly probeIntervalMs: number = 30_000,
  ) {}

  get currentTier(): Tier {
    return this._currentTier;
  }

  get config(): TierConfig {
    return this._config;
  }

  /** Subscribe to tier transitions. Returns unsubscribe function. */
  onTransition(listener: TierTransitionListener): () => void {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter(l => l !== listener);
    };
  }

  /** Probe once and set initial tier. */
  initialize(): void {
    const snapshot = this.probe.snapshot();
    const recommended = recommendTier(snapshot);
    this.applyTier(recommended, snapshot, 'initial');
  }

  /** Start periodic resource monitoring. */
  startMonitoring(): void {
    this.stopMonitoring();
    this.monitorTimer = setInterval(() => {
      const snapshot = this.probe.snapshot();
      const recommended = recommendTier(snapshot);
      if (recommended !== this._currentTier) {
        this.applyTier(recommended, snapshot, 'resource_change');
      }
    }, this.probeIntervalMs);
  }

  /** Stop periodic monitoring. */
  stopMonitoring(): void {
    if (this.monitorTimer != null) {
      clearInterval(this.monitorTimer);
      this.monitorTimer = null;
    }
  }

  /** Force a specific tier (for testing or manual override). */
  forceTier(tier: Tier): void {
    const snapshot = this.probe.snapshot();
    this.applyTier(tier, snapshot, 'forced');
  }

  private applyTier(newTier: Tier, snapshot: ResourceSnapshot, reason: string): void {
    const oldTier = this._currentTier;
    if (newTier === oldTier && reason !== 'initial') return;

    this._currentTier = newTier;
    this._config = configForTier(newTier);

    const transition: TierTransition = { from: oldTier, to: newTier, reason, snapshot };
    for (const listener of this.listeners) {
      listener(transition);
    }
  }
}
