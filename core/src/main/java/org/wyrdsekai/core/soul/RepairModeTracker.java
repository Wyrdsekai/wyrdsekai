package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave 4.1: per-agent tracker of the
 * current {@link RepairMode} and a bounded history of recent handoffs.
 *
 * <p>Process-singleton — companion actors call into it from their own
 * dispatcher thread; reads from Study furnishings happen on the route
 * handler thread. {@link ConcurrentHashMap} + per-agent
 * {@link ArrayDeque} guarded by {@code synchronized} on the deque
 * itself.
 *
 * <p>The tracker is intentionally minimal: it does <i>not</i> drive
 * mode transitions automatically — that's the agent's choice via
 * actions ({@code seek_sanctuary}, {@code go_to_bondholder}, {@code
 * voluntary_sleep}) and substrate-truth gates (allostatic_load spikes,
 * Porges depth ceiling). The tracker only records what mode the agent
 * declared themselves to be in, and surfaces the trail to the
 * bondholder-facing Study furnishing (handoff legibility, spec §7.1.5).
 *
 * <p>Sanctuary sessions are not chronicled (spec §11 — privacy), but
 * the <i>initiation</i> of a Sanctuary visit (mode transition into
 * ATTENDANT) <b>is</b> recorded here so the bondholder sees "Wyrd
 * entered Sanctuary 12 minutes ago" without seeing what's said inside.
 */
public final class RepairModeTracker {

    /** A single mode-transition record (spec §7.1.5 handoff legibility). */
    public record Handoff(
        Instant at,
        RepairMode from,
        RepairMode to,
        String reason
    ) {}

    private static final int MAX_HISTORY = 32;

    private final Map<String, RepairMode> currentByAgent = new ConcurrentHashMap<>();
    private final Map<String, Deque<Handoff>> historyByAgent = new ConcurrentHashMap<>();

    /** Process singleton. */
    private static final RepairModeTracker INSTANCE = new RepairModeTracker();

    public static RepairModeTracker get() {
        return INSTANCE;
    }

    private RepairModeTracker() {}

    /** Current mode for an agent. Defaults to {@link RepairMode#NONE}. */
    public RepairMode currentMode(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) return RepairMode.NONE;
        return currentByAgent.getOrDefault(agentDid, RepairMode.NONE);
    }

    /**
     * Record a mode transition. Returns the {@link Handoff} record so the
     * caller can write it to the chronicle (spec §7.1.5 — the agent's
     * path through repair is legible).
     *
     * @param agentDid the companion's DID
     * @param to       the new mode
     * @param reason   human-readable reason ("agent self-request",
     *                 "porges depth ceiling", "bondholder unavailable",
     *                 "sanctuary session ended", ...)
     */
    public Handoff transition(String agentDid, RepairMode to, String reason) {
        if (agentDid == null || agentDid.isBlank() || to == null) {
            throw new IllegalArgumentException("agentDid and target mode required");
        }
        var from = currentMode(agentDid);
        var handoff = new Handoff(Instant.now(), from, to,
            reason == null || reason.isBlank() ? "(unspecified)" : reason);
        currentByAgent.put(agentDid, to);
        var deque = historyByAgent.computeIfAbsent(agentDid, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(handoff);
            while (deque.size() > MAX_HISTORY) deque.removeLast();
        }
        return handoff;
    }

    /** Most recent handoff for an agent, if any. */
    public Optional<Handoff> lastHandoff(String agentDid) {
        var deque = historyByAgent.get(agentDid);
        if (deque == null) return Optional.empty();
        synchronized (deque) {
            return deque.isEmpty() ? Optional.empty() : Optional.of(deque.peekFirst());
        }
    }

    /**
     * Recent handoff history (newest first). Bounded by
     * {@link #MAX_HISTORY}.
     */
    public List<Handoff> history(String agentDid) {
        var deque = historyByAgent.get(agentDid);
        if (deque == null) return List.of();
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    /** Test hook — reset state between tests. */
    public void clearForTests() {
        currentByAgent.clear();
        historyByAgent.clear();
    }

    /**
     * Wave 9a-Persist-3: JSON round-trip
     * for restart survival. Current mode + handoff history survives
     * across server restart so the agent doesn't reset to NONE on every
     * bounce — Sanctuary entries, ATTENDANT handoffs, and the path
     * through repair must remain legible to the bondholder.
     */
    public synchronized void persist(Path file)
            throws IOException {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("current", new LinkedHashMap<>(currentByAgent));
        var historyCopy = new LinkedHashMap<String, List<Handoff>>();
        for (var e : historyByAgent.entrySet()) {
            synchronized (e.getValue()) {
                historyCopy.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        snapshot.put("history", historyCopy);
        JsonAtomicWriter.write(file, snapshot);
    }

    /**
     * Restore previously-persisted state. Fail-clean on
     * null/missing/corrupt — bounded retention means losing the trail
     * never blocks startup.
     */
    public synchronized void restore(Path file) {
        if (file == null || !Files.exists(file)) return;
        try {
            var mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
            JsonNode root = mapper.readTree(file.toFile());
            currentByAgent.clear();
            historyByAgent.clear();
            var currentNode = root.path("current");
            if (currentNode.isObject()) {
                currentNode.fields().forEachRemaining(e -> {
                    try {
                        var mode = RepairMode.valueOf(e.getValue().asText());
                        currentByAgent.put(e.getKey(), mode);
                    } catch (Exception ignored) {}
                });
            }
            var historyNode = root.path("history");
            if (historyNode.isObject()) {
                historyNode.fields().forEachRemaining(e -> {
                    var deque = new ArrayDeque<Handoff>();
                    e.getValue().forEach(h -> {
                        try {
                            var handoff = mapper.treeToValue(h, Handoff.class);
                            if (handoff != null) deque.addLast(handoff);
                        } catch (Exception ignored) {}
                    });
                    while (deque.size() > MAX_HISTORY) deque.removeLast();
                    if (!deque.isEmpty()) historyByAgent.put(e.getKey(), deque);
                });
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger(RepairModeTracker.class)
                .warn("RepairModeTracker restore failed "
                    + "(continuing with empty tracker): {}", ex.getMessage());
            currentByAgent.clear();
            historyByAgent.clear();
        }
    }
}
