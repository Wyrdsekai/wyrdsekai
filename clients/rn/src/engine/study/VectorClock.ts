/**
 * Vector clock comparison for Study item sync.
 * TypeScript port of KMP's VectorClock.kt.
 */

export type ClockMap = Record<string, number>;

export type Relation = 'dominates' | 'dominated' | 'concurrent' | 'equal';

/**
 * Compare two vector clocks.
 * a dominates b if every slot in a >= b, and at least one is >.
 */
export function compare(a: ClockMap, b: ClockMap): Relation {
  const allKeys = new Set([...Object.keys(a), ...Object.keys(b)]);
  let aGreater = false;
  let bGreater = false;

  for (const key of allKeys) {
    const va = a[key] ?? 0;
    const vb = b[key] ?? 0;
    if (va > vb) aGreater = true;
    if (vb > va) bGreater = true;
  }

  if (aGreater && !bGreater) return 'dominates';
  if (bGreater && !aGreater) return 'dominated';
  if (!aGreater && !bGreater) return 'equal';
  return 'concurrent';
}

/** Merge two clocks, taking the max of each slot. */
export function merge(a: ClockMap, b: ClockMap): ClockMap {
  const result: ClockMap = { ...a };
  for (const [key, value] of Object.entries(b)) {
    result[key] = Math.max(result[key] ?? 0, value);
  }
  return result;
}

/** Increment a device's slot in the clock. */
export function tick(clock: ClockMap, deviceId: string): ClockMap {
  return { ...clock, [deviceId]: (clock[deviceId] ?? 0) + 1 };
}
