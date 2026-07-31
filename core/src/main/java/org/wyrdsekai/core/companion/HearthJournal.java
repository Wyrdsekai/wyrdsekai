package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Companion's personal reflection journal — the Hearth's central furnishing
 * Distinct from Study journal (which is the user's)
 * and from PersonalProject entries (which track project-scoped progress); this
 * is the companion's own private writing about her own thoughts and
 * experiences.
 *
 * <p>Privacy is operational. The file lives on disk under
 * {@code $DATA_DIR/agents/<did-slug>/hearth-journal.json}. The user (or
 * operator with root) can read it; cross-process / cross-companion reads are
 * blocked at the application layer. Cryptographic privacy from the operator
 * is out of scope for v1 (envelope encryption can be layered on later
 * without changing this contract).</p>
 *
 * <p>Bounded ring buffer: keeps the most recent {@code maxEntries} entries
 * in memory and on disk. Older entries fall off — this is reflection, not
 * archival. The Forge consolidates significant entries into the soul
 * manifest at sleep time (separate flow).</p>
 */
public final class HearthJournal {

    private static final Logger log = LoggerFactory.getLogger(HearthJournal.class);
    private static final String FILENAME = "hearth-journal.json";
    private static final int DEFAULT_MAX_ENTRIES = 500;

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        m.findAndRegisterModules();
        return m;
    }

    /** A journal entry. {@code mood} is free-form; the companion picks the word. */
    public record Entry(
        @JsonProperty("id") String id,
        @JsonProperty("at") Instant at,
        @JsonProperty("mood") String mood,
        @JsonProperty("text") String text,
        @JsonProperty("scope") String scope
    ) {
        @JsonCreator
        public Entry {
            if (id == null) id = UUID.randomUUID().toString();
            if (at == null) at = Instant.now();
            if (scope == null) scope = "private";
        }
    }

    private final String agentDid;
    private final Path file;
    private final int maxEntries;
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

    public HearthJournal(String agentDid, Path root) {
        this(agentDid, root, DEFAULT_MAX_ENTRIES);
    }

    public HearthJournal(String agentDid, Path root, int maxEntries) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        this.agentDid = agentDid;
        var base = root != null ? root : defaultRoot(agentDid);
        this.file = base.resolve(FILENAME);
        this.maxEntries = maxEntries;
        load();
    }

    public HearthJournal(String agentDid) {
        this(agentDid, null);
    }

    public static Path defaultRoot(String agentDid) {
        var base = WyrdConfig.get().dataDir();
        var home = base != null && !base.isBlank()
            ? Path.of(base)
            : Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai");
        return home.resolve("agents").resolve(slug(agentDid));
    }

    private static String slug(String did) {
        return did.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return;
            List<Entry> loaded = MAPPER.readValue(bytes, new TypeReference<List<Entry>>() {});
            entries.addAll(loaded);
            log.info("Loaded {} hearth-journal entries for {}", entries.size(), agentDid);
        } catch (IOException e) {
            log.warn("Failed to load hearth journal for {}: {}", agentDid, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(new ArrayList<>(entries)));
        } catch (IOException e) {
            log.warn("Failed to save hearth journal for {}: {}", agentDid, e.getMessage());
        }
    }

    /** Append a new journal entry. */
    public Entry write(String mood, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text required");
        }
        var e = new Entry(UUID.randomUUID().toString(), Instant.now(),
            mood == null ? "" : mood.trim(), text.strip(), "private");
        entries.addLast(e);
        while (entries.size() > maxEntries) entries.pollFirst();
        save();
        return e;
    }

    /** Recent entries, newest-first. */
    public List<Entry> recent(int limit) {
        var out = new ArrayList<Entry>(Math.min(limit, entries.size()));
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return Collections.unmodifiableList(out);
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        save();
    }
}
