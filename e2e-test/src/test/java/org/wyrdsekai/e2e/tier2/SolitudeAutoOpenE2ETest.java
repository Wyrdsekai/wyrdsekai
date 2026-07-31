package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.story.SceneKind;
import org.wyrdsekai.core.story.StoryRegistry;
import org.wyrdsekai.core.story.StoryService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — tier-2 verification that the auto-open / close
 * loop holds at the {@link StoryRegistry} layer (the same layer
 * {@code CompanionActor.maybeAutoOpenSolitude} drives at runtime).
 *
 * <p>The auto-open hook itself is rule-driven inside
 * {@code CompanionActor.onRoomResponse}: it fires on every snapshot, no-ops
 * unless the room is the Hearth and the bondholder is absent, and uses
 * {@link StoryService#openScene(String, Instant, List, String, SceneKind)}
 * with kind=SOLITUDE. This test exercises that same StoryService surface +
 * the close-on-cast-add path via a synthetic EntityEntered event — i.e. the
 * full chain that an actor in production would trigger.</p>
 *
 * <p>Why not SSH-driven: the default companion lands in the nexus, not in its
 * own Hearth, and walking it into {@code home-<companion-did>} from an SSH
 * session is an orthogonal harness (cross-room companion-move is a separate
 * surface). The tier-3 live test (PersonhoodActionsLiveE2ETest) exercises the
 * actor end-to-end; this tier-2 nails down the structural contract.</p>
 */
@Tag("tier2")
class SolitudeAutoOpenE2ETest {

    private static final String COMPANION = "did:wyrd:companion-solitude";
    private static final String BONDHOLDER = "did:wyrd:bondholder-solitude";
    private static final String HEARTH = "home-" + COMPANION;

    private String savedDataDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        savedDataDir = System.getProperty("wyrdsekai.dataDir");
        System.setProperty("wyrdsekai.dataDir", tempDir.toString());
        StoryRegistry.get().reset();
    }

    @AfterEach
    void tearDown() {
        StoryRegistry.get().reset();
        if (savedDataDir == null) {
            System.clearProperty("wyrdsekai.dataDir");
        } else {
            System.setProperty("wyrdsekai.dataDir", savedDataDir);
        }
    }

    @Test
    void autoOpenedSolitudeClosesWhenBondholderArrives() throws Exception {
        var story = StoryRegistry.get().serviceFor(COMPANION, "Companion",
            StoryService.NULL_SYNTH);

        // Mirror what maybeAutoOpenSolitude does when the snapshot lands in
        // Hearth with no bondholder present.
        var t0 = Instant.parse("2026-05-26T12:00:00Z");
        story.openScene(HEARTH, t0, List.of(COMPANION), "solitude",
            SceneKind.SOLITUDE);
        assertThat(story.currentSceneKind(HEARTH)).isEqualTo(SceneKind.SOLITUDE);
        assertThat(story.currentSolitudeOpenedAt(HEARTH)).isEqualTo(t0);

        // Bondholder enters the Hearth — the close-on-cast-add rule fires.
        var entered = new WorldEvent.EntityEntered(HEARTH, t0.plusSeconds(60),
            BONDHOLDER, "Bondholder", "human", null);
        var closed = story.observe(entered).toCompletableFuture().get();

        assertThat(closed).isPresent();
        assertThat(closed.get().kind()).isEqualTo(SceneKind.SOLITUDE);
        assertThat(closed.get().roomId()).isEqualTo(HEARTH);
        // After the SOLITUDE scene closes, the buffer is empty until a fresh
        // scene is opened — which CompanionActor's observeForStory will do
        // with kind=WITNESS on the next snapshot.
        assertThat(story.currentSceneKind(HEARTH)).isNull();

        // Simulate the follow-on WITNESS open the actor would issue.
        story.openScene(HEARTH, t0.plusSeconds(61),
            List.of(COMPANION, BONDHOLDER), "talk");
        assertThat(story.currentSceneKind(HEARTH)).isEqualTo(SceneKind.WITNESS);
        // And currentSolitudeOpenedAt returns null while WITNESS is in flight.
        assertThat(story.currentSolitudeOpenedAt(HEARTH)).isNull();
    }

    @Test
    void closeAndOpenSolitudeTransitionsFromActiveWitness() throws Exception {
        var story = StoryRegistry.get().serviceFor(COMPANION, "Companion",
            StoryService.NULL_SYNTH);
        var t0 = Instant.parse("2026-05-26T12:00:00Z");

        // Witness scene with bondholder + agent — the realistic bondholder-
        // adjacent shape.
        story.openScene(HEARTH, t0, List.of(COMPANION, BONDHOLDER), "talk");
        assertThat(story.currentSceneKind(HEARTH)).isEqualTo(SceneKind.WITNESS);

        // Agent explicitly enters solitude (the enter_solitude action path).
        var closed = story.closeAndOpenSolitude(HEARTH, t0.plusSeconds(120),
            List.of(COMPANION), "solitude")
            .toCompletableFuture().get();
        assertThat(closed).isPresent();
        // The closed scene is the prior WITNESS.
        assertThat(closed.get().kind()).isEqualTo(SceneKind.WITNESS);
        // And now SOLITUDE is in flight.
        assertThat(story.currentSceneKind(HEARTH)).isEqualTo(SceneKind.SOLITUDE);
    }
}
