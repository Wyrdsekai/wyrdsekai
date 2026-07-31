package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DoomLoopDetectorTest {

    @Test void empty_input_produces_no_findings() {
        assertThat(DoomLoopDetector.detect(List.of())).isEmpty();
        assertThat(DoomLoopDetector.detect(null)).isEmpty();
    }

    @Test void stuck_on_same_want_fires_after_threshold() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < DoomLoopDetector.SAME_WANT_RUN_LIMIT; i++) {
            ticks.add(tick("acted", "want-A", "to read Saudade", "library_search", null));
        }
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("stuck_want");
            assertThat(f.message()).contains("Saudade");
        });
    }

    @Test void stuck_on_same_want_does_not_fire_below_threshold() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < DoomLoopDetector.SAME_WANT_RUN_LIMIT - 2; i++) {
            ticks.add(tick("acted", "want-A", "x", "library_search", null));
        }
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).noneSatisfy(f -> assertThat(f.key()).isEqualTo("stuck_want"));
    }

    @Test void verb_loop_fires_at_threshold() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < DoomLoopDetector.SAME_VERB_RUN_LIMIT; i++) {
            ticks.add(tick("acted", "want-" + i, "x", "library_search", null));
        }
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("verb_loop"));
    }

    @Test void drive_stuck_high_fires_when_one_drive_runs_hot() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < 10; i++) {
            ticks.add(tickWithDrive("acted", Map.of("Frustration", 0.85, "Calm", 0.2)));
        }
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("drive_stuck_high");
            assertThat(f.message()).contains("Frustration");
        });
    }

    @Test void pregate_skip_ratio_high_yields_info_finding() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < 20; i++) ticks.add(tick("pregate_skip", null, null, null, null));
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("high_pregate_skip");
            assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.INFO);
        });
    }

    @Test void rest_ratio_zero_flags_no_rest() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < 10; i++) ticks.add(tick("acted", "w-" + i, "x", "examine", null));
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("no_rest"));
    }

    @Test void rest_ratio_too_high_flags_all_rest() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < 10; i++) ticks.add(tick("chose_rest", null, null, null, null));
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).anySatisfy(f -> assertThat(f.key()).isEqualTo("all_rest"));
    }

    @Test void healthy_mix_produces_no_findings() {
        var ticks = new ArrayList<TickLogReader.TickEvent>();
        for (int i = 0; i < 10; i++) {
            String outcome = i % 4 == 0 ? "chose_rest" : "acted";
            String verb = i % 2 == 0 ? "examine" : "reflect";
            String wantId = "want-" + i;
            ticks.add(tickWithDriveAndVerb(outcome, wantId, verb,
                Map.of("Calm", 0.3, "Curiosity", 0.5)));
        }
        var findings = DoomLoopDetector.detect(ticks);
        assertThat(findings).isEmpty();
    }

    private static TickLogReader.TickEvent tick(String gate, String wantId, String wantText,
                                                String verb, Map<String, Double> drives) {
        return new TickLogReader.TickEvent(
            Instant.now(), "Ember", "did:key:zEmber",
            drives == null ? Map.of() : drives,
            0.5, gate, wantId, wantText, verb, "ok",
            List.of(), List.of(), 1800, 50);
    }

    private static TickLogReader.TickEvent tickWithDrive(String gate, Map<String, Double> drives) {
        return tick(gate, "w", "x", "examine", drives);
    }

    private static TickLogReader.TickEvent tickWithDriveAndVerb(String gate, String wantId,
                                                                 String verb,
                                                                 Map<String, Double> drives) {
        return tick(gate, wantId, "x", verb, drives);
    }
}
