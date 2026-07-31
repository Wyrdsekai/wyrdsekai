package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * installs the Tier-0/Tier-1 starter packs on first boot when the
 * steward opted in at {@code wyrd setup} (the locale-aware "Download starter Library?" prompt
 * writes {@code WYRDSEKAI_LIBRARY_STARTER=true} + {@code WYRDSEKAI_LIBRARY_LANGS=...} into the
 * env file).
 *
 * <p>Setup can't install directly — the server isn't running yet and indexing needs the live
 * Lucene store — so the choice is recorded and honored here. Idempotent: packs that already have
 * chunks are skipped, so the flag can stay set across restarts. Runs on a background thread,
 * never blocks boot, downloads sequentially (smallest first) so a slow simple-wikipedia pull
 * doesn't starve the dictionaries.</p>
 */
public final class StarterLibraryInstaller {

    private static final Logger log = LoggerFactory.getLogger(StarterLibraryInstaller.class);

    private StarterLibraryInstaller() {}

    /** Kick off the starter install if the steward requested it at setup. Returns immediately. */
    public static void installIfRequested(WyrdLuceneStore lucene, Path packsDir) {
        if (lucene == null || packsDir == null) return;
        String flag = env("WYRDSEKAI_LIBRARY_STARTER");
        if (!isTruthy(flag)) return;

        var langs = parseLangs(env("WYRDSEKAI_LIBRARY_LANGS"));
        var packs = selectStarterPacks(langs);
        if (packs.isEmpty()) return;

        var indexer = new KnowledgePackIndexer(lucene);
        Thread.ofVirtual().name("starter-library-install").start(() -> {
            for (var pack : packs) {
                try {
                    if (indexer.packSize(pack.name()) > 0) {
                        log.info("[StarterLibrary] '{}' already installed — skipping", pack.name());
                        continue;
                    }
                    log.info("[StarterLibrary] installing '{}' ({})", pack.name(), pack.estimatedSize());
                    var result = KnowledgePackRegistry.install(pack.name(), packsDir, indexer,
                        msg -> log.info("[StarterLibrary] {}: {}", pack.name(), msg)).join();
                    log.info("[StarterLibrary] '{}' done — {} chunks", pack.name(), result.chunksIndexed());
                } catch (Exception e) {
                    // One failed pack must not sink the rest; next boot retries (still idempotent).
                    log.warn("[StarterLibrary] '{}' failed: {}", pack.name(), e.getMessage());
                }
            }
            log.info("[StarterLibrary] starter install pass complete");
        });
    }

    /**
     * The starter set for a household's languages: every Tier-0 pack (dictionaries — always),
     * plus every <em>essential</em> pack above Tier 0 whose languages overlap the requested set.
     * That's the Tier-1 starters (simple-wikipedia, gutenberg-classics) <em>and</em> any Tier-2
     * pack the registry marks essential (medquad + the QA stackexchanges) — the registry's
     * {@code essential} flag is the single source of truth for "ship this by default", so a
     * fresh node comes up with the same shelf the registry author intended, not just Tier ≤ 1.
     * Ordered smallest-download first (parsed from {@code estimatedSize}) so the big
     * simple-wikipedia pull never starves the rest — and, being the largest, lands last.
     */
    static List<KnowledgePackRegistry.PackInfo> selectStarterPacks(Set<String> langs) {
        var out = new ArrayList<KnowledgePackRegistry.PackInfo>();
        out.addAll(KnowledgePackRegistry.listTier(0));
        var rest = KnowledgePackRegistry.listEssential().stream()
            .filter(p -> p.effectiveTier() >= 1)
            .filter(p -> overlaps(p.language(), langs))
            .sorted(Comparator.comparingInt(StarterLibraryInstaller::pullWeightMb))
            .toList();
        out.addAll(rest);
        return out;
    }

    /**
     * Rough download size in MB parsed from the free-text {@code estimatedSize}
     * (e.g. {@code "~520 MB download, ~200 MB indexed"} → 520; {@code "~2 GB"} → 2048),
     * used purely to order the starter pulls smallest-first. Unknown/blank sizes sort last.
     */
    static int pullWeightMb(KnowledgePackRegistry.PackInfo pack) {
        var s = pack.estimatedSize();
        if (s == null || s.isBlank()) return Integer.MAX_VALUE;
        var m = SIZE_PATTERN.matcher(s);
        if (!m.find()) return Integer.MAX_VALUE;
        int n = Integer.parseInt(m.group(1));
        return "gb".equalsIgnoreCase(m.group(2)) ? n * 1024 : n;
    }

    private static final Pattern SIZE_PATTERN =
        Pattern.compile("(\\d+)\\s*(GB|MB)", Pattern.CASE_INSENSITIVE);

    private static boolean overlaps(List<String> packLangs, Set<String> requested) {
        if (packLangs == null || packLangs.isEmpty()) return true; // language-agnostic pack
        for (var l : packLangs) {
            if (requested.contains(l.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Parse "ja,en" → {ja, en}; English is always included as the federation lingua franca. */
    static Set<String> parseLangs(String csv) {
        var set = new LinkedHashSet<String>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        }
        set.add("en");
        return set;
    }

    static boolean isTruthy(String v) {
        if (v == null) return false;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "pending", "on" -> true;
            default -> false;
        };
    }

    private static String env(String key) {
        var v = System.getenv(key);
        return v != null ? v : System.getProperty(key.toLowerCase(Locale.ROOT).replace('_', '.'));
    }
}
