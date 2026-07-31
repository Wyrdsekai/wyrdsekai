/**
 * Platform-aware ResourceProbe that queries real device resources.
 *
 * Uses only APIs available in bare React Native + web:
 * - Web: navigator.deviceMemory, navigator.getBattery() (Battery Status API)
 * - Native (Android/iOS): reasonable defaults with comments for future
 *   react-native-device-info integration
 *
 * Falls back to sensible defaults when APIs are unavailable.
 *
 */

import type { ResourceProbe, ResourceSnapshot, ThermalState } from './TierConfig';

/** Whether we're running in a web environment. */
function isWeb(): boolean {
  return typeof navigator !== 'undefined' && typeof window !== 'undefined';
}

/**
 * Cached battery info from the Battery Status API (web only).
 * Updated asynchronously — snapshot() reads the latest cached value.
 */
interface BatteryInfo {
  level: number;      // 0.0 - 1.0
  charging: boolean;
}

/** Minimal interface for navigator.deviceMemory (Chrome/Edge). */
interface NavigatorWithDeviceMemory extends Navigator {
  deviceMemory?: number;
}

/** Minimal interface for the Battery Status API (navigator.getBattery()). */
interface NavigatorWithBattery extends Navigator {
  getBattery?: () => Promise<BatteryManager>;
}

/** Minimal BatteryManager shape from the Battery Status API. */
interface BatteryManager {
  level: number;
  charging: boolean;
  addEventListener?: (event: string, listener: () => void) => void;
}

/** Minimal interface for navigator.connection (Network Information API). */
interface NavigatorWithConnection extends Navigator {
  connection?: { type?: string };
}

/**
 * PlatformResourceProbe queries real device resources using only
 * standard RN/web APIs. No external dependencies required.
 *
 * For richer native data (thermal state, precise free memory),
 * integrate react-native-device-info in the future and pass readings
 * into updateNativeResources().
 */
export class PlatformResourceProbe implements ResourceProbe {
  private batteryInfo: BatteryInfo = { level: 0.8, charging: false };
  private batteryInitialized = false;

  // Native resource overrides — set externally when richer APIs are available
  private nativeMemoryMb: number | null = null;
  private nativeTotalMemoryMb: number | null = null;
  private nativeThermalState: ThermalState | null = null;
  private nativeHasWifi: boolean | null = null;

  // Inference activity tracking — used as CPU heuristic
  private _inferenceActive = false;

  constructor() {
    this.initBattery();
  }

  /**
   * Update native resource readings from external sources.
   * Call this from a react-native-device-info poller or similar.
   */
  updateNativeResources(resources: {
    availableMemoryMb?: number;
    totalMemoryMb?: number;
    thermalState?: ThermalState;
    hasWifi?: boolean;
  }): void {
    if (resources.availableMemoryMb !== undefined) this.nativeMemoryMb = resources.availableMemoryMb;
    if (resources.totalMemoryMb !== undefined) this.nativeTotalMemoryMb = resources.totalMemoryMb;
    if (resources.thermalState !== undefined) this.nativeThermalState = resources.thermalState;
    if (resources.hasWifi !== undefined) this.nativeHasWifi = resources.hasWifi;
  }

  /** Mark whether inference is currently running (affects CPU heuristic). */
  setInferenceActive(active: boolean): void {
    this._inferenceActive = active;
  }

  snapshot(): ResourceSnapshot {
    return {
      availableMemoryMb: this.getAvailableMemoryMb(),
      totalMemoryMb: this.getTotalMemoryMb(),
      batteryPercent: this.getBatteryPercent(),
      isCharging: this.getIsCharging(),
      thermalState: this.getThermalState(),
      hasWifi: this.getHasWifi(),
    };
  }

  // ── Memory ──────────────────────────────────────────────────────────

  private getAvailableMemoryMb(): number {
    // Native override takes priority
    if (this.nativeMemoryMb !== null) return this.nativeMemoryMb;

    // Web: navigator.deviceMemory gives total RAM in GiB (Chrome/Edge only)
    // We estimate 60% available as a reasonable heuristic
    if (isWeb()) {
      const nav = navigator as NavigatorWithDeviceMemory;
      if (typeof nav.deviceMemory === 'number') {
        return Math.round(nav.deviceMemory * 1024 * 0.6);
      }
    }

    // Default: assume a mid-range phone with 2GB available
    return 2000;
  }

  private getTotalMemoryMb(): number {
    if (this.nativeTotalMemoryMb !== null) return this.nativeTotalMemoryMb;

    if (isWeb()) {
      const nav = navigator as NavigatorWithDeviceMemory;
      if (typeof nav.deviceMemory === 'number') {
        return Math.round(nav.deviceMemory * 1024);
      }
    }

    return 4000;
  }

  // ── Battery ─────────────────────────────────────────────────────────

  private getBatteryPercent(): number {
    return Math.round(this.batteryInfo.level * 100);
  }

  private getIsCharging(): boolean {
    return this.batteryInfo.charging;
  }

  /**
   * Initialize Battery Status API (web only).
   * On native, battery info comes from updateNativeResources().
   */
  private initBattery(): void {
    if (!isWeb()) return;

    const nav = navigator as NavigatorWithBattery;
    if (typeof nav.getBattery !== 'function') return;

    nav.getBattery().then((battery: BatteryManager) => {
      this.batteryInfo = {
        level: battery.level ?? 0.8,
        charging: battery.charging ?? false,
      };
      this.batteryInitialized = true;

      // Listen for changes
      battery.addEventListener?.('levelchange', () => {
        this.batteryInfo = { ...this.batteryInfo, level: battery.level };
      });
      battery.addEventListener?.('chargingchange', () => {
        this.batteryInfo = { ...this.batteryInfo, charging: battery.charging };
      });
    }).catch(() => {
      // Battery API not available — keep defaults
    });
  }

  /**
   * Update battery from native source.
   * Call this from a react-native-device-info poller.
   */
  updateBattery(level: number, charging: boolean): void {
    this.batteryInfo = { level, charging };
    this.batteryInitialized = true;
  }

  // ── Thermal ─────────────────────────────────────────────────────────

  private getThermalState(): ThermalState {
    if (this.nativeThermalState !== null) return this.nativeThermalState;

    // Heuristic: low battery + not charging + inference = likely warm
    if (!this.batteryInfo.charging && this.batteryInfo.level < 0.15 && this._inferenceActive) {
      return 'SERIOUS';
    }
    if (!this.batteryInfo.charging && this.batteryInfo.level < 0.20 && this._inferenceActive) {
      return 'FAIR';
    }

    return 'NOMINAL';
  }

  // ── Network ─────────────────────────────────────────────────────────

  private getHasWifi(): boolean {
    if (this.nativeHasWifi !== null) return this.nativeHasWifi;

    // Web: use Network Information API if available
    if (isWeb()) {
      const nav = navigator as NavigatorWithConnection;
      const connection = nav.connection;
      if (connection && typeof connection.type === 'string') {
        return connection.type === 'wifi' || connection.type === 'ethernet';
      }
      // If no Network Information API, assume online = wifi for web
      return typeof navigator.onLine === 'boolean' ? navigator.onLine : true;
    }

    // Native default: assume wifi (conservative — promotes higher tier)
    return true;
  }
}
