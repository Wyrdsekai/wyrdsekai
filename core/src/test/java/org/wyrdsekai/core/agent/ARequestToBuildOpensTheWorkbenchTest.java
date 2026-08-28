package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A request to build must reach hands that can build — and use them.
 *
 * <p>The greenhouse, live 2026-08-11 05:57–06:00, failed twice in one
 * conversation: "how about making us a greenhouse" was classified
 * confidently-no-task (0.80) and answered by the toolless voice tier; the
 * explicit retry "ok so create a room - a greenhouse" routed with tools,
 * put create_room_from_template second on the surface — and the model
 * described the greenhouse instead of building it. The bondholder heard
 * agreement twice and nothing ever appeared. Same lesson as dev42, third
 * door: a guarantee is only as wide as the paths that host it.</p>
 */
class ARequestToBuildOpensTheWorkbenchTest {

    private static String src() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        return Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
    }

    // ── The recognizer ────────────────────────────────────────────────────

    /** Both live phrasings — including the typo — read as build requests. */
    @Test
    void the_greenhouse_phrasings_are_recognized() {
        assertThat(CompanionActor.looksLikeBuildRequest(
            "how about makingf us a greenhouse. a room full of plants")).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "ok so create a room - a greenhouse. make it full of plants")).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "can you build us a reading nook somewhere quiet")).isTrue();
        // The SECOND live phrasing (15:15 same day): a need, not a verb.
        assertThat(CompanionActor.looksLikeBuildRequest(
            "cool. so I was thinking we need a greenhouse full of green plants as a new room")).isTrue();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "i want a quiet little space for reading")).isTrue();
    }

    /** Writing-shaped asks belong to the quill, and chat stays chat. */
    @Test
    void writing_and_chat_do_not_arm_the_workbench() {
        assertThat(CompanionActor.looksLikeBuildRequest(
            "make me a list of every ship in the hornblower books")).isFalse();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "please write a short welcome note for our guest")).isFalse();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "that makes sense to me")).isFalse();
        assertThat(CompanionActor.looksLikeBuildRequest(
            "good morning! how are you feeling?")).isFalse();
        // Need-phrasing without a made-thing cue stays conversation.
        assertThat(CompanionActor.looksLikeBuildRequest(
            "we need to talk about yesterday")).isFalse();
    }

    // ── The wiring ────────────────────────────────────────────────────────

    /** The voice-route override: a build request never lands on the toolless tier. */
    @Test
    void the_voice_tier_cannot_swallow_a_build_request() throws Exception {
        assertThat(src()).contains(
            "a build request, and the voice tier cannot build");
    }

    /** Armed per turn beside library-first; fact-questions take precedence. */
    @Test
    void armed_beside_library_first_with_lookup_precedence() throws Exception {
        var s = src();
        assertThat(s).contains(
            "buildFirstPending = !libraryFirstPending && looksLikeBuildRequest(asked);");
        assertThat(s).contains("Build-first armed for:");
    }

    /** The pin rides the trim, choosing room-builder vs crafting bench shallowly. */
    @Test
    void a_build_tool_is_pinned_through_the_topk_trim() throws Exception {
        var s = src();
        assertThat(s).contains("} else if (buildFirstPending) {");
        assertThat(s).contains("? \"create_room_from_template\" : \"craft_from_template\";");
    }

    /** The direct-path force: build-or-decline, required, consumed either way. */
    @Test
    void the_direct_path_has_teeth() throws Exception {
        var s = src();
        assertThat(s).contains("Build-first FORCE (direct):");
        assertThat(s).contains("Build-first armed but no build tool on this surface — skipped (direct)");
        assertThat(s)
            .as("a force may compel a choice, never an assent")
            .contains("\"decline_with_reason\"");
    }
}
