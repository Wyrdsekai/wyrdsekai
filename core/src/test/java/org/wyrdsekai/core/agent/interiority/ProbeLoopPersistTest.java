package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.ProbeLoop.Verdict;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the marginal persistence decision. Asserts DIRECTION and ORDERING (and
 * termination), not exact counts: how many tries a want survives is care(drive·grit)-vs-cost, with
 * energy a floored modulator, so the four-corner felt shape must hold and the loop must end.
 */
class ProbeLoopPersistTest {

    private static final long PAST_WINDOW = ProbeLoop.WINDOW_SECONDS + 1;

    /** Run the marginal rule to give-up, draining energy a little per try (the real coupling), and
     *  return how many RETRY verdicts came before the DISENGAGE. */
    private int triesBeforeGiveUp(double drive, double grit, double startEnergy, double energyDrainPerTry) {
        double energy = startEnergy;
        int attempts = 0;
        for (int i = 0; i < 50; i++) {   // bounded guard so a non-terminating bug fails loudly
            double care = drive * grit;
            Verdict v = ProbeLoop.persistVerdict(PAST_WINDOW, attempts, care, energy);
            if (v == Verdict.UNANSWERED_RETRY) {
                attempts++;
                energy = Math.max(0.0, energy - energyDrainPerTry);
            } else {
                return attempts;   // DISENGAGE
            }
        }
        return -1;   // never terminated → bug
    }

    @Test
    void awaitsInsideWindow() {
        assertEquals(Verdict.AWAITING,
            ProbeLoop.persistVerdict(0, 0, 0.9, 1.0));
    }

    @Test
    void hardCapAlwaysTerminates() {
        // Even an absurd care + full energy can't grind past the HARD_CAP backstop.
        assertEquals(Verdict.UNANSWERED_DISENGAGE,
            ProbeLoop.persistVerdict(PAST_WINDOW, ProbeLoop.HARD_CAP, 1.0, 1.0));
    }

    @Test
    void terminatesUnderDrainingEnergy() {
        // The give-up must EMERGE (energy out under an intensifying want), never loop forever.
        int tries = triesBeforeGiveUp(1.0, 0.8, 1.0, 0.15);
        assertTrue(tries >= 0, "marginal persistence must terminate, not loop");
        assertTrue(tries <= ProbeLoop.HARD_CAP, "bounded by the hard cap");
    }

    @Test
    void fourCornerOrdering() {
        // No energy drain here — isolate the instantaneous care-vs-bar shape at a fixed first check.
        // high·high persists the most; low·low the least; a loved thing survives a tired day (high·low
        // still tries at least once more than a barely-cared one when rested).
        int hiHi = triesBeforeGiveUp(0.95, 0.9, 1.0, 0.10);   // cares + rested
        int hiLo = triesBeforeGiveUp(0.95, 0.9, 0.20, 0.10);  // cares but spent
        int loHi = triesBeforeGiveUp(0.25, 0.5, 1.0, 0.10);   // idle poke, rested
        int loLo = triesBeforeGiveUp(0.25, 0.5, 0.20, 0.10);  // one glance, spent

        assertTrue(hiHi > loLo, "cares+rested persists far more than doesn't-care+spent");
        assertTrue(hiHi >= hiLo, "rested holds on at least as long as spent, same high care");
        assertTrue(hiLo >= 1, "a loved thing still gets a real try even on a tired day (energy floored)");
        assertTrue(loLo <= 1, "barely-cared + spent gives up almost immediately");
        assertTrue(hiHi > loHi, "with energy to spare, deep care still outlasts idle curiosity");
    }

    @Test
    void gritStretchesPersistence() {
        // Same drive + energy; the higher-grit particular holds on at least as long.
        int dogged   = triesBeforeGiveUp(0.7, 0.9, 0.8, 0.10);
        int mercurial = triesBeforeGiveUp(0.7, 0.2, 0.8, 0.10);
        assertTrue(dogged >= mercurial, "grit (the seed axis) stretches how long a want is held");
    }

    @Test
    void scarcityRisesAsEnergyFalls() {
        assertTrue(ProbeLoop.scarcity(0.0) > ProbeLoop.scarcity(1.0),
            "a spent agent values each unit of effort more — higher bar");
        assertTrue(ProbeLoop.scarcity(1.0) >= ProbeLoop.SCARCITY_MIN,
            "even rested, scarcity is floored (>0) so grit is never infinite");
    }
}
