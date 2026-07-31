package org.wyrdsekai.core.room;

import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restyle a room's authored description under the active {@link ZoneAesthetic}.
 *
 * <p>Pure static, no actor state, no inference — the twin of
 * {@link org.wyrdsekai.core.ambient.AmbientRenderer} for theme (rather than
 * time-of-day). Consumed by {@code RoomActor.onLookRoom} to colour what a
 * visitor reads when they look at a room, so a themed zone actually <em>reads</em>
 * themed and isn't just a stored label.</p>
 *
 * <p>Two transforms, both deterministic:</p>
 * <ol>
 *   <li><b>Lexicon substitution</b> — the aesthetic's curated vocabulary
 *       ({@code room→chamber}, {@code tool→artifact}, {@code search→scry}, …)
 *       is applied whole-word, case-preserving, with naive plural handling, over
 *       the authored prose. The mappings are author-chosen per theme, so an
 *       arcane library that "searches for knowledge" becomes one that "scrys for
 *       knowledge" — on-theme by construction.</li>
 *   <li><b>Atmosphere line</b> — a themed sensory sentence is woven in after the
 *       prose (i18n key {@code aesthetic.atmosphere.<name>}, English fallback
 *       baked in so the engine never appends a blank line). Exactly parallel to
 *       the ambient phase overlay.</li>
 * </ol>
 *
 * <p>The default / {@link ZoneAesthetic#none()} aesthetic is a no-op: the base
 * description passes through untouched.</p>
 */
public final class ZoneAestheticDescriber {

    private ZoneAestheticDescriber() {}

    /**
     * Restyle {@code baseDescription} under {@code aesthetic} in {@code locale}.
     * Returns the input unchanged for a null/blank description or the default
     * aesthetic.
     *
     * @param baseDescription the authored (already i18n-resolved) room description
     * @param aesthetic       the effective zone/room aesthetic (nullable → no-op)
     * @param locale          IETF language tag ({@code en}, {@code es}, {@code ja}, …)
     */
    public static String restyle(String baseDescription, ZoneAesthetic aesthetic, String locale) {
        if (baseDescription == null || baseDescription.isBlank()) return baseDescription;
        if (aesthetic == null) return baseDescription;
        var name = aesthetic.name();
        if (name == null || name.isBlank() || "default".equals(name)) return baseDescription;

        var prose = applyLexicon(baseDescription, aesthetic.lexicon());
        var atmosphere = atmosphereLine(name, locale);
        if (atmosphere == null || atmosphere.isBlank()) return prose;
        return prose + "\n\n" + atmosphere;
    }

    /**
     * Apply a lexicon as whole-word, case-preserving substitution over prose.
     * Naive plural support: a word ending in {@code s} whose singular maps is
     * replaced with the mapping + {@code s}. Package-private for tests.
     */
    static String applyLexicon(String prose, Map<String, String> lexicon) {
        if (prose == null || lexicon == null || lexicon.isEmpty()) return prose;
        var sb = new StringBuilder();
        var matcher = WORD.matcher(prose);
        while (matcher.find()) {
            var word = matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(substitute(word, lexicon)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Match runs of letters (apostrophes kept inside so "don't" stays whole). */
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");

    private static String substitute(String word, Map<String, String> lexicon) {
        var lower = word.toLowerCase(Locale.ROOT);
        var hit = lexicon.get(lower);
        if (hit != null) return matchCase(word, hit);
        // naive plural: "chambers" → singular "chamber" maps → re-pluralise
        if (lower.length() > 1 && lower.endsWith("s")) {
            var singular = lexicon.get(lower.substring(0, lower.length() - 1));
            if (singular != null) return matchCase(word, singular + "s");
        }
        return word;
    }

    /** Re-apply the original word's casing to a replacement. */
    private static String matchCase(String original, String replacement) {
        if (original.isEmpty() || replacement.isEmpty()) return replacement;
        boolean allUpper = original.equals(original.toUpperCase(Locale.ROOT))
            && original.chars().anyMatch(Character::isLetter);
        if (allUpper) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    /**
     * Themed atmosphere line for an aesthetic. Prefers i18n key
     * {@code aesthetic.atmosphere.<name>}; falls back to a baked English line so
     * a configured theme always contributes something. Unknown themes → "".
     */
    static String atmosphereLine(String name, String locale) {
        if (name == null) return "";
        var lang = (locale == null || locale.isBlank()) ? "en" : locale;
        var key = "aesthetic.atmosphere." + name.toLowerCase(Locale.ROOT);
        var catalog = ScriptMessageCatalog.forLang(lang);
        if (catalog.hasKey(key)) return catalog.get(key);
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "arcane" -> "A faint shimmer of old magic clings to the air, and the shadows seem to listen.";
            case "cyberpunk" -> "Neon bleed and the low hum of distant servers wash over everything here.";
            case "steampunk" -> "Brass fittings catch the light, and somewhere a clockwork mechanism ticks patiently.";
            case "minimalist" -> "Everything here is clean lines and uncluttered, deliberate space.";
            case "garden" -> "Green things grow at the edges, and the air carries the scent of soil and leaf.";
            case "wild" -> "Nothing here sits quite still; the place hums with untamed, restless life.";
            case "sanctuary" -> "A deep quiet rests over everything, soft as a held breath.";
            default -> "";
        };
    }
}
