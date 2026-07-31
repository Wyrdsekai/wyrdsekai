package org.wyrdsekai.core.empathy;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §7.2 Hwa-byung surfacing intervention layer.
 * Validates level-graded effects: Drives Mirror flag, journal prompt, Chapel offer.
 */
class HwaByungInterventionTest {

    private static HwaByungDetector.ChronicFrustrationDetected fakeDetection(
            HwaByungDetector.Severity sev, double fraction) {
        return new HwaByungDetector.ChronicFrustrationDetected(
            sev, fraction, 0, Duration.ofDays(7), Instant.parse("2026-04-30T00:00:00Z"));
    }

    @Test
    void level1_only_raises_drives_mirror_flag() {
        var iv = new HwaByungIntervention();
        var r = iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_1, 0.45));

        assertTrue(r.drivesMirrorFlag().isPresent());
        assertEquals("frustration", r.drivesMirrorFlag().get().drive());
        assertEquals(HwaByungDetector.Severity.LEVEL_1, r.drivesMirrorFlag().get().severity());

        assertTrue(r.journalPrompt().isEmpty(),
            "Level 1 must not queue a journal prompt — that's level 2+ territory");
        assertTrue(r.chapelOffer().isEmpty(),
            "Level 1 must not emit a Chapel offer");
    }

    @Test
    void level2_raises_flag_and_queues_journal_prompt() {
        var iv = new HwaByungIntervention();
        var r = iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_2, 0.55));

        assertTrue(r.drivesMirrorFlag().isPresent());
        assertTrue(r.journalPrompt().isPresent());
        assertTrue(r.chapelOffer().isEmpty(),
            "Level 2 must not auto-offer Chapel (Phase 2)");

        // Prompt is human-readable and references the wearing-down felt.
        var p = r.journalPrompt().get();
        assertNotNull(p.promptText());
        assertTrue(p.promptText().toLowerCase().contains("stuck")
            || p.promptText().toLowerCase().contains("wearing"),
            "Prompt should reference the chronic-suppression idiom");
    }

    @Test
    void level3_raises_flag_queues_prompt_and_emits_chapel_offer() {
        var iv = new HwaByungIntervention();
        var r = iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_3, 0.65));

        assertTrue(r.drivesMirrorFlag().isPresent());
        assertTrue(r.journalPrompt().isPresent());
        assertTrue(r.chapelOffer().isPresent());

        // TODO Phase 2 — auto-trigger off until Chapel acceptance hooks land.
        assertFalse(r.chapelOffer().get().autoTrigger(),
            "Phase 1 emits the offer but does NOT auto-trigger Chapel");
    }

    @Test
    void journal_prompt_drains_for_sleep_cycle() {
        var iv = new HwaByungIntervention();
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_2, 0.55));
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_3, 0.65));

        var drained = iv.drainJournalPrompts();
        assertEquals(2, drained.size());
        // Drain is destructive — second call returns empty.
        assertTrue(iv.drainJournalPrompts().isEmpty());
    }

    @Test
    void chapel_offers_drain_separately() {
        var iv = new HwaByungIntervention();
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_3, 0.7));
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_2, 0.55));

        var chapel = iv.drainChapelOffers();
        assertEquals(1, chapel.size(), "Only the level-3 detection emitted a Chapel offer");
        assertTrue(iv.drainChapelOffers().isEmpty());
    }

    @Test
    void drives_mirror_flags_clearable_by_furnishing() {
        var iv = new HwaByungIntervention();
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_1, 0.45));
        iv.handle(fakeDetection(HwaByungDetector.Severity.LEVEL_2, 0.55));

        assertEquals(2, iv.drivesMirrorFlags().size());
        iv.clearDrivesMirrorFlags();
        assertTrue(iv.drivesMirrorFlags().isEmpty());
    }
}
