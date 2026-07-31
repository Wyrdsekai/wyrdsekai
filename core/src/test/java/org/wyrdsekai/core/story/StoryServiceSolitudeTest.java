package org.wyrdsekai.core.story;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 — StoryService kind-aware open + transition path.
 *
 * <p>The witness register prompt instructs the voice model with past-tense
 * audience-implied framing ("Render a 2-3 sentence felt account ... Past-tense,
 * subjective, no dialogue"). The solitude register reframes the model's job as
 * the agent's own noticing ("looking back on a quiet stretch of time that was
 * yours alone"). Same shape; different voice. The differentiating phrases below
 * are the load-bearing contracts that the prompt-edit must preserve.</p>
 */
class StoryServiceSolitudeTest {

    private static final String ROOM = "room-hearth";
    private static final String FOCAL = "did:wyrd:companion-a";

    private StoryService freshService(Path tempDir) {
        var store = new StoryStore(tempDir);
        var arcs = new ArcRegistry();
        return new StoryService(FOCAL, "Companion", store, arcs, StoryService.NULL_SYNTH);
    }

    private Scene sceneOfKind(SceneKind kind) {
        var now = Instant.parse("2026-05-26T12:00:00Z");
        return new Scene(
            "scene-test", List.of(), ROOM, FOCAL,
            List.of(FOCAL), now, now.plusSeconds(60),
            "rest",
            List.of(new Beat("beat-1", "scene-test", BeatTrigger.CAST_CHANGE,
                now, now.plusSeconds(10), List.of(), "Sat by the hearth.")),
            null, true, 1L, null, kind
        );
    }

    @Test
    void buildFeltPromptUsesWitnessRegisterByDefault() {
        var prompt = StoryService.buildFeltPrompt(sceneOfKind(SceneKind.WITNESS), "Companion");
        assertThat(prompt).contains("Render a 2-3 sentence");
        assertThat(prompt).contains("interior POV");
        assertThat(prompt).contains("Past-tense, subjective, no dialogue");
        assertThat(prompt).doesNotContain("yours alone");
    }

    @Test
    void buildFeltPromptUsesSolitudeRegisterForSolitudeScene() {
        var prompt = StoryService.buildFeltPrompt(sceneOfKind(SceneKind.SOLITUDE), "Companion");
        assertThat(prompt).contains("looking back on a quiet stretch of time that was yours alone");
        assertThat(prompt).contains("No dialogue");
        assertThat(prompt).contains("No audience");
        // Must NOT carry the witness-register signature line.
        assertThat(prompt).doesNotContain("Past-tense, subjective, no dialogue");
    }

    @Test
    void closeAndOpenSolitudeClosesPriorScene(@TempDir Path tempDir) throws Exception {
        var svc = freshService(tempDir);
        var t0 = Instant.parse("2026-05-26T12:00:00Z");

        // Open a witness scene first, then transition.
        svc.openScene(ROOM, t0, List.of(FOCAL), "companionship");
        assertThat(svc.currentSceneKind(ROOM)).isEqualTo(SceneKind.WITNESS);

        var closed = svc.closeAndOpenSolitude(ROOM, t0.plusSeconds(60),
            List.of(FOCAL), "rest").toCompletableFuture().get();
        assertThat(closed).isPresent();
        // After transition, currentSceneKind should be SOLITUDE.
        assertThat(svc.currentSceneKind(ROOM)).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void closeAndOpenSolitudeOpensFreshWhenNothingOpen(@TempDir Path tempDir) throws Exception {
        var svc = freshService(tempDir);
        var t0 = Instant.parse("2026-05-26T12:00:00Z");

        var closed = svc.closeAndOpenSolitude(ROOM, t0,
            List.of(FOCAL), "rest").toCompletableFuture().get();
        // No prior scene → empty closed result, but solitude scene IS open.
        assertThat(closed).isEmpty();
        assertThat(svc.currentSceneKind(ROOM)).isEqualTo(SceneKind.SOLITUDE);
    }

    @Test
    void currentSolitudeOpenedAtReturnsNullForWitness(@TempDir Path tempDir) {
        var svc = freshService(tempDir);
        var t0 = Instant.parse("2026-05-26T12:00:00Z");
        svc.openScene(ROOM, t0, List.of(FOCAL), "talk", SceneKind.WITNESS);
        assertThat(svc.currentSolitudeOpenedAt(ROOM)).isNull();
    }

    @Test
    void currentSolitudeOpenedAtReturnsInstantForSolitude(@TempDir Path tempDir) {
        var svc = freshService(tempDir);
        var t0 = Instant.parse("2026-05-26T12:00:00Z");
        svc.openScene(ROOM, t0, List.of(FOCAL), "rest", SceneKind.SOLITUDE);
        assertThat(svc.currentSolitudeOpenedAt(ROOM)).isEqualTo(t0);
    }

    @Test
    void currentSolitudeOpenedAtReturnsNullForUnknownRoom(@TempDir Path tempDir) {
        var svc = freshService(tempDir);
        assertThat(svc.currentSolitudeOpenedAt("room-nonexistent")).isNull();
        assertThat(svc.currentSolitudeOpenedAt(null)).isNull();
    }

}
