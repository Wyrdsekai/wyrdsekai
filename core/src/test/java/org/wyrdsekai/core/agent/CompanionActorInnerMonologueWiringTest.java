package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verify the inner-monologue + felt voice wiring is
 * really present in CompanionActor source. Source-text-based per the project
 * pattern (see {@link CompanionActorSubstrateSleepPassWiringTest}): cheap,
 * deterministic, catches "did the wire get removed in a refactor" without
 * needing to spin up a Pekko actor system. Behavioral correctness is covered
 * by the StoryService + EPISODIC unit tests; this catches structural drift.
 */
class CompanionActorInnerMonologueWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    @Test
    void observeForStory_wires_both_synthesizers_on_first_create() throws Exception {
        var src = sourceText();
        int start = src.indexOf("private void observeForStory(");
        assertThat(start).isGreaterThan(0);
        int end = src.indexOf("\n    private", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        assertThat(body)
            .as("observeForStory must call the 4-arg serviceFor(focalId,name,felt,inner)")
            .contains("StoryRegistry.get().serviceFor(")
            .contains("this::renderFeltViaVoice")
            .contains("this::renderInnerMonologueAndPersist");
    }

    @Test
    void renderInnerMonologueAndPersist_has_idempotency_recursion_persist_pipeline()
            throws Exception {
        var src = sourceText();
        int start = src.indexOf("private CompletionStage<Void> "
            + "renderInnerMonologueAndPersist(");
        assertThat(start)
            .as("renderInnerMonologueAndPersist method must exist on CompanionActor")
            .isGreaterThan(0);
        int end = src.indexOf("\n    private CompletionStage<String> "
            + "callVoiceInnerMonologue(", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        // 1. Idempotency: skip if EPISODIC fragment for this sceneId already exists.
        assertThat(body)
            .as("idempotency check: skip if EPISODIC fragment for sceneId already present")
            .contains("FragmentKind.EPISODIC")
            .contains("scene.id().equals(f.sceneId())")
            .contains("alreadyExists");

        // 2. Recursion: pull top-K prior EPISODIC for the prompt context.
        assertThat(body)
            .as("recursion: pull prior EPISODIC fragments via the ranking helper")
            .contains("rankPriorEpisodicForRecursion");

        // 3. Call voice + persist on success.
        assertThat(body)
            .contains("callVoiceInnerMonologue")
            .contains("persistInnerMonologueFragment");
    }

    @Test
    void persistInnerMonologueFragment_uses_fromEpisodicScene_and_dual_writes()
            throws Exception {
        var src = sourceText();
        int start = src.indexOf("private void persistInnerMonologueFragment(");
        assertThat(start).isGreaterThan(0);
        int end = src.indexOf("\n    /**", start + 100);
        if (end < 0) end = src.indexOf("\n    private ", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        assertThat(body)
            .as("must build EPISODIC fragment via the §10 factory (preserves kind + sceneId)")
            .contains("SoulFragment.fromEpisodicScene(");

        assertThat(body)
            .as("must dual-write via soulStore.store() — same path as substrate/recipe Forge")
            .contains("cachedManifest.withFragments(")
            .contains("bumpedVersion()")
            .contains("soulStore.store(cachedManifest)");

        assertThat(body)
            .as("race-tight idempotency re-check after the await (in case two scene-closes "
                + "for the same id raced through the pipeline)")
            .contains("dup");
    }

    @Test
    void callVoiceInnerMonologue_uses_fireOneShotVoicePrompt_no_stub() throws Exception {
        var src = sourceText();
        int start = src.indexOf("private CompletionStage<String> "
            + "callVoiceInnerMonologue(");
        assertThat(start)
            .as("callVoiceInnerMonologue method must exist")
            .isGreaterThan(0);
        int end = src.indexOf("\n    /**", start + 100);
        if (end < 0) end = src.indexOf("\n    private ", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        assertThat(body)
            .as("inner monologue MUST hit the real :8201 helper, not a fail-fast stub")
            .contains("fireOneShotVoicePrompt(")
            .contains("buildInnerMonologuePrompt")
            .contains("\"inner-\"")
            .contains("INNER_VOICE_TIMEOUT");

        assertThat(body)
            .as("no stub remains: must NOT return failedFuture as the body")
            .doesNotContain("CompletableFuture.failedFuture(")
            .doesNotContain("deferred to batch sweep");
    }

    @Test
    void renderFeltViaVoice_uses_fireOneShotVoicePrompt_no_stub() throws Exception {
        var src = sourceText();
        int start = src.indexOf("private CompletionStage<String> "
            + "renderFeltViaVoice(");
        assertThat(start).isGreaterThan(0);
        int end = src.indexOf("\n    /**", start + 100);
        if (end < 0) end = src.indexOf("\n    private ", start + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        assertThat(body)
            .as("felt synthesis also uses the shared helper — closes the pre-existing "
                + "Phase-D stub that left journal mirrors with a pending placeholder")
            .contains("fireOneShotVoicePrompt(")
            .contains("buildFeltPrompt")
            .contains("\"felt-\"")
            .contains("FELT_VOICE_TIMEOUT");

        assertThat(body)
            .as("no stub remains on the felt path either")
            .doesNotContain("CompletableFuture.failedFuture(")
            .doesNotContain("deferred to batch sweep");
    }

    @Test
    void fireOneShotVoicePrompt_routes_via_pendingOneShotVoice_and_cap_quick() throws Exception {
        var src = sourceText();
        // The helper grew a delegating overload (the 6-arg form forwards to the
        // 7-arg form with an explicit backend). The FIRST occurrence is now the
        // five-line delegator, which contains none of the machinery this test
        // exists to pin — slicing it made the suite red for days over wiring
        // that was intact the whole time. Assert over EVERY overload's body:
        // the machinery must exist in one of them, and cap:quick in the chain.
        int start = src.indexOf(
            "private CompletionStage<String> fireOneShotVoicePrompt(");
        assertThat(start)
            .as("the shared one-shot voice helper must exist")
            .isGreaterThan(0);
        int last = src.lastIndexOf(
            "private CompletionStage<String> fireOneShotVoicePrompt(");
        int end = src.indexOf("\n    /**", last + 100);
        if (end < 0) end = src.indexOf("\n    private ", last + 100);
        var body = src.substring(start, end > 0 ? end : src.length());

        assertThat(body)
            .as("routes to 4B voice via cap:quick (the §10 design memo's voice :8201 constraint)")
            .contains("cap:quick");

        assertThat(body)
            .as("uses pendingOneShotVoice callback map + timeout machinery (fire-once)")
            .contains("pendingOneShotVoice.put(")
            .contains("OneShotVoiceTimeout(")
            .contains("AtomicBoolean")
            .contains("compareAndSet(false, true)");
    }

    @Test
    void onInferenceResponse_routes_one_shot_returns_before_polish_branch() throws Exception {
        var src = sourceText();
        int handlerStart = src.indexOf("private Behavior<Command> onInferenceResponse(");
        assertThat(handlerStart).isGreaterThan(0);
        int handlerEnd = src.indexOf("\n    private", handlerStart + 200);
        var body = src.substring(handlerStart,
            handlerEnd > 0 ? handlerEnd : Math.min(handlerStart + 8000, src.length()));

        int oneShotBranch = body.indexOf("pendingOneShotVoice.containsKey(");
        int polishBranch = body.indexOf("startsWith(\"polish-\")");

        assertThat(oneShotBranch)
            .as("one-shot voice branch must exist in onInferenceResponse")
            .isGreaterThan(0);
        assertThat(polishBranch)
            .as("polish branch must still exist (unchanged behavior)")
            .isGreaterThan(0);
        assertThat(oneShotBranch)
            .as("one-shot branch runs BEFORE polish so its requestIds don't accidentally "
                + "fall through to polish post-processing (which has the expansion-cap guard "
                + "tuned for user-facing speech, not for soul/journal prose)")
            .isLessThan(polishBranch);
    }

    @Test
    void OneShotVoiceTimeout_command_exists_and_is_handled() throws Exception {
        var src = sourceText();

        assertThat(src)
            .as("OneShotVoiceTimeout command record declared")
            .contains("private record OneShotVoiceTimeout(String requestId) implements Command {}");

        assertThat(src)
            .as("OneShotVoiceTimeout has a registered handler that fires Optional.empty()")
            .contains(".onMessage(OneShotVoiceTimeout.class")
            .contains("Optional.empty()");
    }

    @Test
    void felt_and_inner_use_distinct_request_prefixes() throws Exception {
        var src = sourceText();
        assertThat(src)
            .as("felt and inner use distinct requestId prefixes so log grep works "
                + "even though routing is by map-membership not prefix-match")
            .contains("\"felt-\"")
            .contains("\"inner-\"");
    }
}
