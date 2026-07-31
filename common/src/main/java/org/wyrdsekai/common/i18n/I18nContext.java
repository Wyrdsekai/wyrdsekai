package org.wyrdsekai.common.i18n;

import java.util.Locale;

/**
 * Per-session/per-entity locale context.
 * Immutable — create a new context when locale changes.
 */
public record I18nContext(Locale locale, MessageCatalog catalog) {

    /** Create a context from a BCP 47 language tag (e.g. "es", "ja", "en-US"). */
    public static I18nContext of(String langTag) {
        var locale = Locale.forLanguageTag(langTag);
        return new I18nContext(locale, I18n.catalogFor(locale));
    }

    /** Default context (English). */
    public static I18nContext defaultContext() {
        return of("en");
    }

    /** BCP 47 language tag for wire protocol. */
    public String langTag() {
        return locale.toLanguageTag();
    }

    /** Whether this context is the default (English). */
    public boolean isDefault() {
        return "en".equals(locale.getLanguage());
    }
}
