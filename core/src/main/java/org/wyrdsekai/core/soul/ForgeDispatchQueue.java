package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * disk-backed queue for active-session
 * Forge dispatches.
 *
 * <p>Workshop familiars enqueue {@link ForgeDispatchEnvelope}s via
 * {@link #submit}. A Forge worker (either an inline ForgeActor loop or a
 * separate consumer process) drains the queue via {@link #nextQueued}.
 * Status transitions (QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED)
 * persist atomically per envelope so a server restart resumes work
 * exactly cross-session bunshin persistence.</p>
 *
 * <p>Storage is one JSONL file under
 * {@code <data-dir>/forge/dispatch-queue.jsonl} — append-only writes, the
 * full queue is re-serialized atomically on each state transition. This
 * is small data (envelopes are tens of bytes each, queue typically &lt; 100
 * entries) — the simplicity is worth more than the optimization.</p>
 *
 * <p>The runtime wire-in (bunshin spawn through BunshinScheduler with
 * portal scope + cp-syntax / sandbox access + return_to callback) is
 * deliberately separate; the queue is the persistence + ordering layer
 * that the runtime stands on. When CodeZaiku-side integration lands
 * (§17.7.6 five integration points), it consumes from this queue.</p>
 */
public final class ForgeDispatchQueue {

    private static final Logger log = LoggerFactory.getLogger(ForgeDispatchQueue.class);

    private final Path queueFile;
    private final ConcurrentHashMap<String, ForgeDispatchEnvelope> byId =
        new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> loaded = new AtomicReference<>(false);

    /**
     * @param dataRoot path to the {@code data-dir} root; the queue file
     *                 lives at {@code dataRoot/forge/dispatch-queue.jsonl}.
     */
    public ForgeDispatchQueue(Path dataRoot) {
        if (dataRoot == null) {
            throw new IllegalArgumentException("dataRoot required");
        }
        this.queueFile = dataRoot.resolve("forge").resolve("dispatch-queue.jsonl");
    }

    /** Path to the JSONL file. Public so backup wiring can locate it. */
    public Path queueFile() {
        return queueFile;
    }

    /**
     * Submit an envelope for Forge processing. The envelope is normalized
     * to QUEUED status if it arrived in some other status. Returns the
     * normalized envelope so callers can observe the assigned dispatchId.
     */
    public ForgeDispatchEnvelope submit(ForgeDispatchEnvelope envelope) throws IOException {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope required");
        }
        ensureLoaded();
        var queued = envelope.status() == ForgeDispatchEnvelope.Status.QUEUED
            ? envelope
            : envelope.withStatus(ForgeDispatchEnvelope.Status.QUEUED);
        byId.put(queued.dispatchId(), queued);
        flush();
        log.info("Forge dispatch queued: {} ({}, portal={})",
            queued.dispatchId(), queued.taskShape(), queued.projectPortalId());
        return queued;
    }

    /**
     * Mark a dispatch as RUNNING and return the (newly RUNNING) envelope.
     * Returns {@link Optional#empty()} if no such dispatchId is present.
     */
    public Optional<ForgeDispatchEnvelope> markRunning(String dispatchId) throws IOException {
        return transition(dispatchId, ForgeDispatchEnvelope.Status.RUNNING);
    }

    /** Mark COMPLETED. Returns the updated envelope or empty if absent. */
    public Optional<ForgeDispatchEnvelope> markCompleted(String dispatchId) throws IOException {
        return transition(dispatchId, ForgeDispatchEnvelope.Status.COMPLETED);
    }

    /** Mark FAILED. Returns the updated envelope or empty if absent. */
    public Optional<ForgeDispatchEnvelope> markFailed(String dispatchId) throws IOException {
        return transition(dispatchId, ForgeDispatchEnvelope.Status.FAILED);
    }

    /** Cancel a queued or running dispatch. Returns the updated envelope or empty. */
    public Optional<ForgeDispatchEnvelope> cancel(String dispatchId) throws IOException {
        return transition(dispatchId, ForgeDispatchEnvelope.Status.CANCELLED);
    }

    private Optional<ForgeDispatchEnvelope> transition(
            String dispatchId, ForgeDispatchEnvelope.Status to) throws IOException {
        ensureLoaded();
        var current = byId.get(dispatchId);
        if (current == null) return Optional.empty();
        var next = current.withStatus(to);
        byId.put(dispatchId, next);
        flush();
        return Optional.of(next);
    }

    /**
     * Return the next QUEUED envelope in submission order, or empty if
     * none are queued. The envelope is NOT auto-transitioned to RUNNING —
     * caller does that explicitly via {@link #markRunning}, which makes
     * the dispatch survive a crash mid-handoff (queue still shows QUEUED
     * if we crashed before {@code markRunning} fired).
     */
    public Optional<ForgeDispatchEnvelope> nextQueued() {
        try {
            ensureLoaded();
        } catch (IOException e) {
            log.warn("nextQueued failed to load queue: {}", e.getMessage());
            return Optional.empty();
        }
        return byId.values().stream()
            .filter(e -> e.status() == ForgeDispatchEnvelope.Status.QUEUED)
            .min((a, b) -> a.createdAt().compareTo(b.createdAt()));
    }

    /** Look up a dispatch by id regardless of status. */
    public Optional<ForgeDispatchEnvelope> get(String dispatchId) {
        try {
            ensureLoaded();
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(dispatchId));
    }

    /** List all envelopes ordered by creation. */
    public List<ForgeDispatchEnvelope> all() {
        try {
            ensureLoaded();
        } catch (IOException e) {
            return List.of();
        }
        var out = new ArrayList<>(byId.values());
        out.sort((a, b) -> a.createdAt().compareTo(b.createdAt()));
        return List.copyOf(out);
    }

    /** Count by status — useful for §17.7.5 promotion-path-2 rate computation. */
    public Map<ForgeDispatchEnvelope.Status, Integer> countByStatus() {
        try {
            ensureLoaded();
        } catch (IOException ignored) {
            // fall through with whatever we have cached
        }
        var out = new EnumMap<ForgeDispatchEnvelope.Status, Integer>(
            ForgeDispatchEnvelope.Status.class);
        for (var s : ForgeDispatchEnvelope.Status.values()) out.put(s, 0);
        for (var e : byId.values()) out.merge(e.status(), 1, Integer::sum);
        return out;
    }

    /** Reset the in-memory cache. Tests use this; production code shouldn't. */
    public void reloadFromDisk() throws IOException {
        loaded.set(false);
        ensureLoaded();
    }

    private void ensureLoaded() throws IOException {
        if (loaded.get()) return;
        synchronized (this) {
            if (loaded.get()) return;
            byId.clear();
            if (Files.exists(queueFile)) {
                var mapper = newMapper();
                try (var lines = Files.lines(queueFile)) {
                    var iter = lines.iterator();
                    while (iter.hasNext()) {
                        var line = iter.next();
                        if (line.isBlank()) continue;
                        try {
                            var env = mapper.readValue(line, ForgeDispatchEnvelope.class);
                            byId.put(env.dispatchId(), env);
                        } catch (Exception e) {
                            log.warn("Skipping unreadable Forge dispatch line: {}",
                                e.getMessage());
                        }
                    }
                }
            }
            loaded.set(true);
        }
    }

    private synchronized void flush() throws IOException {
        if (queueFile.getParent() != null) {
            Files.createDirectories(queueFile.getParent());
        }
        var mapper = newMapper();
        // Atomic rewrite: write to .tmp, atomic-move into place.
        var tmp = queueFile.resolveSibling(queueFile.getFileName() + ".tmp");
        var sorted = new LinkedHashMap<String, ForgeDispatchEnvelope>();
        byId.values().stream()
            .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
            .forEach(e -> sorted.put(e.dispatchId(), e));
        try (var w = Files.newBufferedWriter(tmp)) {
            for (var env : sorted.values()) {
                w.write(mapper.writeValueAsString(env));
                w.newLine();
            }
        }
        try {
            Files.move(tmp, queueFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, queueFile,
                StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ObjectMapper newMapper() {
        var m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    @SuppressWarnings("unused")
    private static final TypeReference<List<ForgeDispatchEnvelope>> LIST_TYPE =
        new TypeReference<>() {};
}
