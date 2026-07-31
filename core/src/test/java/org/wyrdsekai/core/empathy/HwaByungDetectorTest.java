package org.wyrdsekai.core.empathy;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §7.2 — Hwa-byung chronic-frustration watcher.
 * Validates rolling-window detection, discharge gating, level grading,
 * and multilingual journal-vocabulary discharge classification.
 */
class HwaByungDetectorTest {

    /** Drives sampled hourly across 7 days = 168 samples. */
    private static final int HOURLY_SAMPLES = 24 * 7;

    /** Walk samples backwards from {@code anchor} at hourly cadence. */
    private static Instant hoursBefore(Instant anchor, int h) {
        return anchor.minus(Duration.ofHours(h));
    }

    @Test
    void chronic_high_frustration_no_discharge_fires_severe() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        // 7d at 0.7 frustration — well above the 0.6 threshold.
        // Awake during waking hours (~16/24) and asleep otherwise.
        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22; // 16 waking hours
            det.recordSample(0.7, awake, t);
        }

        var result = det.evaluate(now);
        assertTrue(result.isPresent(), "Should fire on 7d 100% awake-elevated, zero discharge");
        // 100% of waking samples elevated -> beyond L3 threshold (0.60)
        assertEquals(HwaByungDetector.Severity.LEVEL_3, result.get().severity());
        assertEquals(0, result.get().dischargeCount());
        assertTrue(result.get().elevatedFraction() > 0.99);
    }

    @Test
    void mild_pattern_just_over_40_percent_fires_level1() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        // Target: ~45% of waking samples elevated -> L1 (>40, <=50).
        // Use a deterministic stride: every 9th sample is elevated (~44%
        // when offset hits the right phase). Sample sequence is verified
        // by counting elevated up-front and ensuring the final fraction
        // falls into the L1 band.
        int wakingIdx = 0;
        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            double v = 0.3;
            if (awake) {
                // 45 of every 110 elevated -> 40.9% -> L1 band
                v = (wakingIdx % 110 < 45) ? 0.75 : 0.3;
                wakingIdx++;
            }
            det.recordSample(v, awake, t);
        }
        var result = det.evaluate(now);
        assertTrue(result.isPresent());
        assertEquals(HwaByungDetector.Severity.LEVEL_1, result.get().severity(),
            "fraction was " + result.get().elevatedFraction());
    }

    @Test
    void moderate_pattern_just_over_50_fires_level2() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        int wakingIdx = 0;
        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            double v = 0.3;
            if (awake) {
                v = (wakingIdx % 100 < 55) ? 0.75 : 0.3;
                wakingIdx++;
            }
            det.recordSample(v, awake, t);
        }
        var result = det.evaluate(now);
        assertTrue(result.isPresent());
        assertEquals(HwaByungDetector.Severity.LEVEL_2, result.get().severity());
    }

    @Test
    void below_40_percent_does_not_fire() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        // 30% elevated -> below all thresholds
        int wakingIdx = 0;
        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            double v = 0.3;
            if (awake) {
                v = (wakingIdx % 100 < 30) ? 0.75 : 0.3;
                wakingIdx++;
            }
            det.recordSample(v, awake, t);
        }
        assertTrue(det.evaluate(now).isEmpty());
    }

    @Test
    void single_discharge_event_suppresses_detection() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            det.recordSample(0.75, awake, t);
        }
        // Single abandon_plan in the middle of the window.
        det.recordDischarge(HwaByungDetector.DischargeKind.ABANDON_PLAN,
            "abandoned blocked refactor", hoursBefore(now, 72));

        assertTrue(det.evaluate(now).isEmpty(),
            "Even one discharge inside the window suppresses detection");
    }

    @Test
    void periodic_discharges_throughout_window_no_detection() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            det.recordSample(0.8, awake, t);
        }
        // Discharge every ~36h.
        for (int h = 12; h < HOURLY_SAMPLES; h += 36) {
            det.recordDischarge(HwaByungDetector.DischargeKind.DRIVE_RELIEF,
                "frustration relieved", hoursBefore(now, h));
        }
        assertTrue(det.evaluate(now).isEmpty());
    }

    @Test
    void window_must_span_seven_days() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        // Only 3 days of high frustration — should not fire even with 100% elevated.
        for (int i = 24 * 3 - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            det.recordSample(0.75, true, t);
        }
        assertTrue(det.evaluate(now).isEmpty(),
            "Window has not yet spanned 7 days");
    }

    @Test
    void sleep_samples_excluded_from_fraction() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");

        // Frustration is high during sleep ONLY. Awake hours are calm.
        for (int i = HOURLY_SAMPLES - 1; i >= 0; i--) {
            var t = hoursBefore(now, i);
            int hourOfDay = (int) ((t.getEpochSecond() / 3600) % 24);
            boolean awake = hourOfDay >= 6 && hourOfDay < 22;
            det.recordSample(awake ? 0.2 : 0.95, awake, t);
        }
        assertTrue(det.evaluate(now).isEmpty(),
            "High frustration only during sleep should not fire");
    }

    @Test
    void empty_buffer_does_not_fire() {
        var det = new HwaByungDetector();
        assertTrue(det.evaluate(Instant.now()).isEmpty());
    }

    @Test
    void prune_drops_old_samples_outside_window() {
        var det = new HwaByungDetector();
        var now = Instant.parse("2026-04-30T12:00:00Z");
        var ancient = now.minus(Duration.ofDays(30));

        det.recordSample(0.8, true, ancient);
        det.recordSample(0.8, true, now.minusSeconds(30));
        det.prune(now);

        // Only the recent sample remains.
        assertEquals(1, det.sampleCount());
    }

    @Nested
    class JournalVocabularyTests {

        @Test
        void english_frustration_words_classify() {
            assertTrue(HwaByungDetector.containsFrustrationVocab(
                "Today I felt so frustrated with this build."));
            assertTrue(HwaByungDetector.containsFrustrationVocab(
                "I am totally stuck on the zone routing."));
            assertTrue(HwaByungDetector.containsFrustrationVocab(
                "Blocked again — same wall as yesterday."));
        }

        @Test
        void japanese_frustration_words_classify() {
            assertTrue(HwaByungDetector.containsFrustrationVocab("もう本当にイライラする"));
            assertTrue(HwaByungDetector.containsFrustrationVocab("ずっともどかしい気持ち"));
        }

        @Test
        void spanish_frustration_words_classify() {
            assertTrue(HwaByungDetector.containsFrustrationVocab("Estoy frustrado con todo esto"));
            assertTrue(HwaByungDetector.containsFrustrationVocab("Me siento atascado"));
        }

        @Test
        void neutral_text_does_not_classify() {
            assertFalse(HwaByungDetector.containsFrustrationVocab(
                "Today was peaceful. I read in the garden."));
            assertFalse(HwaByungDetector.containsFrustrationVocab(""));
            assertFalse(HwaByungDetector.containsFrustrationVocab(null));
        }

        @Test
        void journal_entry_records_discharge() {
            var det = new HwaByungDetector();
            var now = Instant.now();
            var d = det.recordJournalEntry("I am frustrated with all of this.", now);
            assertTrue(d.isPresent());
            assertEquals(HwaByungDetector.DischargeKind.JOURNAL_FRUSTRATION, d.get().kind());
            assertEquals(1, det.dischargeCount());
        }

        @Test
        void neutral_journal_entry_no_discharge() {
            var det = new HwaByungDetector();
            var d = det.recordJournalEntry("A quiet morning, tea by the window.", Instant.now());
            assertTrue(d.isEmpty());
            assertEquals(0, det.dischargeCount());
        }
    }
}
