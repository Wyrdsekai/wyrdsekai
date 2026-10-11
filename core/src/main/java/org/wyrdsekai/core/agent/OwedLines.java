package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.identity.PersonIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines she owes a person she could not reach: an answer, or her bunshin's report, for someone who
 * had left the room, whose session was gone and who has no mail (a visitor). Until now such a line
 * was logged ("had an answer for anonymous and no way reached them", the household node, 2026-10-05)
 * and lost. It is kept here, on disk, and said to them the next time they are seen: when they walk
 * into her room, when they speak to her, or when a session of theirs can be reached again.
 *
 * <p>Bounded: a few dozen lines per companion, kept for a week.</p>
 */
public final class OwedLines {

    private static final Logger log = LoggerFactory.getLogger(OwedLines.class);

    /** One line owed to one person. */
    public record Owed(String personId, String personName, String text, Instant since) {}

    static final int MAX = 40;
    static final Duration KEEP = Duration.ofDays(7);

    private final Path file;
    private final List<Owed> lines = new ArrayList<>();

    private OwedLines(Path file) { this.file = file; }

    /** Kept nowhere: tests, and a node with no data directory. */
    public static OwedLines inMemory() { return new OwedLines(null); }

    /** Where a companion's owed lines live: beside her other ledgers, named by her entity id. */
    public static Path pathFor(String dataDir, String entityId) {
        if (dataDir == null || dataDir.isBlank() || entityId == null || entityId.isBlank()) return null;
        return Paths.get(dataDir, "substrate", "owed-" + entityId.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
    }

    /** The lines from the file, or none when there is no file or it cannot be read. */
    public static OwedLines load(Path file) {
        var owed = new OwedLines(file);
        if (file == null || !Files.exists(file)) return owed;
        try {
            var tree = Json.mapper().readValue(file.toFile(), new TypeReference<Map<String, Object>>() {});
            var raw = tree.get("lines");
            if (raw instanceof List<?> items) {
                for (var item : items) {
                    if (!(item instanceof Map<?, ?> m)) continue;
                    var id = str(m.get("personId"));
                    var text = str(m.get("text"));
                    if (id == null || text == null) continue;
                    Instant since;
                    try { since = Instant.parse(str(m.get("since"))); } catch (Exception e) { since = Instant.now(); }
                    owed.lines.add(new Owed(id, str(m.get("personName")), text, since));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Owed lines at {} not read: {}", file, e.getMessage());
        }
        return owed;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /** Keep a line for a person; the oldest goes when there are too many. */
    public synchronized void add(String personId, String personName, String text) {
        if (personId == null || personId.isBlank() || text == null || text.isBlank()) return;
        lines.add(new Owed(personId, personName, text, Instant.now()));
        while (lines.size() > MAX) lines.remove(0);
        save();
    }

    /** The lines owed to this person, oldest first, taken out of the ledger. */
    public synchronized List<Owed> takeFor(String personId) {
        if (personId == null) return List.of();
        var taken = new ArrayList<Owed>();
        var it = lines.iterator();
        while (it.hasNext()) {
            var o = it.next();
            if (personId.equals(o.personId()) || PersonIds.samePerson(o.personId(), personId)) {
                taken.add(o);
                it.remove();
            }
        }
        if (!taken.isEmpty()) save();
        return taken;
    }

    /** True when something is owed to this person. */
    public synchronized boolean owes(String personId) {
        if (personId == null) return false;
        for (var o : lines) {
            if (personId.equals(o.personId()) || PersonIds.samePerson(o.personId(), personId)) return true;
        }
        return false;
    }

    /** Every line owed, oldest first. */
    public synchronized List<Owed> all() { return List.copyOf(lines); }

    /** A line that was delivered another way. */
    public synchronized void remove(Owed owed) {
        if (lines.remove(owed)) save();
    }

    /** Lines older than a week are let go; returns how many. */
    public synchronized int expire(Instant now) {
        int before = lines.size();
        lines.removeIf(o -> Duration.between(o.since(), now).compareTo(KEEP) > 0);
        int gone = before - lines.size();
        if (gone > 0) save();
        return gone;
    }

    public synchronized boolean isEmpty() { return lines.isEmpty(); }

    public synchronized int size() { return lines.size(); }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            var out = new ArrayList<Map<String, Object>>();
            for (var o : lines) {
                var m = new LinkedHashMap<String, Object>();
                m.put("personId", o.personId());
                m.put("personName", o.personName());
                m.put("text", o.text());
                m.put("since", o.since().toString());
                out.add(m);
            }
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), Map.of("lines", out));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("Owed lines not saved to {}: {}", file, e.getMessage());
        }
    }
}
