import {
  acceptsFromSync,
  isRealOwner,
  requireOwner,
  UnresolvableOwnerError,
} from '../../src/engine/study/StudyOwner';

/**
 * Mobile is the platform that writes JOURNAL entries — the encrypted,
 * irreplaceable kind — and it was defaulting its owner to 'local-user'.
 * These pin the refusal.
 */
describe('StudyOwner', () => {
  it('rejects the local-user placeholder that shipped as the default', () => {
    expect(isRealOwner('local-user')).toBe(false);
    expect(() => requireOwner('local-user')).toThrow(UnresolvableOwnerError);
  });

  it('rejects the other placeholder shapes', () => {
    for (const bad of ['unknown', 'anonymous', 'default', 'user', '', '   ']) {
      expect(isRealOwner(bad)).toBe(false);
      expect(() => requireOwner(bad)).toThrow();
    }
  });

  it('rejects missing owners rather than inventing one', () => {
    expect(isRealOwner(null)).toBe(false);
    expect(isRealOwner(undefined)).toBe(false);
    expect(() => requireOwner(null)).toThrow(UnresolvableOwnerError);
    expect(() => requireOwner(undefined)).toThrow(UnresolvableOwnerError);
  });

  it('is case-insensitive about placeholders', () => {
    expect(isRealOwner('LOCAL-USER')).toBe(false);
    expect(isRealOwner('Local-User')).toBe(false);
  });

  it('accepts a real person DID', () => {
    const did = 'did:key:z6MkfRi1xsvEkV9Kn53a7wt7tQRuqA5rG8jNBWeN38A7x9F9';
    expect(isRealOwner(did)).toBe(true);
    expect(requireOwner(did)).toBe(did);
  });

  it('trims but does not otherwise alter the owner', () => {
    expect(requireOwner('  did:key:zAbc  ')).toBe('did:key:zAbc');
  });

  it('quarantines placeholder-owned items arriving over sync', () => {
    // Without this, one un-upgraded device pushes its placeholder items back
    // after the household has migrated and the split silently regrows.
    expect(acceptsFromSync('local-user')).toBe(false);
    expect(acceptsFromSync(null)).toBe(false);
    expect(acceptsFromSync('did:key:zReal')).toBe(true);
  });

  it('explains the failure rather than just failing', () => {
    try {
      requireOwner('local-user');
      throw new Error('should have thrown');
    } catch (e) {
      expect((e as Error).message).toContain('not a person');
      expect((e as Error).message).toContain('several different strings');
    }
  });
});
