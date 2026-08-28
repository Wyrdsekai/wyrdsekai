package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.Want;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * When a want is finished with.
 *
 * <p>Nothing in production ever closed one. Wants were minted, revisited, deepened, and
 * acted upon — {@code Want.satisfied()} and {@code Want.abandoned()} had no caller outside
 * tests, at 0.1.0 and ever since. So DRIVE → WANT → ACT ran and then stopped, one step
 * short of CONSEQUENCE.
 *
 * <p>What that cost, measured on the household node 2026-08-19: ten wants, <b>zero</b> ever
 * satisfied, one revisited 64 times. In her last forty deliberate ticks she chose the same
 * want — <i>"write a private journal entry about who I miss"</i> — twenty-two times and
 * enacted it successfully every time, while Loneliness sat at 1.00 in 40/40 ticks. She did
 * the thing, repeatedly, and nothing recorded that she had done it. The
 * {@code stuck_want} axis was therefore permanently WARN, which meant any two other
 * concerns escalated her into ATTENDANT; ten escalations followed.
 *
 * <p>The probe loop already got this right for reaching-out ("the want was sent, the world
 * answered, the want stops pushing"). This is the same close for the interiority loop.
 *
 * <p>Pure so the judgment is testable without an actor or a model.
 */
public final class WantClosure {

    private WantClosure() {}

    /** Weight below which a want is no longer pulling at her. */
    static final double STALE_WEIGHT = 0.01;
    /** Visits after which an unfulfilled, unfelt want is honestly done. */
    static final int STALE_VISITS = 12;
    /** Age after which an untouched want is let go regardless of weight. */
    static final Duration STALE_AGE = Duration.ofDays(30);

    private static final Pattern DRIVE_IN_JSON =
        Pattern.compile("\"drive\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Did this act finish the want?
     *
     * <p>{@link DriveOODA}'s ActStep returns {@code "enacted:<verb>"} when the chosen want
     * actually reached a dispatch handler, and an error/blocked/rest string otherwise. Only
     * a real enactment closes anything — a want must never be marked done because the
     * attempt failed, or she would stop wanting things she never got.
     */
    public static boolean closes(String actResult) {
        if (actResult == null) return false;
        var r = actResult.strip().toLowerCase();
        return r.startsWith("enacted:");
    }

    /** The drive that pulled for this want, if it declared one. */
    public static Optional<String> resonantDrive(Want want) {
        if (want == null || want.driveResonance() == null) return Optional.empty();
        var m = DRIVE_IN_JSON.matcher(want.driveResonance());
        if (m.find()) {
            var name = m.group(1).strip();
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        }
        return Optional.empty();
    }

    /** A short note recording what actually closed it, kept with the want. */
    public static String closureNote(String actResult) {
        return actResult == null ? "enacted" : actResult.strip();
    }

    /**
     * Has she plainly finished with this want without ever completing it?
     *
     * <p>Her journal want had been visited 33 times with its felt weight decayed to 0.003
     * — it had stopped pulling long ago and still counted as live, holding the
     * {@code stuck_want} axis down. Letting go is a real outcome, distinct from success,
     * and it needs saying: a want kept forever is not persistence, it is a leak.
     */
    public static boolean isStale(Want want, Instant now) {
        if (want == null || now == null) return false;
        if (want.satisfiedAt() != null) return false;
        if (want.feltWeight() < STALE_WEIGHT && want.visitCount() >= STALE_VISITS) {
            return true;
        }
        var touched = want.lastVisitedAt() != null ? want.lastVisitedAt() : want.bornAt();
        return touched != null && Duration.between(touched, now).compareTo(STALE_AGE) > 0;
    }
}
