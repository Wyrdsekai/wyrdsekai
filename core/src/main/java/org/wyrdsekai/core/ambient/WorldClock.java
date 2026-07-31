package org.wyrdsekai.core.ambient;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.embodiment.AmbientPhase;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Layer 5 — per-zone time-of-day driver.
 *
 * <p>One {@code WorldClock} actor runs per zone. It polls the system clock
 * (or a test-injected {@link Supplier Supplier&lt;Instant&gt;}), maps the
 * current time to an {@link AmbientPhase}, and — when the phase changes —
 * broadcasts a {@link WorldEvent.AmbientChanged} to every room registered
 * in {@link RoomRegistry} via {@link RoomCommand.BroadcastRemoteEvent}.
 *
 * <p>The room-actor side of the wire (already implemented at
 * {@code RoomState.applyEvent}) accepts {@code AmbientChanged} as a perceived
 * event; {@code BeatDetector} already classifies it as an INTRUSION trigger;
 * {@code CompanionActor.recordEmbodimentObservation} already routes it into
 * working memory. This actor just authors the event stream.
 *
 * <p>A static {@code PHASE_BY_ZONE} map lets synchronous callers (the
 * vitality-tick tank coupling, {@code RoomActor.onLookRoom} prose overlay)
 * read the current phase without round-tripping through the actor.
 *
 * <h3>Timing controls</h3>
 * <ul>
 *   <li>{@code dayLength} — total length of one synthetic day. Production:
 *       {@link Duration#ofHours(long)} 24h. Tests pass tiny durations to sweep
 *       all four phases in seconds.</li>
 *   <li>{@code tickInterval} — how often to poll for a phase change. Production:
 *       1 minute (cheap, four-or-fewer transitions per day). Tests: tens of
 *       milliseconds.</li>
 *   <li>{@code clock} — {@code Supplier<Instant>}. Production: {@code Instant::now}.
 *       Tests: a {@link java.util.concurrent.atomic.AtomicReference}-backed
 *       supplier they advance manually.</li>
 * </ul>
 *
 * <h3>Synthetic-day mode</h3>
 * <p>When {@code dayLength} is shorter than 24h (i.e. tests, or a deliberately
 * accelerated demo), {@link AmbientPhase#syntheticPhase} computes the phase
 * from {@code (epochSeconds % dayLength)}. When {@code dayLength == 24h},
 * {@link AmbientPhase#fromInstant} uses the wall-clock hour-of-day in the
 * configured {@link ZoneId}.
 */
public final class WorldClock extends AbstractBehavior<WorldClock.Command> {

    private static final Logger log = LoggerFactory.getLogger(WorldClock.class);

    // --- Static read surface --------------------------------------------------

    /**
     * Cache of zoneId → current phase, populated on every transition. Read
     * synchronously by the renderer + the vitality tank-coupling path. Tests
     * that need to inject a phase without spinning up the actor write here
     * via {@link #setPhaseForTests}.
     */
    private static final ConcurrentMap<String, AmbientPhase> PHASE_BY_ZONE = new ConcurrentHashMap<>();

    /** Read the most-recently-observed phase for the given zone. */
    public static AmbientPhase currentPhase(String zoneId) {
        if (zoneId == null) return null;
        return PHASE_BY_ZONE.get(zoneId);
    }

    /** Read the phase, defaulting to MIDDAY if no clock has run for this zone yet. */
    public static AmbientPhase currentPhaseOrDefault(String zoneId) {
        var p = currentPhase(zoneId);
        return p != null ? p : AmbientPhase.MIDDAY;
    }

    /** Test override — sets the cached phase for a zone without involving the actor. */
    public static void setPhaseForTests(String zoneId, AmbientPhase phase) {
        if (zoneId == null) return;
        if (phase == null) PHASE_BY_ZONE.remove(zoneId);
        else PHASE_BY_ZONE.put(zoneId, phase);
    }

    /** Clear all cached phases — call between test runs. */
    public static void clearAllForTests() {
        PHASE_BY_ZONE.clear();
    }

    // --- Command protocol -----------------------------------------------------

    public sealed interface Command {}

    /** Internal timer tick — re-evaluate phase and broadcast on change. */
    public record Tick() implements Command {}

    /** Force a phase override (tests + future ritual/seasonal scripts). */
    public record OverridePhase(AmbientPhase phase) implements Command {}

    /** Read the actor's current phase — useful for tests that prefer ask over the static cache. */
    public record GetPhase(ActorRef<AmbientPhase> replyTo) implements Command {}

    // --- Construction / Behavior --------------------------------------------

    private final String zoneId;
    private final Supplier<Instant> clock;
    private final Duration dayLength;
    private final ZoneId timeZone;
    private final TimerScheduler<Command> timers;
    /** Time origin used to compute synthetic-day phase position. */
    private final Instant epochOrigin;

    private AmbientPhase currentPhase;

    private static final String TICK_KEY = "world-clock-tick";

    public static Behavior<Command> create(String zoneId) {
        return create(zoneId, Duration.ofHours(24), Duration.ofMinutes(1),
            Instant::now, ZoneId.systemDefault());
    }

    public static Behavior<Command> create(String zoneId, Duration dayLength,
                                            Duration tickInterval,
                                            Supplier<Instant> clock,
                                            ZoneId timeZone) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId required");
        }
        if (dayLength == null || dayLength.isZero() || dayLength.isNegative()) {
            throw new IllegalArgumentException("dayLength must be positive");
        }
        if (tickInterval == null || tickInterval.isZero() || tickInterval.isNegative()) {
            throw new IllegalArgumentException("tickInterval must be positive");
        }
        var resolvedClock = clock != null ? clock : (Supplier<Instant>) Instant::now;
        var resolvedZone = timeZone != null ? timeZone : ZoneId.systemDefault();
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(t ->
                new WorldClock(ctx, t, zoneId, resolvedClock, dayLength, resolvedZone, tickInterval)));
    }

    private WorldClock(ActorContext<Command> context, TimerScheduler<Command> timers,
                       String zoneId, Supplier<Instant> clock, Duration dayLength,
                       ZoneId timeZone, Duration tickInterval) {
        super(context);
        this.zoneId = zoneId;
        this.clock = clock;
        this.dayLength = dayLength;
        this.timeZone = timeZone;
        this.timers = timers;
        this.epochOrigin = clock.get();
        // Compute initial phase synchronously so callers reading PHASE_BY_ZONE
        // right after spawn see a value.
        this.currentPhase = computePhase(epochOrigin);
        PHASE_BY_ZONE.put(zoneId, currentPhase);
        log.info("WorldClock zone={} initial phase={} dayLength={}s tick={}s",
            zoneId, currentPhase, dayLength.toSeconds(), tickInterval.toSeconds());
        // Drive ticks at fixed delay (production: 1 minute). The first tick
        // fires after tickInterval — tests that need an immediate transition
        // send a Tick() message directly via the actor ref.
        timers.startTimerWithFixedDelay(TICK_KEY, new Tick(), tickInterval);
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Tick.class, this::onTick)
            .onMessage(OverridePhase.class, this::onOverridePhase)
            .onMessage(GetPhase.class, this::onGetPhase)
            .build();
    }

    private Behavior<Command> onTick(Tick t) {
        var now = clock.get();
        var nextPhase = computePhase(now);
        if (nextPhase != currentPhase) {
            applyTransition(nextPhase, now);
        }
        return this;
    }

    private Behavior<Command> onOverridePhase(OverridePhase op) {
        if (op.phase() != null && op.phase() != currentPhase) {
            applyTransition(op.phase(), clock.get());
        }
        return this;
    }

    private Behavior<Command> onGetPhase(GetPhase req) {
        req.replyTo().tell(currentPhase);
        return this;
    }

    /** Compute the phase from {@code now}, honoring the synthetic-day flag. */
    private AmbientPhase computePhase(Instant now) {
        if (dayLength.equals(Duration.ofHours(24))) {
            return AmbientPhase.fromInstant(now, timeZone);
        }
        var elapsed = Math.max(0L, now.getEpochSecond() - epochOrigin.getEpochSecond());
        return AmbientPhase.syntheticPhase(elapsed, dayLength.toSeconds());
    }

    /** Apply a phase transition: update cache, broadcast {@code AmbientChanged} to every room. */
    private void applyTransition(AmbientPhase next, Instant at) {
        var previous = currentPhase;
        currentPhase = next;
        PHASE_BY_ZONE.put(zoneId, next);
        log.info("WorldClock zone={} phase transition {} → {}", zoneId, previous, next);
        broadcastAmbient(previous, next, at);
    }

    /**
     * Broadcast an {@link WorldEvent.AmbientChanged} to every room registered
     * in {@link RoomRegistry}. The {@code descriptor} is rendered per-room
     * via {@link AmbientRenderer#descriptor} so each room narrates its own
     * phase change in its own voice. Locale defaults to English here; the
     * room's own look/scene-open path re-renders in the viewer's locale.
     */
    private void broadcastAmbient(AmbientPhase previous, AmbientPhase next, Instant at) {
        var registry = RoomRegistry.get();
        var roomIds = registry.roomIds();
        var prevKey = previous == null ? null : previous.key();
        var nextKey = next.key();
        for (var roomId : roomIds) {
            var ref = registry.ref(roomId);
            if (ref == null) continue;
            // Use English here — viewer-locale rendering happens at look() time.
            // Engine-side fallback is fine; the i18n keys exist regardless.
            var descriptor = AmbientRenderer.descriptor(roomId, next, "en");
            var event = new WorldEvent.AmbientChanged(roomId, at, "phase",
                prevKey, nextKey, descriptor);
            ref.tell(new RoomCommand.BroadcastRemoteEvent(event));
        }
        log.debug("WorldClock zone={} broadcast AmbientChanged to {} rooms ({} → {})",
            zoneId, roomIds.size(), prevKey, nextKey);
    }
}
