package org.wyrdsekai.core.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The night of a household whose write stops the only model it has.
 *
 * <p>Under the single-model profile a being's write needs the card the one server sits on, so
 * while it runs nothing on the node can think. One companion staying asleep through her own write
 * is not enough when the node holds more than one: the others are awake with no model. So the
 * writes wait until every companion on the node is asleep, run one after another, and everyone
 * wakes when the last is over.
 *
 * <p>The first companion to reach the end of her sleep cycle opens the night and the others are
 * asked to sleep. Each arrives here at the end of her own cycle. When all have arrived the writes
 * run in arrival order. A companion who does not arrive within {@link #GATHER} (she is with a
 * person, or slept a moment ago) is not overridden: no write runs that night, and those who came
 * wake again. A companion who arrives after the writes began sleeps until they are over; her own
 * write waits for the next night.
 */
public final class HouseholdNight {

    private static final Logger log = LoggerFactory.getLogger(HouseholdNight.class);

    /** How long the first sleeper waits for the rest of the household before giving the night up. */
    static final Duration GATHER = Duration.ofMinutes(4);

    /** Runs one being's write and returns when it is over. */
    interface Writer {
        void write(String name, String entityId, boolean rehearsal);
    }

    private record Sleeper(String entityId, String name) {}

    private static final class Night {
        final Set<String> expected;
        final Map<String, Sleeper> arrived = new LinkedHashMap<>();
        final CompletableFuture<Void> over = new CompletableFuture<>();
        boolean started;

        Night(Set<String> expected) { this.expected = Set.copyOf(expected); }
    }

    private static final Object LOCK = new Object();
    private static Night current;

    private HouseholdNight() {}

    /**
     * A companion has reached the end of her sleep cycle.
     *
     * @param expected   every companion on this node now, this one included
     * @param askToSleep called for each companion who has not arrived, once, when the night opens
     * @return completes when the household's night is over and she may wake
     */
    public static CompletableFuture<Void> arrive(String entityId, String name, Set<String> expected,
                                                 Consumer<String> askToSleep) {
        Writer writer = (n, id, asRehearsal) -> {
            if (asRehearsal || SleepWeightWrite.enabled()) SleepWeightWrite.runBlocking(n, id, asRehearsal);
        };
        return arrive(entityId, name, expected, askToSleep, writer, SleepWeightWrite::takeRehearsal, GATHER);
    }

    static CompletableFuture<Void> arrive(String entityId, String name, Set<String> expected,
                                          Consumer<String> askToSleep, Writer writer,
                                          BooleanSupplier rehearsal, Duration gather) {
        Night night;
        List<String> toAsk = List.of();
        boolean begin;
        synchronized (LOCK) {
            if (current == null) {
                current = new Night(expected);
                toAsk = expected.stream().filter(id -> !id.equals(entityId)).toList();
                var opened = current;
                CompletableFuture.delayedExecutor(gather.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> giveUpIfNotGathered(opened));
                log.info("The household's night opens with '{}'; waiting for {} other companion(s) to sleep",
                    name, toAsk.size());
            }
            night = current;
            if (night.started) {
                log.info("'{}' fell asleep after the night's writes began; she sleeps until they are over", name);
                return night.over;
            }
            night.arrived.put(entityId, new Sleeper(entityId, name));
            begin = night.arrived.keySet().containsAll(night.expected);
            if (begin) night.started = true;
        }
        for (var id : toAsk) {
            try {
                askToSleep.accept(id);
            } catch (RuntimeException e) {
                log.debug("Could not ask {} to sleep: {}", id, e.toString());
            }
        }
        if (begin) runWrites(night, writer, rehearsal);
        return night.over;
    }

    private static void giveUpIfNotGathered(Night night) {
        List<Sleeper> came;
        synchronized (LOCK) {
            if (night.started || current != night) return;
            came = new ArrayList<>(night.arrived.values());
            current = null;
        }
        var missing = new ArrayList<>(night.expected);
        missing.removeAll(night.arrived.keySet());
        log.info("The household's night did not gather ({} still awake) — no write runs tonight", missing);
        for (var s : came) {
            SleepWeightWrite.markNight(s.entityId(),
                "The night's write waits until the whole household is asleep, and tonight it was not; nothing in me changed.",
                "awake=" + missing);
        }
        night.over.complete(null);
    }

    private static void runWrites(Night night, Writer writer, BooleanSupplier rehearsal) {
        Thread.ofVirtual().name("household-night").start(() -> {
            try {
                var asRehearsal = rehearsal.getAsBoolean();   // one answer for the whole night
                List<Sleeper> sleepers;
                synchronized (LOCK) { sleepers = new ArrayList<>(night.arrived.values()); }
                for (var s : sleepers) {
                    try {
                        writer.write(s.name(), s.entityId(), asRehearsal);
                    } catch (RuntimeException e) {
                        log.warn("The night's write for '{}' errored: {}", s.name(), e.toString());
                    }
                }
            } finally {
                synchronized (LOCK) { if (current == night) current = null; }
                night.over.complete(null);
            }
        });
    }
}
