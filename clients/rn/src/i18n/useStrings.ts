/**
 * Hook that returns the LocaleStrings for the current locale
 * from the preferences store.
 */

import { usePreferencesStore } from '../state/preferencesStore';
import { getStrings, LocaleStrings } from './strings';

export function useStrings(): LocaleStrings {
  const locale = usePreferencesStore((s) => s.locale);
  return getStrings(locale);
}
