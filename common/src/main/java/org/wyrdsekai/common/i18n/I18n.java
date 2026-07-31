package org.wyrdsekai.common.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static accessor for i18n messages. Thread-local locale — each actor/session sets its own.
 * <pre>
 *     I18n.setLocale(Locale.forLanguageTag("es"));
 *     String text = I18n.get("vitality.energy.exhausted"); // "agotado"
 * </pre>
 */
public final class I18n {

    private static final ThreadLocal<Locale> CURRENT_LOCALE =
            ThreadLocal.withInitial(() -> Locale.ENGLISH);

    private static final Map<Locale, MessageCatalog> CATALOG_CACHE = new ConcurrentHashMap<>();

    private I18n() {}

    /** Set the locale for the current thread. */
    public static void setLocale(Locale locale) {
        CURRENT_LOCALE.set(locale != null ? locale : Locale.ENGLISH);
    }

    /** Get the locale for the current thread. */
    public static Locale getLocale() {
        return CURRENT_LOCALE.get();
    }

    /** Look up a message by key using the current thread's locale. */
    public static String get(String key) {
        return catalog().get(key);
    }

    /** Look up a message by key with format arguments. */
    public static String get(String key, Object... args) {
        return catalog().get(key, args);
    }

    /** Get the MessageCatalog for the current thread's locale. */
    public static MessageCatalog catalog() {
        return CATALOG_CACHE.computeIfAbsent(CURRENT_LOCALE.get(), PropertyCatalog::new);
    }

    /** Get a MessageCatalog for a specific locale. */
    public static MessageCatalog catalogFor(Locale locale) {
        return CATALOG_CACHE.computeIfAbsent(locale, PropertyCatalog::new);
    }

    /** Clear the thread-local (for cleanup after request). */
    public static void clear() {
        CURRENT_LOCALE.remove();
    }

    /** Clear all cached catalogs (for testing). */
    public static void clearCaches() {
        CATALOG_CACHE.clear();
    }
}
