/**
 * Owner validation for locally-stored Study content.
 *
 * Mirrors `StudyOwnerGuard` on the server. The mobile client is the platform
 * that writes JOURNAL entries — the encrypted, irreplaceable kind — and it was
 * defaulting its owner to the literal string `'local-user'` whenever the Study
 * screen opened without a `userDid` route param. Every such entry belongs to
 * nobody, and study-sync would carry them into the household index.
 *
 * The rule, same as the server: **no resolvable identity means no write**, and
 * say so rather than substituting a placeholder.
 *
 */

/** Strings that are placeholders rather than people. */
const PLACEHOLDERS = new Set([
  'local-user',
  'unknown',
  'anonymous',
  'default',
  'user',
  '',
]);

export class UnresolvableOwnerError extends Error {
  constructor(owner: string | null | undefined) {
    super(
      `Refusing to write Study content owned by ${JSON.stringify(owner)} — ` +
        'this is not a person. Writing it anyway is how one human ends up ' +
        'owning content under several different strings.',
    );
    this.name = 'UnresolvableOwnerError';
  }
}

/** True when this string could plausibly identify a person. */
export function isRealOwner(owner: string | null | undefined): boolean {
  if (owner === null || owner === undefined) return false;
  const trimmed = owner.trim();
  if (trimmed.length === 0) return false;
  return !PLACEHOLDERS.has(trimmed.toLowerCase());
}

/**
 * Validate an owner for a WRITE. Throws rather than silently substituting.
 *
 * @param owner whatever the caller believes identifies the owner
 * @returns the trimmed owner
 */
export function requireOwner(owner: string | null | undefined): string {
  if (!isRealOwner(owner)) throw new UnresolvableOwnerError(owner);
  return (owner as string).trim();
}

/**
 * Should an inbound synced item be accepted into the local store?
 *
 * Quarantining here matters as much as on the server: a device that has not
 * upgraded can otherwise push placeholder-owned items around the household and
 * silently re-create the split after everything else has been migrated.
 */
export function acceptsFromSync(owner: string | null | undefined): boolean {
  return isRealOwner(owner);
}
