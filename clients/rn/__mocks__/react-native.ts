/**
 * Minimal `react-native` stand-in for the node-env jest suite.
 *
 * Two suites (`engine/between/native-nats-client`, `engine/standalone-nats`)
 * silently FAILED TO LOAD — ts-jest runs in `testEnvironment: node` and does not
 * transform `node_modules/react-native`, whose entry point is Flow/ESM
 * ("Cannot use import statement outside a module"). So the tests covering the
 * relay/NATS transport never ran at all. Mapping `react-native` here lets them
 * execute without pulling in the RN preset (which would need Babel + a
 * jsdom-ish env for the whole suite).
 *
 * Only what the transport modules touch is provided. `Platform.OS` defaults to
 * 'android', which selects the ws/OkHttp path — the native RelaySocket branch is
 * iOS-only, and a test that wants it can override `Platform.OS` or inject into
 * `NativeModules`. (2026-07-25)
 */

export const Platform: { OS: string; select: <T>(spec: Record<string, T>) => T | undefined } = {
  OS: 'android',
  select: (spec) => spec[Platform.OS] ?? (spec as Record<string, unknown>).default as never,
};

/** Mutable so a test can install a fake native module before importing. */
export const NativeModules: Record<string, unknown> = {};

type Listener = (event: unknown) => void;

/** Event-emitter shape RelaySocket uses: addListener → { remove() }. */
export class NativeEventEmitter {
  private readonly listeners = new Map<string, Set<Listener>>();

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  constructor(_nativeModule?: unknown) {}

  addListener(event: string, listener: Listener): { remove: () => void } {
    let set = this.listeners.get(event);
    if (!set) {
      set = new Set();
      this.listeners.set(event, set);
    }
    set.add(listener);
    return { remove: () => { set!.delete(listener); } };
  }

  removeAllListeners(event?: string): void {
    if (event) this.listeners.delete(event);
    else this.listeners.clear();
  }

  /** Test hook: deliver an event to registered listeners. */
  emit(event: string, payload: unknown): void {
    for (const l of this.listeners.get(event) ?? []) l(payload);
  }
}

export const DeviceEventEmitter = new NativeEventEmitter();
