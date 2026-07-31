package org.wyrdsekai.common.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * MessageCatalog backed by standard Java ResourceBundle (.properties files).
 * Loads from classpath: i18n/messages_{locale}.properties
 */
public final class PropertyCatalog implements MessageCatalog {

    private static final String BASE_NAME = "i18n.messages";

    private final Locale locale;
    private final ResourceBundle bundle;

    public PropertyCatalog(Locale locale) {
        this.locale = locale;
        this.bundle = ResourceBundle.getBundle(BASE_NAME, locale);
    }

    @Override
    public String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key; // fallback: return the key itself
        }
    }

    @Override
    public String get(String key, Object... args) {
        var pattern = get(key);
        if (pattern.equals(key)) {
            return key; // no translation found, don't attempt format
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            return pattern; // malformed pattern, return as-is
        }
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public boolean hasKey(String key) {
        return bundle.containsKey(key);
    }
}
