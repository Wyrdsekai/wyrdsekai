/**
 * Client-side i18n string lookup ( / Plan section 104.8).
 * Hint labelKeys resolved to localized display text.
 */

const strings: Record<string, Record<string, string>> = {};

export function registerStrings(locale: string, entries: Record<string, string>): void {
  strings[locale] = { ...(strings[locale] ?? {}), ...entries };
}

export function resolveLabel(
  labelKey: string | null | undefined,
  fallback: string,
  locale: string,
): string {
  if (!labelKey) return fallback;
  return strings[locale]?.[labelKey] ?? fallback;
}

const RTL_LOCALES = new Set(['ar', 'he', 'fa', 'ur']);

/** Whether the locale uses RTL layout direction. */
export function isRtl(locale: string): boolean {
  return RTL_LOCALES.has(locale);
}
