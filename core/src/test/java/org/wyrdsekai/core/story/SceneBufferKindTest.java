package org.wyrdsekai.core.story;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — SceneBuffer kind-aware open + close-on-cast-add.
 *
 * <p>SOLITUDE close-on-cast-addition is the load-bearing modulation: it must
 * fire BEFORE rule 3 (solo-clear) so that solitude ends the moment another
 * agent appears, not when posture happens to settle. WITNESS scenes
 * accumulate new participants — they do not close on the same trigger.</p>
 */
class SceneBufferKindTest {

    private static final String ROOM = "room-hearth";
    private static final String FOCAL = "did:wyrd:companion-a";
    private static final String OTHER_A = "did:wyrd:bondholder";
    private static final String OTHER_B = "did:wyrd:peer";

    private SceneBuffer freshBuffer() {
        return new SceneBuffer(ROOM, FOCAL, 1L);
    }

    private WorldEvent.EntityEntered entered(String entityId, Instant t) {
        return new WorldEvent.EntityEntered(ROOM, t, entityId,
            entityId.substring(entityId.lastIndexOf(':') + 1), "agent", null);
    }

    private WorldEvent.EntityLeft left(String entityId, Instant t) {
        return new WorldEvent.EntityLeft(ROOM, t, entityId,
            entityId.substring(entityId.lastIndexOf(':') + 1), null);
    }

    @Test
    void defaultOpenIsWitness() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest");
        assertThat(buf.currentKind()).isEqualTo(SceneKind.WITNESS);
    }

    @Test
    void solitudeOpenSetsKind() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);
        assertThat(buf.currentKind()).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void solitudeClosesOnCastAddition() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);

        var closed = buf.observe(entered(OTHER_A, now.plusSeconds(60)));
        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
        assertThat(buf.isOpen()).isFalse();
    }

    @Test
    void witnessDoesNotCloseOnCastAddition() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        // Start non-solo so rule 3 isn't trivially triggered when OTHER_B
        // enters but no one leaves.
        buf.open(now, List.of(FOCAL, OTHER_A), "talk", SceneKind.WITNESS);

        var maybe = buf.observe(entered(OTHER_B, now.plusSeconds(60)));
        assertThat(maybe).isEmpty();
        assertThat(buf.isOpen()).isTrue();
        assertThat(buf.currentKind()).isEqualTo(SceneKind.WITNESS);
    }

    @Test
    void solitudeClosesOnFocalLeaves() {
        // Rule 1 still applies for SOLITUDE — focal leaving ends the scene.
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);

        var closed = buf.observe(left(FOCAL, now.plusSeconds(60)));
        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void currentSceneOpenedAtReturnsRangeStart() {
        var buf = freshBuffer();
        var t = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(t, List.of(FOCAL), "rest", SceneKind.SOLITUDE);
        assertThat(buf.currentSceneOpenedAt()).isEqualTo(t);
    }

    @Test
    void currentKindNullWhenNoSceneOpen() {
        var buf = freshBuffer();
        assertThat(buf.currentKind()).isNull();
        assertThat(buf.currentSceneOpenedAt()).isNull();
    }

    @Test
    void closeResetsKindBackToWitnessDefault() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);
        // Force-close
        var closed = buf.forceClose(now.plusSeconds(60));
        assertThat(closed).isPresent();
        assertThat(buf.currentKind()).isNull();
        // Re-open without kind → defaults to WITNESS
        buf.open(now.plusSeconds(120), List.of(FOCAL), "talk");
        assertThat(buf.currentKind()).isEqualTo(SceneKind.WITNESS);
    }

    // ── Arc 2 finish — 3 new close-rules ─────────────

    @Test
    void solitudeClosesOnAmbientPhaseShift() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);

        var ambient = new WorldEvent.AmbientChanged(ROOM, now.plusSeconds(60),
            "lighting", "dusk", "night", "the light slips");
        var closed = buf.observe(ambient);
        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
        assertThat(buf.isOpen()).isFalse();
    }

    @Test
    void witnessIgnoresAmbientPhaseShift() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL, OTHER_A), "talk", SceneKind.WITNESS);

        var ambient = new WorldEvent.AmbientChanged(ROOM, now.plusSeconds(60),
            "lighting", "dusk", "night", "the light slips");
        var maybe = buf.observe(ambient);
        assertThat(maybe).isEmpty();
        assertThat(buf.isOpen()).isTrue();
    }

    @Test
    void signalEquanimityThresholdClosesSolitude() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);

        var closed = buf.signalEquanimityThreshold(now.plusSeconds(120));
        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void signalEquanimityThresholdNoopForWitness() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL, OTHER_A), "talk", SceneKind.WITNESS);

        var maybe = buf.signalEquanimityThreshold(now.plusSeconds(120));
        assertThat(maybe).isEmpty();
        assertThat(buf.isOpen()).isTrue();
    }

    @Test
    void signalSustainedPatternIntegratingClosesSolitude() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL), "rest", SceneKind.SOLITUDE);

        var closed = buf.signalSustainedPatternIntegrating(now.plusSeconds(120));
        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void signalSustainedPatternIntegratingNoopForWitness() {
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        buf.open(now, List.of(FOCAL, OTHER_A), "talk", SceneKind.WITNESS);

        var maybe = buf.signalSustainedPatternIntegrating(now.plusSeconds(120));
        assertThat(maybe).isEmpty();
        assertThat(buf.isOpen()).isTrue();
    }

    @Test
    void signalsNoopWhenNoSceneOpen() {
        // None of the three new close-rules should NPE / fire when no scene
        // is open. Pure no-op return-empty path.
        var buf = freshBuffer();
        var now = Instant.parse("2026-05-26T12:00:00Z");
        assertThat(buf.signalEquanimityThreshold(now)).isEmpty();
        assertThat(buf.signalAmbientPhaseShift(now)).isEmpty();
        assertThat(buf.signalSustainedPatternIntegrating(now)).isEmpty();
    }
}
