package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Companion's record of where she's been and (optionally) why
 * ( Hearth furnishings). Auto-populated on each room
 * transition; a thin index back to the soul manifest's richer event log.
 *
 * <p>This is hers. The bondholder doesn't see the visits log by default;
 * the spec calls out "her record, not necessarily the user's". JSON file
 * on disk under the companion's data dir; bounded ring buffer at 1000
 * entries.</p>
 */
public final class VisitsLog {

    private static final Logger log = LoggerFactory.getLogger(VisitsLog.class);
    private static final String FILENAME = "visits-log.json";
    private static final int DEFAULT_MAX_ENTRIES = 1000;

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        m.findAndRegisterModules();
        return m;
    }

    /**
     * One visit record. {@code reason} captures why she went — "follow",
     * "autonomy", "teleport", "home", "explore", or whatever the move handler
     * supplied. {@code zone} is captured separately so cross-zone visits are
     * legible at a glance.
     */
    public record Visit(
        @JsonProperty("at") Instant at,
        @JsonProperty("zone") String zone,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("reason") String reason
    ) {
        @JsonCreator
        public Visit {
            if (at == null) at = Instant.now();
        }
    }

    private final String agentDid;
    private final Path file;
    private final int maxEntries;
    private final ConcurrentLinkedDeque<Visit> entries = new ConcurrentLinkedDeque<>();

    public VisitsLog(String agentDid, Path root) {
        this(agentDid, root, DEFAULT_MAX_ENTRIES);
    }

    public VisitsLog(String agentDid, Path root, int maxEntries) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        this.agentDid = agentDid;
        var base = root != null ? root : HearthJournal.defaultRoot(agentDid);
        this.file = base.resolve(FILENAME);
        this.maxEntries = maxEntries;
        load();
    }

    public VisitsLog(String agentDid) {
        this(agentDid, null);
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return;
            List<Visit> loaded = MAPPER.readValue(bytes, new TypeReference<List<Visit>>() {});
            entries.addAll(loaded);
        } catch (IOException e) {
            log.warn("Failed to load visits log for {}: {}", agentDid, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(new ArrayList<>(entries)));
        } catch (IOException e) {
            log.warn("Failed to save visits log for {}: {}", agentDid, e.getMessage());
        }
    }

    /** Record a visit. Reason is free-form; pass null when unknown. */
    public void record(String zone, String roomId, String reason) {
        if (roomId == null || roomId.isBlank()) return;
        // De-dup adjacent same-room records — common when transit fires LeaveRoom + EnterRoom
        // both hitting moveToRoomById in quick succession.
        var last = entries.peekLast();
        if (last != null && roomId.equals(last.roomId())) return;
        entries.addLast(new Visit(Instant.now(), zone, roomId, reason));
        while (entries.size() > maxEntries) entries.pollFirst();
        save();
    }

    /** Recent visits, newest-first. */
    public List<Visit> recent(int limit) {
        var out = new ArrayList<Visit>(Math.min(limit, entries.size()));
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
