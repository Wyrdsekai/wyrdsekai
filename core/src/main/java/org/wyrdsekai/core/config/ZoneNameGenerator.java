package org.wyrdsekai.core.config;

import java.security.SecureRandom;
import java.util.List;

/**
 * Curated zone-name + theme bundles. Used by {@code wyrd setup} / {@code
 * wyrd config init} so that a fresh install gets a memorable zone name
 * (not "home", not the hostname) and a coherent matching aesthetic
 * theme out of the box.
 *
 * <p>The pairing is deliberate: a zone called {@code hearth} feels
 * native to the {@code garden} aesthetic; {@code grid} feels native to
 * {@code cyberpunk}. Random selection across bundles gives every fresh
 * install a distinct flavor without burdening the operator with a
 * "pick your aesthetic" prompt at first run. They can change it at
 * any time via {@code wyrd config set node.theme=...}.</p>
 *
 * <p>Names are short (1-2 syllables), evocative, and unlikely to collide
 * with hostnames or DNS labels. They're not security tokens — collisions
 * across households are fine; the Between bus uses fingerprinted IDs
 * for routing, not zone display names.</p>
 */
public final class ZoneNameGenerator {

    private ZoneNameGenerator() {}

    /** A zone-name + theme pairing. */
    public record Bundle(String zoneName, String theme) {}

    /** Curated bundles. Each theme has 5+ candidate names. */
    private static final List<Bundle> BUNDLES = List.of(
        // garden — soft, pastoral, grown-not-built
        new Bundle("hearth",  "garden"),
        new Bundle("verdant",     "garden"),
        new Bundle("ferngrove",   "garden"),
        new Bundle("mossbank",    "garden"),
        new Bundle("willowmere",  "garden"),

        // arcane — old, wrought, knowing
        new Bundle("obsidian",    "arcane"),
        new Bundle("scriptorium", "arcane"),
        new Bundle("ember",       "arcane"),
        new Bundle("candlelit",   "arcane"),
        new Bundle("vellum",      "arcane"),

        // cyberpunk — networked, terse, electric
        new Bundle("lattice",     "cyberpunk"),
        new Bundle("mesh",        "cyberpunk"),
        new Bundle("neon",        "cyberpunk"),
        new Bundle("circuit",     "cyberpunk"),
        new Bundle("synapse",     "cyberpunk"),

        // steampunk — brass, gear, vapor
        new Bundle("brassworks",  "steampunk"),
        new Bundle("foundry",     "steampunk"),
        new Bundle("gearworks",   "steampunk"),
        new Bundle("turbine",     "steampunk"),
        new Bundle("aetheric",    "steampunk"),

        // wild — feral, fast, surprising
        new Bundle("thicket",     "wild"),
        new Bundle("spark",       "wild"),
        new Bundle("current",     "wild"),
        new Bundle("quicksilver", "wild"),
        new Bundle("ravens",      "wild"),

        // sanctuary — quiet, careful, held
        new Bundle("hush",        "sanctuary"),
        new Bundle("refuge",      "sanctuary"),
        new Bundle("hearth",      "sanctuary"),
        new Bundle("lantern",     "sanctuary"),
        new Bundle("cradle",      "sanctuary"),

        // minimalist — clean, plain, true
        new Bundle("atrium",      "minimalist"),
        new Bundle("prism",       "minimalist"),
        new Bundle("plain",       "minimalist"),
        new Bundle("clear",       "minimalist"),
        new Bundle("axis",        "minimalist")
    );

    private static final SecureRandom RNG = new SecureRandom();

    /** Pick a random (zone name, theme) bundle. */
    public static Bundle randomBundle() {
        return BUNDLES.get(RNG.nextInt(BUNDLES.size()));
    }

    /** Pick a bundle deterministically from a seed (hostname, etc).
     *  Same seed → same bundle, so a node won't change identity if its
     *  config is regenerated without a saved zone. */
    public static Bundle bundleForSeed(String seed) {
        if (seed == null || seed.isEmpty()) return randomBundle();
        var hash = Math.floorMod(seed.hashCode(), BUNDLES.size());
        return BUNDLES.get(hash);
    }

    /** All available themes, derived from the bundle table.  Useful for
     *  `wyrd config set node.theme` validation and listing. */
    public static List<String> availableThemes() {
        return BUNDLES.stream().map(Bundle::theme).distinct().sorted().toList();
    }

    /** Sample of zone names per theme — for help text. */
    public static List<String> sampleNames(String theme, int max) {
        return BUNDLES.stream()
            .filter(b -> b.theme().equals(theme))
            .map(Bundle::zoneName)
            .limit(max)
            .toList();
    }

    /** A random zone name drawn from the given theme's candidates. Used by
     *  {@code wyrd config init} to re-roll the SUGGESTED name once the operator
     *  picks a theme, so a garden world doesn't default to a steampunk name.
     *  Falls back to a fully-random bundle name if the theme is unknown. */
    public static String randomNameForTheme(String theme) {
        var matches = BUNDLES.stream()
            .filter(b -> theme != null && b.theme().equals(theme))
            .map(Bundle::zoneName)
            .toList();
        return matches.isEmpty()
            ? randomBundle().zoneName()
            : matches.get(RNG.nextInt(matches.size()));
    }
}
