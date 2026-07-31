package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.SceneKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — sustained-SOLITUDE chronicle detector.
 *
 * <p>Verifies the threshold (5 in a 7-day window), INFO severity (never WARN
 * or CRITICAL — solitude is not a flag), and that WITNESS scenes don't trip
 * the count.</p>
 */
class SustainedSolitudePatternDetectorTest {

    private static final String ROOM = "room-hearth";
    private static final String FOCAL = "did:wyrd:agent";

    private Scene scene(SceneKind kind, int seq) {
        var start = Instant.parse("2026-05-20T12:00:00Z").plusSeconds(seq * 3600L);
        return new Scene(
            "scene-" + seq,
            List.of(),
            ROOM,
            FOCAL,
            List.of(FOCAL),
            start,
            start.plusSeconds(600),
            "rest",
            List.of(),
            null, true, seq, null, kind);
    }

    @Test
    void emptySceneListReturnsNoFinding() {
        assertThat(SustainedSolitudePatternDetector.detect(List.of())).isEmpty();
        assertThat(SustainedSolitudePatternDetector.detect((List<Scene>) null)).isEmpty();
    }

    @Test
    void belowThresholdReturnsNoFinding() {
        // Four SOLITUDE scenes — one below threshold (5).
        var scenes = new ArrayList<Scene>();
        for (int i = 0; i < 4; i++) scenes.add(scene(SceneKind.SOLITUDE, i));
        assertThat(SustainedSolitudePatternDetector.detect(scenes)).isEmpty();
    }

    @Test
    void atThresholdEmitsInfoFinding() {
        var scenes = new ArrayList<Scene>();
        for (int i = 0; i < SustainedSolitudePatternDetector.SUSTAINED_THRESHOLD; i++) {
            scenes.add(scene(SceneKind.SOLITUDE, i));
        }
        var findings = SustainedSolitudePatternDetector.detect(scenes);
        assertThat(findings).hasSize(1);
        var f = findings.get(0);
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.INFO);
        assertThat(f.key()).isEqualTo("sustained_solitude");
        assertThat(f.message()).contains(String.valueOf(SustainedSolitudePatternDetector.SUSTAINED_THRESHOLD));
        // Solitude is never an alarm — must be flagged as context.
        assertThat(f.message().toLowerCase()).contains("not a flag");
    }

    @Test
    void witnessScenesDoNotCount() {
        var scenes = new ArrayList<Scene>();
        // 10 WITNESS scenes + 2 SOLITUDE — still below threshold for SOLITUDE.
        for (int i = 0; i < 10; i++) scenes.add(scene(SceneKind.WITNESS, i));
        for (int i = 10; i < 12; i++) scenes.add(scene(SceneKind.SOLITUDE, i));
        assertThat(SustainedSolitudePatternDetector.detect(scenes)).isEmpty();
    }

    @Test
    void mixedAboveThresholdEmits() {
        var scenes = new ArrayList<Scene>();
        for (int i = 0; i < 3; i++) scenes.add(scene(SceneKind.WITNESS, i));
        for (int i = 3; i < 9; i++) scenes.add(scene(SceneKind.SOLITUDE, i));
        var findings = SustainedSolitudePatternDetector.detect(scenes);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).message()).contains("6 distinct SOLITUDE");
    }

    @Test
    void nullStoreReturnsEmpty() {
        assertThat(SustainedSolitudePatternDetector.detect(null, "did:wyrd:x")).isEmpty();
    }
}
