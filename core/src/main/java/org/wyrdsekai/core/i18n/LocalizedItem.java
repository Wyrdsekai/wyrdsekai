package org.wyrdsekai.core.i18n;

import java.util.*;

/**
 * Locale-aware item wrapper (§104.4).
 * Items carry their origin language — memories in origin language,
 * no translation during Forge consolidation.
 *
 * The originLocale records what language the item was created in.
 * localizedNames provides display-only translations (not used by Forge).
 */
public record LocalizedItem(
    String itemId,
    String name,
    String description,
    String originLocale,
    Map<String, String> localizedNames,
    Map<String, String> localizedDescriptions
) {

    /** Create with just origin locale, no translations. */
    public static LocalizedItem of(String itemId, String name, String description, String locale) {
        return new LocalizedItem(itemId, name, description, locale, Map.of(), Map.of());
    }

    /** Get display name for a locale, falling back to origin. */
    public String nameFor(String locale) {
        if (locale.equals(originLocale)) return name;
        return localizedNames.getOrDefault(locale, name);
    }

    /** Get description for a locale, falling back to origin. */
    public String descriptionFor(String locale) {
        if (locale.equals(originLocale)) return description;
        return localizedDescriptions.getOrDefault(locale, description);
    }

    /** Whether this item has a translation for the given locale. */
    public boolean hasTranslation(String locale) {
        return localizedNames.containsKey(locale);
    }

    /** Add a translation (returns new instance). */
    public LocalizedItem withTranslation(String locale, String localName, String localDescription) {
        var names = new HashMap<>(localizedNames);
        names.put(locale, localName);
        var descs = new HashMap<>(localizedDescriptions);
        descs.put(locale, localDescription);
        return new LocalizedItem(itemId, name, description, originLocale,
            Map.copyOf(names), Map.copyOf(descs));
    }

    /** Number of available translations. */
    public int translationCount() {
        return localizedNames.size();
    }
}
