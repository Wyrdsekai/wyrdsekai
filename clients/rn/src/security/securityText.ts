/**
 * The security refusals in the person's language, for modules that are not
 * React components (the transport and trust layers).
 */
import { getStrings, type SecurityStrings } from '../i18n/strings';
import { usePreferencesStore } from '../state/preferencesStore';

export function securityText(): SecurityStrings {
  let locale = 'en';
  try {
    locale = usePreferencesStore.getState().locale || 'en';
  } catch {
    /* store not ready — English */
  }
  return getStrings(locale).security;
}
