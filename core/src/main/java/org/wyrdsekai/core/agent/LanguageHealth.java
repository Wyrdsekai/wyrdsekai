package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.SoulManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * What the language healer sees in a soul, for the steward's eye: the notes it reads as another
 * language than the household's, each with its strikes and whether it has been left in peace.
 *
 * <p>A steward whose node was busy with re-renders could not tell whether the notes were in another
 * language or the detector misread them: the soul files are the node's own (a household's 0.5.4
 * report, 2026-10-05). {@code wyrd soul language <companion>} shows this.</p>
 */
public final class LanguageHealth {

    private LanguageHealth() {}

    private static final int LIMIT = 200;
    private static final int SNIPPET = 140;

    public record Item(String id, String kind, String detected, int strikes, boolean mercied, String text) {}

    public record Report(String household, boolean verifiable, int fragments, int memoryNodes, List<Item> items) {}

    /** The household's language as the node knows it: the configured one, else English. */
    public static String householdLanguage() {
        var v = configuredHouseholdLanguage();
        return v == null ? "en" : v;
    }

    /**
     * The household's language as configured: {@code WYRDSEKAI_LOCALE}, the key setup writes and the
     * rest of the product reads first, then {@code WYRDSEKAI_LANG}; null when neither is set. A node
     * installed from the terminal sets neither, and its household's language is then the one a
     * person's own app reports (2026-10-05: reading only WYRDSEKAI_LANG would have turned a Spanish
     * household's companion to English on her own time).
     */
    public static String configuredHouseholdLanguage() {
        return householdLanguageFrom(System.getenv("WYRDSEKAI_LOCALE"), System.getenv("WYRDSEKAI_LANG"));
    }

    static String householdLanguageFrom(String locale, String lang) {
        var v = normalizedLanguage(locale);
        return v != null ? v : normalizedLanguage(lang);
    }

    /** "es-ES", "ES", "es_ES.UTF-8" → "es"; blank → null. */
    public static String normalizedLanguage(String v) {
        if (v == null || v.isBlank()) return null;
        var l = v.strip().toLowerCase(java.util.Locale.ROOT).split("[-_.]")[0];
        return l.isBlank() ? null : l;
    }

    public static Report of(SoulManifest m, String household, LanguageHealLedger ledger, String companionName) {
        var items = new ArrayList<Item>();
        boolean verifiable = CompanionActor.detectorVerifiable(household);
        int fragments = 0, nodes = 0;
        if (m != null && verifiable) {
            for (var f : CompanionActor.fragmentLanguageOffenders(m.soulFragments(), household, LIMIT, null)) {
                fragments++;
                items.add(item(f.id(), "fragment", f.text(), ledger));
            }
            for (var n : CompanionActor.memoryNodeLanguageOffenders(m.memory(), household, LIMIT, null, companionName)) {
                nodes++;
                items.add(item(n.id(), "memory", n.content(), ledger));
            }
        }
        return new Report(household, verifiable, fragments, nodes, List.copyOf(items));
    }

    private static Item item(String id, String kind, String text, LanguageHealLedger ledger) {
        var detected = CompanionActor.detectLanguage(text);
        int strikes = ledger == null ? 0 : ledger.strikes(id);
        return new Item(id, kind, detected == null ? "?" : detected, strikes,
            strikes >= LanguageHealLedger.MAX_STRIKES, snippet(text));
    }

    static String snippet(String text) {
        if (text == null) return "";
        var one = text.replaceAll("\\s+", " ").strip();
        return one.length() <= SNIPPET ? one : one.substring(0, SNIPPET - 1) + "…";
    }
}
