package org.wyrdsekai.common.i18n;

import java.util.Locale;

/**
 * Abstraction over message lookup for i18n.
 * Implementations may be backed by ResourceBundle, JSON files, or inference.
 */
public interface MessageCatalog {

    /** Look up a message by key. Returns the key itself if no translation found. */
    String get(String key);

    /** Look up a message by key with MessageFormat arguments. */
    String get(String key, Object... args);

    /** The locale this catalog serves. */
    Locale getLocale();

    /** Whether this catalog has a translation for the given key. */
    boolean hasKey(String key);
}
