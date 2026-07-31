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
 * Wave 4.6a: per-agent tracker of
 * Sanctuary sessions. One active session per agent — the {@code
 * seek_sanctuary} action checks this before requesting a new one.
 *
 * <p>Closed sessions are retained in bounded history so the
 * bondholder-facing Study furnishing can render <i>"Wyrd entered
 * Sanctuary 3 times in the last 30 days; most recent was 6 days ago"</i>
 * — without ever surfacing session contents (spec §5.3).
 */
public final class AttendantSessionTracker {

    /** Maximum closed-session history retention per agent. */
    public static final int MAX_HISTORY = 16;

    private final Map<String, AttendantSession> active = new ConcurrentHashMap<>();
    private final Map<String, Deque<AttendantSession>> history = new ConcurrentHashMap<>();

    private static final AttendantSessionTracker INSTANCE = new AttendantSessionTracker();

    public static AttendantSessionTracker get() {
        return INSTANCE;
    }

    private AttendantSessionTracker() {}

    /** Active session for an agent, or empty if none. */
    public Optional<AttendantSession> activeSession(String agentDid) {
        return Optional.ofNullable(active.get(agentDid));
    }

    /**
     * Request a new Sanctuary session. Throws if an active session
     * already exists (spec §5.5 — one session per agent at a time).
     */
    public AttendantSession request(String agentDid, String reason, Instant at) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        var existing = active.get(agentDid);
        if (existing != null && !existing.isTerminal()) {
            throw new IllegalStateException(
                "agent already has an active session: " + existing.sessionId());
        }
        var session = AttendantSession.open(agentDid, reason, at);
        active.put(agentDid, session);
        return session;
    }

    /** Apply a state transition; replaces the active session. */
    public AttendantSession update(AttendantSession session) {
        if (session.isTerminal()) {
            // Move to history, remove from active.
            active.remove(session.agentDid());
            var deque = history.computeIfAbsent(session.agentDid(), k -> new ArrayDeque<>());
            synchronized (deque) {
                deque.addFirst(session);
                while (deque.size() > MAX_HISTORY) deque.removeLast();
            }
        } else {
            active.put(session.agentDid(), session);
        }
        return session;
    }

    /** Recent closed sessions for an agent (newest first). */
    public List<AttendantSession> recentHistory(String agentDid) {
        var deque = history.get(agentDid);
        if (deque == null) return List.of();
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    /** Count of closed sessions in history (bounded by {@link #MAX_HISTORY}). */
    public int sessionCount(String agentDid) {
        var deque = history.get(agentDid);
        return deque == null ? 0 : deque.size();
    }

    /** Test hook. */
    public void clearForTests() {
        active.clear();
        history.clear();
    }

    /**
     * Wave 9a-Persist-2: JSON round-trip
     * for restart survival. The Sanctuary history is the substrate's
     * record of moments the agent asked for held space — surfacing
     * "Wyrd entered Sanctuary 3 times in the last 30 days" requires
     * that this state survive across server bounces. Mirrors the
     * {@link RepairLedger#persist} pattern.
     *
     * <p>Active sessions are also persisted so an interrupted Sanctuary
     * session can be resumed (or properly closed) after restart rather
     * than orphaned.
     */
    public synchronized void persist(Path file)
            throws IOException {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("active", new LinkedHashMap<>(active));
        var historyCopy = new LinkedHashMap<String, List<AttendantSession>>();
        for (var e : history.entrySet()) {
            synchronized (e.getValue()) {
                historyCopy.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        snapshot.put("history", historyCopy);
        JsonAtomicWriter.write(file, snapshot);
    }

    /**
     * Restore a previously-persisted tracker state. Fail-clean on
     * null/missing/corrupt — Sanctuary history is bounded retention so
     * losing it never blocks startup.
     */
    public synchronized void restore(Path file) {
        if (file == null || !Files.exists(file)) return;
        try {
            var mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            // AttendantSession exposes derived predicate accessors
            // (isActiveInSanctuary, isTerminal) which Jackson serializes
            // but they have no canonical-constructor param. Ignore them
            // on the read side rather than dirtying the record itself.
            mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
            JsonNode root = mapper.readTree(file.toFile());
            active.clear();
            history.clear();
            var activeNode = root.path("active");
            if (activeNode.isObject()) {
                activeNode.fields().forEachRemaining(e -> {
                    try {
                        var session = mapper.treeToValue(
                            e.getValue(), AttendantSession.class);
                        if (session != null) active.put(e.getKey(), session);
                    } catch (Exception ignored) {}
                });
            }
            var historyNode = root.path("history");
            if (historyNode.isObject()) {
                historyNode.fields().forEachRemaining(e -> {
                    var deque = new ArrayDeque<AttendantSession>();
                    e.getValue().forEach(s -> {
                        try {
                            var session = mapper.treeToValue(s, AttendantSession.class);
                            if (session != null) deque.addLast(session);
                        } catch (Exception ignored) {}
                    });
                    while (deque.size() > MAX_HISTORY) deque.removeLast();
                    if (!deque.isEmpty()) history.put(e.getKey(), deque);
                });
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger(AttendantSessionTracker.class)
                .warn("AttendantSessionTracker restore failed "
                    + "(continuing with empty tracker): {}", ex.getMessage());
            active.clear();
            history.clear();
        }
    }
}
