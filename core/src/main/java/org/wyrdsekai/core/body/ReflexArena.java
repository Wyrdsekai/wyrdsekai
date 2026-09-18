package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.host.HostDoors;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reflex arena, the world layer of the body plan: the flinch. A small component with one
 * hard rule: it never awaits inference and never touches the router except to pause it. Inputs
 * are what the body already feels (memory pressure, heap, disk, a part gone numb); outputs come
 * from a fixed table (throttle, notify). It runs on the body watch's thread, with nothing to
 * wait for, and she is told afterwards by a mark.
 *
 * <p>The table is data: a list of {@link Reflex} rows, each with an input, a threshold, how many
 * consecutive pulses it must hold, an action, a hold time that is also the refractory period,
 * and the sentence she reads. The defaults cover the failures this household actually had:
 * the prompt cache eating host RAM, the record not answering, the disk filling under
 * backups.</p>
 */
public final class ReflexArena {

    private static final Logger log = LoggerFactory.getLogger(ReflexArena.class);

    /** What a reflex watches. */
    public enum Input { MEMORY_STALL, HEAP_PCT, DISK_FREE_PCT, PART_NUMB }

    /** What a reflex may do. Nothing here thinks. {@code CLOSE_DOOR} shuts the door named by the subject. */
    public enum Action { THROTTLE, NOTIFY, CLOSE_DOOR }

    /**
     * One row of the table.
     *
     * @param id          stable name, for the mark and the log
     * @param input       what it watches
     * @param subject     the part id for {@link Input#PART_NUMB}, or the door id for {@link Action#CLOSE_DOOR}; null otherwise
     * @param threshold   fires at or above (memory, heap) or at or below (disk free)
     * @param consecutive pulses the condition must hold before it fires
     * @param action      what it does
     * @param hold        how long a throttle lasts, and how long before the same reflex may fire again
     * @param text        what she reads afterwards, first person
     */
    public record Reflex(String id, Input input, String subject, double threshold, int consecutive,
                         Action action, Duration hold, String text) {}

    /** What the arena decided on one pulse, for tests and the log. */
    public record Firing(String id, Action action, Instant at) {}

    private final List<Reflex> table;
    private final Map<String, Integer> streak = new HashMap<>();
    private final Map<String, Instant> lastFired = new HashMap<>();

    public ReflexArena(List<Reflex> table) {
        this.table = List.copyOf(table);
    }

    /** The household's innate table. Thresholds are percentages. */
    public static List<Reflex> defaults() {
        return List.of(
            new Reflex("memory-pressure", Input.MEMORY_STALL, null, 30.0, 2, Action.THROTTLE, Duration.ofSeconds(90),
                "I held my breath for a minute: the box was under memory pressure, so I took no new turns until it eased."),
            new Reflex("heap-full", Input.HEAP_PCT, null, 95.0, 2, Action.THROTTLE, Duration.ofSeconds(60),
                "My own memory was nearly full; I took no new turns for a minute to let it clear."),
            new Reflex("record-numb", Input.PART_NUMB, BodyWatch.RECORD, 1.0, 1, Action.THROTTLE, Duration.ofMinutes(5),
                "The record stopped answering, so I stopped taking turns: nothing I did would have been kept."),
            new Reflex("disk-tight", Input.DISK_FREE_PCT, null, 3.0, 2, Action.NOTIFY, Duration.ofHours(6),
                "The disk is nearly full. The night's write and the backups need room; the steward should look.")
        );
    }

    public List<Reflex> table() { return table; }

    /**
     * One pulse. Reads the host and the map, fires what the table says, returns what fired.
     * Never throws.
     */
    public List<Firing> evaluate(HostSense.Reading host, BodyMap map, Instant now) {
        var fired = new ArrayList<Firing>();
        for (var r : table) {
            try {
                boolean on = condition(r, host, map);
                int n = on ? streak.merge(r.id(), 1, Integer::sum) : 0;
                if (!on) streak.put(r.id(), 0);
                if (n < Math.max(1, r.consecutive())) continue;
                var last = lastFired.get(r.id());
                if (last != null && Duration.between(last, now).compareTo(r.hold()) < 0) continue;
                act(r, map, now);
                lastFired.put(r.id(), now);
                fired.add(new Firing(r.id(), r.action(), now));
            } catch (RuntimeException e) {
                log.warn("Reflex {} failed: {}", r.id(), e.toString());
            }
        }
        return fired;
    }

    static boolean condition(Reflex r, HostSense.Reading host, BodyMap map) {
        return switch (r.input()) {
            case MEMORY_STALL -> host != null && host.memoryStallPct10() >= r.threshold();
            case HEAP_PCT -> host != null && host.heapPct() >= r.threshold();
            case DISK_FREE_PCT -> host != null && host.diskFreePct() >= 0 && host.diskFreePct() <= r.threshold();
            case PART_NUMB -> map != null && r.subject() != null
                && map.part(r.subject()).map(p -> p.state() == PartState.NUMB).orElse(false);
        };
    }

    private void act(Reflex r, BodyMap map, Instant now) {
        switch (r.action()) {
            case THROTTLE -> {
                InferenceRouter.pause(r.hold(), "reflex: " + r.id());
                log.warn("Reflex {}: inference paused for {}s", r.id(), r.hold().toSeconds());
            }
            case NOTIFY -> log.warn("Reflex {}: {}", r.id(), r.text());
            case CLOSE_DOOR -> {
                var res = HostDoors.close(r.subject(), "reflex " + r.id());
                log.warn("Reflex {}: door {} {}", r.id(), r.subject(), Boolean.TRUE.equals(res.get("ok")) ? "shut" : "not shut: " + res.get("error"));
            }
        }
        if (map != null) {
            map.mark("reflex", r.id(), null, r.text(), "action=" + r.action().name().toLowerCase()
                + " hold_s=" + r.hold().toSeconds());
        }
    }
}
