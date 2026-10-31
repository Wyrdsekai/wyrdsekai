package org.wyrdsekai.server.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * A person's place, held for a short while after their connection drops without their choosing:
 * a reload, a network blip, a phone moving between networks. Until they are back or the hold ends
 * they stay in the room, nothing is announced, and their session keeps what is said to them, so
 * the connection that comes back takes up the same session and is told what it missed.
 *
 * <p>A close the person or the server chose is not held: a client's goodbye (1000) and every close
 * the server makes itself (4000–4999: quit, logout, a sign-out, a limit, the idle sweep).</p>
 */
public final class HeldPlaces {

    /** A held session: whose it is, the client it was open on, and since when. */
    public record Place(String sessionId, String playerId, String client, Instant since) {}

    private final Map<String, Place> held = new ConcurrentHashMap<>();
    private final BiConsumer<Duration, Runnable> later;
    private volatile Duration hold;

    /** {@code later} runs a task after a delay (the node's scheduler; a test drives it by hand). */
    public HeldPlaces(Duration hold, BiConsumer<Duration, Runnable> later) {
        this.hold = hold == null || hold.isNegative() ? Duration.ZERO : hold;
        this.later = later;
    }

    /** Whether a close with this code may be held: not a client's goodbye, not one of the server's own. */
    public static boolean mayHold(int closeCode) {
        return closeCode != 1000 && (closeCode < 4000 || closeCode >= 5000);
    }

    public Duration hold() { return hold; }

    public void setHold(Duration hold) {
        this.hold = hold == null || hold.isNegative() ? Duration.ZERO : hold;
    }

    /** Holds this session's place; {@code leave} runs if no connection takes it up in time. */
    public void hold(String sessionId, String playerId, String client, Runnable leave) {
        var place = new Place(sessionId, playerId, client, Instant.now());
        held.put(sessionId, place);
        later.accept(hold, () -> {
            if (held.remove(sessionId, place)) leave.run();
        });
    }

    /** Takes up this held session, when it is held; the caller now owns it. */
    public Optional<Place> takeUp(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : held.remove(sessionId));
    }

    /**
     * For a client that names no session of its own: the newest place held for the same person on
     * the same kind of client, taken up.
     */
    public Optional<Place> takeUpFor(String playerId, String client) {
        var mine = held.values().stream()
            .filter(p -> p.playerId().equals(playerId) && Objects.equals(p.client(), client))
            .sorted(Comparator.comparing(Place::since).reversed())
            .toList();
        for (var p : mine) {
            if (held.remove(p.sessionId(), p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Ends a hold now (a logout or a kill from another surface): true when there was one; the caller leaves. */
    public boolean endNow(String sessionId) {
        return sessionId != null && held.remove(sessionId) != null;
    }

    public boolean isHeld(String sessionId) {
        return sessionId != null && held.containsKey(sessionId);
    }
}
