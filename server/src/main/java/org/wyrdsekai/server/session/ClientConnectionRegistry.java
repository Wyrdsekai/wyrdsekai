package org.wyrdsekai.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-node registry of live {@link ClientConnection} instances. Populated
 * at login by each transport and consulted by federation and notification
 * paths that need to reach a client by {@code playerId}.
 * <p>
 */
public final class ClientConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientConnectionRegistry.class);

    private final ConcurrentHashMap<String, ClientConnection> byPlayerId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClientConnection> bySessionId = new ConcurrentHashMap<>();

    // Last-activity timestamp per session, for the idle reaper. A session is
    // "quiet" if no input has touched it within the idle window; the reaper
    // closes such sessions so the multi-surface model doesn't accumulate
    // forgotten ghost channels (the manual replacement is `sessions kill`).
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final long idleReapMillis = resolveIdleReapMillis();
    private volatile ScheduledExecutorService reaper;

    private static long resolveIdleReapMillis() {
        // Minutes; 0 (or unset) disables reaping. Generous default so readers
        // aren't surprised — only truly forgotten channels get swept.
        var raw = System.getenv("WYRDSEKAI_SESSION_IDLE_REAP_MINUTES");
        long minutes = 240;
        if (raw != null && !raw.isBlank()) {
            try { minutes = Long.parseLong(raw.trim()); } catch (NumberFormatException ignored) {}
        }
        return minutes <= 0 ? 0 : minutes * 60_000L;
    }

    public void register(ClientConnection c) {
        if (c == null || c.sessionId() == null || c.playerId() == null) return;
        bySessionId.put(c.sessionId(), c);
        lastSeen.put(c.sessionId(), now());
        ensureReaper();
        // Multi-surface presence: one account may hold several live sessions at
        // once (CLI + SSH + web + phone), all backing ONE in-world entity. We do
        // NOT boot a prior session. Duplicates can't appear because the entity is
        // keyed by playerId (one entity per account) and the room fans events out
        // per connection (RoomActor.subscribers is keyed by actor-ref). byPlayerId
        // holds the most-recent connection as the default reach target for
        // findByPlayerId; every session stays in bySessionId.
        //
        // Previously this did "link-takeover" — a new login kicked the old session
        // ("two Masumis" defence) — which forced one-surface-at-a-time. That was a
        // blunt fix for an entity-dup bug already solved by one-entity-per-player,
        // so it's removed in favour of true multi-surface.
        byPlayerId.put(c.playerId(), c);
        log.debug("ClientConnectionRegistry: registered {} session={} player={}",
            c.getClass().getSimpleName(), c.sessionId(), c.playerId());
    }

    public void unregister(String sessionId) {
        if (sessionId == null) return;
        lastSeen.remove(sessionId);
        var c = bySessionId.remove(sessionId);
        if (c != null) {
            // If this was the primary connection for its player, promote another
            // still-live session (if any) so findByPlayerId keeps reaching the
            // account while the user remains present on a different surface.
            if (byPlayerId.remove(c.playerId(), c)) {
                for (var other : bySessionId.values()) {
                    if (c.playerId().equals(other.playerId())) {
                        byPlayerId.put(c.playerId(), other);
                        break;
                    }
                }
            }
            log.debug("ClientConnectionRegistry: unregistered session={} player={}",
                sessionId, c.playerId());
        }
    }

    /**
     * True if a live connection <em>other than</em> {@code excludeSessionId} is
     * registered for {@code playerId}. Lets a per-session disconnect suppress
     * the room-departure broadcast while the same account is still present
     * through another surface (e.g. CLI + SSH at once) — otherwise quitting one
     * surface emits "X heads disconnect." into the room even though X is still
     * here on the other. Scans {@code bySessionId} so it counts every surface,
     * not just the link-takeover winner held in {@code byPlayerId}.
     */
    public boolean hasOtherLiveSession(String playerId, String excludeSessionId) {
        if (playerId == null) return false;
        for (var c : bySessionId.values()) {
            if (playerId.equals(c.playerId())
                    && (excludeSessionId == null || !excludeSessionId.equals(c.sessionId()))) {
                return true;
            }
        }
        return false;
    }

    /** Every live connection for {@code playerId}, across all surfaces. */
    public List<ClientConnection> sessionsFor(String playerId) {
        var out = new ArrayList<ClientConnection>();
        if (playerId == null) return out;
        for (var c : bySessionId.values()) {
            if (playerId.equals(c.playerId())) out.add(c);
        }
        return out;
    }

    /** Number of live connections (surfaces) the account currently holds. */
    public int liveSessionCount(String playerId) {
        if (playerId == null) return 0;
        int n = 0;
        for (var c : bySessionId.values()) {
            if (playerId.equals(c.playerId())) n++;
        }
        return n;
    }

    /**
     * Close every live connection for {@code playerId} except
     * {@code keepSessionId} (the caller's own session, which ends itself
     * through its normal cleanup path). Backs the {@code logout}/{@code quitall}
     * verbs: drop the account's other surfaces; the caller then exits last so
     * the room departure fires exactly once. Returns the number disconnected.
     */
    public int disconnectOthers(String playerId, String keepSessionId, String reason) {
        if (playerId == null) return 0;
        int n = 0;
        for (var c : sessionsFor(playerId)) {
            if (keepSessionId != null && keepSessionId.equals(c.sessionId())) continue;
            try {
                c.disconnect(reason);
            } catch (RuntimeException ex) {
                log.debug("disconnectOthers: {} failed to close session={}: {}",
                    c.getClass().getSimpleName(), c.sessionId(), ex.getMessage());
            }
            n++;
        }
        return n;
    }

    public Optional<ClientConnection> findByPlayerId(String playerId) {
        if (playerId == null) return Optional.empty();
        return Optional.ofNullable(byPlayerId.get(playerId));
    }

    public Optional<ClientConnection> findBySessionId(String sessionId) {
        if (sessionId == null) return Optional.empty();
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    public Collection<ClientConnection> all() {
        return bySessionId.values();
    }

    public int size() {
        return bySessionId.size();
    }

    /** Mark a session as active so the idle reaper leaves it alone. */
    public void touch(String sessionId) {
        if (sessionId != null && bySessionId.containsKey(sessionId)) {
            lastSeen.put(sessionId, now());
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }

    /** Lazily start the idle reaper once the first session registers. */
    private void ensureReaper() {
        if (idleReapMillis <= 0 || reaper != null) return;
        synchronized (this) {
            if (reaper != null) return;
            var exec = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "session-idle-reaper");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(60_000L, idleReapMillis / 4);
            exec.scheduleWithFixedDelay(this::reapIdle, period, period, TimeUnit.MILLISECONDS);
            reaper = exec;
        }
    }

    /** Close sessions with no activity within the idle window. */
    void reapIdle() {
        if (idleReapMillis <= 0) return;
        long cutoff = now() - idleReapMillis;
        for (var entry : lastSeen.entrySet()) {
            if (entry.getValue() != null && entry.getValue() < cutoff) {
                var c = bySessionId.get(entry.getKey());
                if (c != null) {
                    log.info("Reaping idle session {} (player {})", c.sessionId(), c.playerId());
                    try { c.disconnect("idle"); } catch (RuntimeException ignored) {}
                }
                // unregister fires from the transport's own close path; drop the
                // timestamp now so we don't re-reap before that lands.
                lastSeen.remove(entry.getKey());
            }
        }
    }
}
