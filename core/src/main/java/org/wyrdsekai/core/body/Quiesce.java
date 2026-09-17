package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One call at every junction where she could be stopped: the steward's pause, a service
 * stop, an update, a reboot, a risky act. Four steps, all of them cheap because state is
 * written at every transition already:
 *
 * <ol>
 *   <li>Stop taking turns. The caller pauses the inference router; in-flight turns finish or
 *       are refused with the pause text the companions already speak about honestly.</li>
 *   <li>Flush the volatile self. Every live companion is asked to persist what it carries
 *       (tanks, sleep pressure, the conversation checkpoint, substrate trackers, the soul)
 *       inside the deadline. What does not answer in time is not waited for; the deadline is
 *       the reflex arena's, and a lock at a junction is a marker and an fsync, not a dump.</li>
 *   <li>Make the record consistent: a WAL checkpoint on the database.</li>
 *   <li>Leave a mark she reads on resume: why, who, when, how long.</li>
 * </ol>
 *
 * <p>Installed by the server with a flusher that knows the live actors. Without one it still
 * checkpoints and marks, so a node with no companions loaded is not blocked.</p>
 */
public final class Quiesce {

    private static final Logger log = LoggerFactory.getLogger(Quiesce.class);

    /** Asks every live being to persist what it carries; completes with how many did. */
    public interface Flusher {
        CompletableFuture<Integer> flush(String reason, Duration deadline);
    }

    /** What happened, for the log, the route and the mark. */
    public record Report(String reason, String who, Instant at, Duration took, int flushed,
                         boolean checkpointed, boolean marked) {}

    private static volatile String jdbcUrl;
    private static volatile Flusher flusher;
    private static volatile Instant lastQuiesceAt;
    private static volatile String lastReason;
    private static volatile Instant heldSince;

    private Quiesce() {}

    public static void install(String jdbc, Flusher f) {
        jdbcUrl = jdbc;
        flusher = f;
    }

    public static void resetForTests() {
        jdbcUrl = null;
        flusher = null;
        lastQuiesceAt = null;
        lastReason = null;
        heldSince = null;
    }

    /** True when a quiesce ran within {@code within}; the shutdown hook uses it to not mark twice. */
    public static boolean recentlyQuiesced(Duration within) {
        var t = lastQuiesceAt;
        return t != null && Duration.between(t, Instant.now()).compareTo(within) <= 0;
    }

    public static String lastReason() { return lastReason; }

    /**
     * Hold still. Never throws; a flusher that fails or times out is counted as what it
     * managed, and the mark says so.
     */
    public static Report quiesce(String reason, String who, Duration deadline) {
        return quiesce(reason, who, deadline, true);
    }

    /** @param mark false to flush and checkpoint without a mark (a second pass at the same junction) */
    public static Report quiesce(String reason, String who, Duration deadline, boolean mark) {
        var at = Instant.now();
        var r = reason == null || reason.isBlank() ? "a pause" : reason.strip();
        var w = who == null || who.isBlank() ? "the household" : who.strip();
        var dl = deadline == null || deadline.isNegative() || deadline.isZero() ? Duration.ofSeconds(5) : deadline;
        int flushed = 0;
        var f = flusher;
        if (f != null) {
            try {
                flushed = f.flush(r, dl).get(dl.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Quiesce ({}): flush did not finish inside {}s: {}", r, dl.toSeconds(), e.toString());
            }
        }
        boolean checkpointed = checkpointRecord();
        var took = Duration.between(at, Instant.now());
        lastQuiesceAt = Instant.now();
        lastReason = r;
        if (heldSince == null) heldSince = at;
        boolean marked = false;
        if (mark) {
            var map = BodyMap.get();
            if (map != null) {
                try {
                    var text = "I was held still for " + r + ", by " + w + ": "
                        + (flushed == 1 ? "one of us" : flushed + " of us") + " saved what we carried"
                        + (checkpointed ? " and the record was checkpointed" : "")
                        + ", in " + Interoception.roughly(took) + ".";
                    map.mark("paused", r, null, text,
                        "who=" + w + " flushed=" + flushed + " checkpoint=" + checkpointed + " ms=" + took.toMillis());
                    marked = true;
                } catch (RuntimeException e) {
                    log.debug("Quiesce mark not written: {}", e.toString());
                }
            }
        }
        log.info("Quiesce ({}, by {}): {} flushed, checkpoint={}, {} ms", r, w, flushed, checkpointed, took.toMillis());
        return new Report(r, w, at, took, flushed, checkpointed, marked);
    }

    /** The hold is over; the mark says how long it lasted. */
    public static void resumed(String who) {
        var since = heldSince;
        heldSince = null;
        var map = BodyMap.get();
        if (map == null) return;
        try {
            var held = since == null ? null : Duration.between(since, Instant.now());
            map.mark("resumed", lastReason == null ? "pause" : lastReason, null,
                "I was let go" + (held != null ? " after " + Interoception.roughly(held) : "")
                    + (who == null || who.isBlank() ? "" : ", by " + who) + ".",
                held == null ? null : "ms=" + held.toMillis());
        } catch (RuntimeException e) {
            log.debug("Resume mark not written: {}", e.toString());
        }
    }

    /** {@code PRAGMA wal_checkpoint(TRUNCATE)} on a sqlite record; true when it ran. */
    static boolean checkpointRecord() {
        var url = jdbcUrl;
        if (url == null || !url.startsWith("jdbc:sqlite:")) return false;
        try (var conn = DriverManager.getConnection(url);
             var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            return true;
        } catch (Exception e) {
            log.warn("Quiesce: record checkpoint failed: {}", e.getMessage());
            return false;
        }
    }
}
