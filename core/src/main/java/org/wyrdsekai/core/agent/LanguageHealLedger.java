package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the language healer has learned about each note it tried to re-render, kept across restarts.
 *
 * <p>A strike is a re-render that <em>answered</em> and was no good: still in the other language,
 * numbers lost, or the note handed back as it was. After {@link #MAX_STRIKES} of those the note is
 * presumed legitimately multilingual (a dictionary line, a quoted phrase) and left in peace. A
 * re-render that did not answer is not a strike: that is a verdict on the machine, not the note.
 * The count lived in memory until 2026-10-05; every restart granted a fresh appeal, and on a
 * household whose voice lane ran on a CPU the same notes were tried again after each one.
 */
public final class LanguageHealLedger {

    private static final Logger log = LoggerFactory.getLogger(LanguageHealLedger.class);
    public static final int MAX_STRIKES = 3;

    private final Path file;
    private final Map<String, Integer> strikes = new LinkedHashMap<>();

    private LanguageHealLedger(Path file) { this.file = file; }

    /** A ledger kept nowhere: tests, and a node with no data directory. */
    public static LanguageHealLedger inMemory() { return new LanguageHealLedger(null); }

    /** Where a companion's ledger lives: beside her protection flags, named by her DID. */
    public static Path pathFor(String dataDir, String did) {
        if (dataDir == null || dataDir.isBlank() || did == null || did.isBlank()) return null;
        return Paths.get(dataDir, "substrate", "language-heal-" + did.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
    }

    /** The ledger at {@code file}; empty when there is none yet or it cannot be read. */
    public static LanguageHealLedger load(Path file) {
        var l = new LanguageHealLedger(file);
        if (file == null || !Files.isRegularFile(file)) return l;
        try {
            var tree = Json.mapper().readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            var raw = tree.get("strikes");
            if (raw instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    if (e.getKey() instanceof String id && e.getValue() instanceof Number n && n.intValue() > 0) {
                        l.strikes.put(id, n.intValue());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Language heal ledger {} not read ({}); starting empty", file, e.toString());
        }
        return l;
    }

    /** One more re-render of this note that answered and was no good; the count now. */
    public int strike(String id) {
        if (id == null) return 0;
        return strikes.merge(id, 1, Integer::sum);
    }

    public int strikes(String id) { return id == null ? 0 : strikes.getOrDefault(id, 0); }

    public boolean mercied(String id) { return strikes(id) >= MAX_STRIKES; }

    /** The notes left in peace. */
    public Set<String> mercied() {
        var out = new TreeSet<String>();
        for (var e : strikes.entrySet()) if (e.getValue() >= MAX_STRIKES) out.add(e.getKey());
        return out;
    }

    public Map<String, Integer> all() { return Collections.unmodifiableMap(strikes); }

    public Path file() { return file; }

    /** Write the ledger down; nothing to do for one kept nowhere. */
    public void save() throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Json.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), Map.of("strikes", strikes));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
