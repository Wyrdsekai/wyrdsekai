package org.wyrdsekai.core.familiar;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A bunshin task that survives primary-user sleep sessions.
 *
 * <p>. Bunshin are not tied to a user session — if the
 * user goes to bed, the bunshin keeps working (subject to tanks and
 * priority). A persistent task record captures the goal, completion
 * criteria, cadence, and wall-clock ceiling so that the runtime can
 * journal progress and resume after primary sleep.</p>
 *
 * <p>Unlike a transient {@link BunshinReport} (which is the <em>result</em>
 * of a dispatch), this record is the <em>ongoing work description</em>
 * plus a running journal of cadence-based progress notes.</p>
 *
 * @param id                  opaque identifier
 * @param primaryAgentDid     owning agent
 * @param goal                natural-language goal string
 * @param completionCheck     how the bunshin knows it is done
 * @param stopOnHuman         auto-return when primary user returns?
 * @param maxWallClock        hard ceiling regardless of other checks
 * @param reportCadence       how often to journal progress (§18.3)
 * @param dispatchedAt        when this task was issued
 * @param lastCheckInAt       most recent progress journal timestamp
 * @param progressNotes       cadence-written narrative; bounded by {@link #MAX_NOTES}
 * @param status              current lifecycle state
 * @param partialResult       last-known partial output, if any
 */
public record PersistentBunshinTask(
    String id,
    String primaryAgentDid,
    String goal,
    String completionCheck,
    boolean stopOnHuman,
    Duration maxWallClock,
    Duration reportCadence,
    Instant dispatchedAt,
    Optional<Instant> lastCheckInAt,
    List<ProgressNote> progressNotes,
    Status status,
    Optional<String> partialResult
) {

    /** Bounded journal length — older notes elide. */
    public static final int MAX_NOTES = 100;
    public static final Duration DEFAULT_REPORT_CADENCE = Duration.ofMinutes(10);
    public static final Duration DEFAULT_MAX_WALL_CLOCK = Duration.ofHours(8);

    public enum Status {
        RUNNING,
        YIELDED,             // primary active — will resume on quiescence
        COMPLETED,           // terminated naturally
        EXPIRED,             // wall-clock ceiling hit
        CANCELLED,           // explicit cancel
        FAILED               // inference error or other non-recoverable
    }

    public record ProgressNote(
        Instant at,
        String content
    ) {
        public ProgressNote {
            if (at == null) at = Instant.now();
            if (content == null) content = "";
        }
    }

    public PersistentBunshinTask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (primaryAgentDid == null || primaryAgentDid.isBlank()) {
            throw new IllegalArgumentException("primaryAgentDid required");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal required");
        }
        if (completionCheck == null) completionCheck = "";
        if (maxWallClock == null) maxWallClock = DEFAULT_MAX_WALL_CLOCK;
        if (reportCadence == null) reportCadence = DEFAULT_REPORT_CADENCE;
        if (dispatchedAt == null) dispatchedAt = Instant.now();
        if (lastCheckInAt == null) lastCheckInAt = Optional.empty();
        progressNotes = progressNotes == null ? List.of() : List.copyOf(progressNotes);
        if (status == null) status = Status.RUNNING;
        if (partialResult == null) partialResult = Optional.empty();
    }

    /** Create a fresh task. */
    public static PersistentBunshinTask dispatch(
            String primaryAgentDid, String goal, String completionCheck,
            boolean stopOnHuman, Duration maxWallClock, Duration reportCadence) {
        return new PersistentBunshinTask(
            UUID.randomUUID().toString(),
            primaryAgentDid, goal, completionCheck,
            stopOnHuman,
            maxWallClock == null ? DEFAULT_MAX_WALL_CLOCK : maxWallClock,
            reportCadence == null ? DEFAULT_REPORT_CADENCE : reportCadence,
            Instant.now(),
            Optional.empty(),
            List.of(),
            Status.RUNNING,
            Optional.empty());
    }

    // ── State transitions — each returns a new record ──────────────────────

    /** Append a progress note. Bounded at {@link #MAX_NOTES}; oldest elide. */
    public PersistentBunshinTask withProgressNote(String content) {
        var note = new ProgressNote(Instant.now(), content);
        var next = new ArrayList<>(progressNotes);
        next.add(note);
        while (next.size() > MAX_NOTES) next.remove(0);
        return new PersistentBunshinTask(id, primaryAgentDid, goal, completionCheck,
            stopOnHuman, maxWallClock, reportCadence, dispatchedAt,
            Optional.of(note.at()),
            List.copyOf(next), status, partialResult);
    }

    /** Change the lifecycle status. */
    public PersistentBunshinTask withStatus(Status newStatus) {
        return new PersistentBunshinTask(id, primaryAgentDid, goal, completionCheck,
            stopOnHuman, maxWallClock, reportCadence, dispatchedAt,
            lastCheckInAt, progressNotes,
            newStatus == null ? status : newStatus,
            partialResult);
    }

    /** Replace the partial-result text. */
    public PersistentBunshinTask withPartialResult(String text) {
        return new PersistentBunshinTask(id, primaryAgentDid, goal, completionCheck,
            stopOnHuman, maxWallClock, reportCadence, dispatchedAt,
            lastCheckInAt, progressNotes, status,
            text == null ? Optional.empty() : Optional.of(text));
    }

    // ── Queries ────────────────────────────────────────────────────────────

    /** Has the max-wall-clock budget been exceeded given the supplied clock? */
    public boolean isExpired(Instant now) {
        return now.isAfter(dispatchedAt.plus(maxWallClock));
    }

    /** Is a new cadence report due given {@code now}? */
    public boolean isReportDue(Instant now) {
        var last = lastCheckInAt.orElse(dispatchedAt);
        return now.isAfter(last.plus(reportCadence));
    }

    public boolean isAlive() {
        return status == Status.RUNNING || status == Status.YIELDED;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED
            || status == Status.EXPIRED
            || status == Status.CANCELLED
            || status == Status.FAILED;
    }

    /** How long this task has been running. */
    public Duration age(Instant now) {
        return Duration.between(dispatchedAt, now);
    }

    /** Seconds until wall-clock expiry; zero if already expired. */
    public long secondsUntilExpiry(Instant now) {
        var deadline = dispatchedAt.plus(maxWallClock);
        if (!now.isBefore(deadline)) return 0;
        return Duration.between(now, deadline).toSeconds();
    }
}
