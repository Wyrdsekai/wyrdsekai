package org.wyrdsekai.core.recipe;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse human-friendly duration strings out of recipe YAML (#1012 step {@code timeout:}).
 *
 * <p>Accepts:
 * <ul>
 *   <li>{@code 30s} / {@code 90s} — seconds</li>
 *   <li>{@code 5m} / {@code 30m} — minutes</li>
 *   <li>{@code 2h} / {@code 1.5h} — hours</li>
 *   <li>{@code 500ms} — milliseconds</li>
 *   <li>{@code PT30M} / ISO-8601 — passes through to {@link Duration#parse}</li>
 *   <li>Bare number → seconds ({@code 90} ≡ {@code 90s})</li>
 * </ul>
 *
 * <p>Throws {@link RecipeValidationException} on unrecognised input so a bad manifest fails
 * fast at parse time, not at recipe run time.
 */
final class RecipeDurations {

    private static final Pattern SHORTHAND =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?$");

    private RecipeDurations() {}

    /** Parse a YAML scalar into a {@link Duration}. Returns null for null/blank input. */
    static Duration parse(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        // ISO-8601 passthrough (PT30M, PT1H, etc).
        if (t.regionMatches(true, 0, "PT", 0, 2) || t.regionMatches(true, 0, "P", 0, 1)) {
            try { return Duration.parse(t); }
            catch (Exception ignored) { /* fall through to shorthand */ }
        }
        Matcher m = SHORTHAND.matcher(t.toLowerCase());
        if (!m.matches()) {
            throw new RecipeValidationException(
                    "invalid duration '" + raw + "' — use forms like '30s', '5m', '2h', or '500ms'");
        }
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2);
        long ms = switch (unit) {
            case "ms" -> Math.round(value);
            case "s"  -> Math.round(value * 1_000);
            case "m"  -> Math.round(value * 60_000);
            case "h"  -> Math.round(value * 3_600_000);
            default -> throw new RecipeValidationException("unknown duration unit: " + unit);
        };
        return Duration.ofMillis(ms);
    }
}
