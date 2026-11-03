package org.wyrdsekai.core.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.time.Instant;

/**
 * A quiet moment for the self-updater, made rather than waited for. The companions' own time makes
 * a model call every few minutes through the night: on the household node the longest stretch
 * between 03:00 and 05:00 with none was 8 to 12 minutes, so "ten minutes with nothing in flight"
 * opened for a minute or two a night at most, and a check every six hours almost never met it.
 *
 * <p>Now: no person has spoken to a companion for {@code personQuiet}, and nothing that must not be
 * broken is running (a sleep, a night's write, a coding task, a backup); then her own time is held,
 * as a stop or a move holds it, and the turns in flight finish. When nothing has run for
 * {@code settle}, the moment is reached and the hold stays: the update restarts the node. A person
 * speaking, or {@code most} passing, lets the hold go and the moment is not reached.</p>
 */
public final class QuietMoment {

    private static final Logger log = LoggerFactory.getLogger(QuietMoment.class);
    static final String HOLD_REASON = "an update is about to be installed";

    /** How the wait sleeps between looks; a test replaces it. */
    @FunctionalInterface
    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    private QuietMoment() {}

    public static boolean reach(Duration personQuiet, Duration settle, Duration most) {
        return reach(personQuiet, settle, most, d -> Thread.sleep(d.toMillis()));
    }

    static boolean reach(Duration personQuiet, Duration settle, Duration most, Sleeper sleeper) {
        if (!ActivityGauge.personQuietFor(personQuiet)) return false;
        if (!ActivityGauge.nothingUnbroken()) return false;
        InferenceRouter.holdOwnTime(most.plus(Duration.ofMinutes(2)), HOLD_REASON);
        var deadline = Instant.now().plus(most);
        try {
            while (Instant.now().isBefore(deadline)) {
                if (!ActivityGauge.personQuietFor(personQuiet) || !ActivityGauge.nothingUnbroken()) break;
                if (ActivityGauge.idleFor(settle)) {
                    log.info("[self-update] a quiet moment: her own time is held and nothing has run for {}", settle);
                    return true;
                }
                sleeper.sleep(Duration.ofSeconds(5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        InferenceRouter.releaseOwnTimeHeldFor(HOLD_REASON);
        log.info("[self-update] no quiet moment within {} (a person spoke, or work went on); trying again at the next check", most);
        return false;
    }
}
