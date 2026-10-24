package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.BackendWindows;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * How many coding jobs may run at once when they think on the node's own brain.
 *
 * <p>A coding job is a long conversation with a model. When that model is the
 * household brain — the same server the companions speak through — every job
 * holds one of its slots for as long as it runs, and the slots share one
 * context pool. On 2026-10-23 two companions each sent a job within two
 * minutes; the jobs held both slots for a quarter of an hour, and in that time
 * one companion's own time and an item were refused with "context size has
 * been exceeded". The brain had no room left for the house it serves.
 *
 * <p>So the workshop has lanes: as many as the brain has slots, less the one
 * kept for the companions, and never fewer than one. A job that finds the
 * lanes full waits its turn (first come, first served); the wait is not
 * counted against its wall-clock cap, which starts when the job starts. A
 * job whose drive is elsewhere — another machine's model — does not queue
 * here, since it takes nothing from the brain.
 */
public final class WorkshopLane {

    private static final Logger log = LoggerFactory.getLogger(WorkshopLane.class);

    /** One job in a lane: what it is and whose it is, for the one waiting behind it. */
    public record Occupant(String taskId, String companionDid, long sinceMillis) {}

    private static final Object LOCK = new Object();
    private static final Deque<Occupant> RUNNING = new ArrayDeque<>();
    private static final Deque<String> WAITING = new ArrayDeque<>();
    /** Lanes when the brain's slot count is unknown: one, the safe reading. */
    static final int LANES_WHEN_UNKNOWN = 1;

    private WorkshopLane() {}

    /** True when jobs on this drive share the brain the companions speak through. */
    public static boolean sharesTheBrain(String driveUrl) {
        if (driveUrl == null || driveUrl.isBlank()) return false;
        return BackendWindows.isThisMachine(driveUrl) || BackendWindows.nameServing(driveUrl) != null;
    }

    /** How many jobs may run at once on this drive: the brain's slots less one for the house. */
    public static int lanes(String driveUrl) {
        var backend = BackendWindows.nameServing(driveUrl);
        int slots = backend == null ? 0 : BackendWindows.slots(backend);
        return slots <= 0 ? LANES_WHEN_UNKNOWN : Math.max(1, slots - 1);
    }

    /** The job that holds a lane right now, if any — for the companion to name while she waits. */
    public static Optional<Occupant> whoHoldsALane() {
        synchronized (LOCK) {
            return Optional.ofNullable(RUNNING.peekFirst());
        }
    }

    /** True when a new job on this drive would have to wait. */
    public static boolean isFull(String driveUrl) {
        if (!sharesTheBrain(driveUrl)) return false;
        synchronized (LOCK) {
            return RUNNING.size() + WAITING.size() >= lanes(driveUrl);
        }
    }

    /**
     * Take a lane for {@code taskId}, waiting while the lanes are full. Returns how long it
     * waited. A drive that does not share the brain gets a lane at once.
     */
    public static Duration enter(String driveUrl, String taskId, String companionDid)
            throws InterruptedException {
        if (!sharesTheBrain(driveUrl)) return Duration.ZERO;
        long started = System.currentTimeMillis();
        synchronized (LOCK) {
            WAITING.addLast(taskId);
            boolean said = false;
            while (RUNNING.size() >= lanes(driveUrl) || !taskId.equals(WAITING.peekFirst())) {
                if (!said) {
                    var ahead = RUNNING.peekFirst();
                    log.info("Workshop lanes full ({} of {}): job {} waits behind {}",
                        RUNNING.size(), lanes(driveUrl), taskId,
                        ahead == null ? "the queue" : ahead.taskId());
                    said = true;
                }
                LOCK.wait(1_000);
            }
            WAITING.removeFirst();
            RUNNING.addLast(new Occupant(taskId, companionDid, System.currentTimeMillis()));
            LOCK.notifyAll();
        }
        var waited = Duration.ofMillis(System.currentTimeMillis() - started);
        if (waited.toSeconds() >= 1) {
            log.info("Workshop lane taken by job {} after waiting {} s", taskId, waited.toSeconds());
        }
        return waited;
    }

    /** Give the lane back. Safe to call for a job that never took one. */
    public static void leave(String taskId) {
        synchronized (LOCK) {
            RUNNING.removeIf(o -> o.taskId().equals(taskId));
            WAITING.remove(taskId);
            LOCK.notifyAll();
        }
    }

    /** Tests: every lane empty. */
    static void resetForTests() {
        synchronized (LOCK) {
            RUNNING.clear();
            WAITING.clear();
            LOCK.notifyAll();
        }
    }
}
