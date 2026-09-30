/**
 * Cryptographic randomness. On the phone `crypto.getRandomValues` comes from
 * react-native-get-random-values (imported first in index.js), which reads the
 * platform CSPRNG. There is no Math.random fallback: a key made from a
 * predictable source is worse than no key, so this throws instead.
 */
export function randomBytes(n: number): Uint8Array {
  const c = (globalThis as { crypto?: { getRandomValues?: (a: Uint8Array) => Uint8Array } }).crypto;
  if (!c || typeof c.getRandomValues !== 'function') {
    throw new Error('This device offers no secure random source');
  }
  const out = new Uint8Array(n);
  c.getRandomValues(out);
  return out;
}

/** Random lowercase hex string of `bytes` bytes. */
export function randomHex(bytes: number): string {
  const b = randomBytes(bytes);
  let s = '';
  for (let i = 0; i < b.length; i++) s += b[i].toString(16).padStart(2, '0');
  return s;
}
