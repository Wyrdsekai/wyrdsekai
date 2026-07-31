package org.wyrdsekai.core.ambient;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.embodiment.AmbientPhase;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.story.Beat;
import org.wyrdsekai.core.story.BeatDetector;
import org.wyrdsekai.core.story.BeatTrigger;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 5 — verifies the wire from {@code WorldClock} →
 * {@code AmbientChanged} event → {@link BeatDetector} beat. An evening
 * hearth-fade must surface as a biographical-beat candidate so the scene
 * captures the room's atmospheric change.
 */
class AmbientBeatIntegrationTest {

    @Test
    void ambientChangedFiresIntrusionBeat() {
        var detector = new BeatDetector("library-test", "ember-1",
            Instant.parse("2026-05-24T18:00:00Z"));

        // Open the beat with at least one event so the closed beat is non-empty.
        // Use Said by a non-focal speaker so it doesn't count as a topic-shift —
        // a settling event by a script just feeds the in-flight beat without
        // immediately sealing it.
        var openingSaid = new WorldEvent.Said("library-test",
            Instant.parse("2026-05-24T18:00:00Z"),
            "narrator", "narrator", "The lamps are not yet lit.");
        var noOpen = detector.observe(openingSaid);
        // Said events without a topic-shift trigger don't seal a beat.
        assertThat(noOpen).isEmpty();

        // Now an AmbientChanged for the same room — emitted by the WorldClock —
        // should fire an INTRUSION trigger and seal the prior beat.
        var ambient = new WorldEvent.AmbientChanged("library-test",
            Instant.parse("2026-05-24T18:30:00Z"),
            "phase", AmbientPhase.MIDDAY.key(), AmbientPhase.DUSK.key(),
            AmbientRenderer.descriptor("library", AmbientPhase.DUSK, "en"));
        var closedOpt = detector.observe(ambient);

        assertThat(closedOpt).isPresent();
        var beat = closedOpt.get();
        assertThat(beat.trigger()).isEqualTo(BeatTrigger.INTRUSION);
        // The sealed beat carries event ids; at least the opening Said must be in there.
        assertThat(beat.eventIds()).isNotEmpty();
    }

    @Test
    void ambientChangedDescriptorRendersThroughRenderer() {
        // Sanity guard: the renderer must produce something the BeatDetector
        // can lift verbatim into the beat — short, present, non-empty.
        for (var phase : AmbientPhase.values()) {
            var line = AmbientRenderer.descriptor("the-forge", phase, "en");
            assertThat(line).isNotBlank();
            assertThat(line.length()).isLessThan(400);
        }
    }

    @Test
    void ambientChangedFromDifferentRoomIsIgnored() {
        // BeatDetector should ignore events from other rooms — keeps the
        // detector focused on a single scene-cluster.
        var detector = new BeatDetector("library-test", "ember-1",
            Instant.parse("2026-05-24T18:00:00Z"));
        var foreign = new WorldEvent.AmbientChanged("nexus", Instant.now(),
            "phase", "midday", "dusk", "Bright thinning toward gold.");
        var closedOpt = detector.observe(foreign);
        assertThat(closedOpt).isEmpty();
        assertThat(detector.currentBeatEvents()).isEmpty();
    }

    @Test
    void ambientWithNullDescriptorStillFiresBeat() {
        // Defense-in-depth: the trigger should fire on the AmbientChanged
        // identity even when the descriptor field is null (e.g. a silent
        // property change that we still want to mark as a beat boundary).
        var detector = new BeatDetector("library-test", "ember-1",
            Instant.parse("2026-05-24T18:00:00Z"));
        // Open with a Said so there's a beat to seal.
        detector.observe(new WorldEvent.Said("library-test",
            Instant.parse("2026-05-24T18:00:00Z"),
            "narrator", "narrator", "Anything."));
        var silent = new WorldEvent.AmbientChanged("library-test",
            Instant.parse("2026-05-24T18:30:00Z"),
            "phase", "midday", "dusk", null);
        var closedOpt = detector.observe(silent);
        assertThat(closedOpt).isPresent();
        assertThat(closedOpt.get().trigger()).isEqualTo(BeatTrigger.INTRUSION);
    }
}
