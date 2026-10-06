package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household on 0.5.4 whose voice lane ran on a CPU reported the language healer burning sixteen
 * cores around the clock: every re-render ran past its 12 s wait, nothing stopped the brain working
 * on it, the next re-render queued behind it, none finished, and her own turns timed out behind
 * them (2026-10-05). These pin the shape of the repair in the actor and the router.
 */
class TheLanguageHealerWaitsForAQuietLaneTest {

    private static String src(String rel) throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        return Files.readString(base.resolve(rel));
    }

    @Test
    void a_one_shot_past_its_timeout_is_taken_back_from_the_router() throws Exception {
        var actor = src("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        int handler = actor.indexOf(".onMessage(OneShotVoiceTimeout.class");
        int end = actor.indexOf(".onMessage(", handler + 10);
        assertThat(actor.substring(handler, end))
            .contains("inferenceRouter.tell(new InferenceRouter.CancelRequest(msg.requestId()))");
        var router = src("core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java");
        int failure = router.indexOf("private Behavior<Command> onInferFailure(InferFailure failure) {");
        int cancelledCheck = router.indexOf("if (cancelled.remove(failure.requestId())) {", failure);
        int blame = router.indexOf("healthStatus.put(failure.failedBackend(), false);", failure);
        assertThat(cancelledCheck).as("a cancelled request ends before the backend is blamed").isGreaterThan(failure).isLessThan(blame);
        assertThat(router).contains(".onMessage(CancelRequest.class, this::onCancelRequest)");
    }

    @Test
    void the_pass_runs_only_on_a_quiet_lane_and_stops_at_the_first_non_answer() throws Exception {
        var actor = src("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        int pass = actor.indexOf("private void maybeReconcileFragmentLanguages() {");
        int quiet = actor.indexOf("if (!langHealLaneIsQuiet()) return;", pass);
        int consume = actor.indexOf("lastFragmentLangReconcile = now;", pass);
        assertThat(quiet).as("the quiet check comes before the interval is spent").isGreaterThan(pass).isLessThan(consume);
        assertThat(actor).contains("if (now.isBefore(langHealBackoffUntil)) return;");
        for (var stage : new String[]{"private CompletionStage<Void> reconcileFragmentStage(", "private CompletionStage<Void> reconcileMemoryNodeStage("}) {
            int at = actor.indexOf(stage);
            assertThat(at).isPositive();
            int noAnswer = actor.indexOf("if (ex != null) { langHealDidNotAnswer(", at);
            int strike = actor.indexOf("langHealStrike(", at);
            assertThat(noAnswer).as(stage + " treats no answer as the lane's, not the note's").isGreaterThan(at).isLessThan(strike);
            assertThat(actor.indexOf("if (langHealPassStopped || (!langHealForced && !langHealLaneIsQuiet())) {", at)).isGreaterThan(at).isLessThan(noAnswer);
        }
        assertThat(actor).contains("langHealBackoffUntil = Instant.now().plus(LANG_HEAL_BACKOFF);");
        // Quiet is measured from other people's events, never her own: a companion busy on her own
        // time would otherwise never be quiet, and her notes never mended.
        int gate = actor.indexOf("private boolean langHealLaneIsQuiet() {");
        assertThat(actor.indexOf("Duration.between(lastOthersEventTime, Instant.now())", gate)).isGreaterThan(gate);
        assertThat(actor).contains("if (actor == null || !actor.equals(profile.entityId())) lastOthersEventTime = lastEventTime;");
        assertThat(actor).doesNotContain("langReconcileStrikes");
    }

    @Test
    void the_steward_can_ask_for_the_healing_now_and_only_the_waits_are_skipped() throws Exception {
        var actor = src("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        assertThat(actor).contains(".onMessage(HealLanguageNow.class, this::onHealLanguageNow)");
        int pass = actor.indexOf("private void maybeReconcileFragmentLanguages() {");
        int forced = actor.indexOf("if (!langHealForced) {", pass);
        int quiet = actor.indexOf("if (!langHealLaneIsQuiet()) return;", pass);
        assertThat(forced).as("the waits sit inside the not-forced branch").isGreaterThan(pass).isLessThan(quiet);
        // The per-rewrite safety stays: a non-answer still stops the pass and ends the forced run.
        assertThat(actor).contains("if (langHealPassStopped || (!langHealForced && !langHealLaneIsQuiet())) {");
        int run = actor.indexOf("chain.thenRun(() -> {", pass);
        int tell = actor.indexOf("getContext().getSelf().tell(new HealLanguageNow());", run);
        assertThat(tell).as("the chain's end asks for the next batch (pass=%d run=%d length=%d)", pass, run, actor.length())
            .isGreaterThan(run);
        int stop = actor.indexOf("asked for now: stopped — the lane did not answer", run);
        assertThat(stop).as("a non-answer ends the forced run").isGreaterThan(run).isLessThan(tell);
        var routes = src("server/src/main/java/org/wyrdsekai/server/http/SoulRoutes.java");
        assertThat(routes).contains("app.post(\"/api/soul/{did}/language/heal\", this::healLanguageNow);");
    }

    @Test
    void a_re_render_goes_to_the_full_lane_with_time_to_answer() throws Exception {
        var actor = src("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        int at = actor.indexOf("private CompletionStage<String> rerenderNoteInLanguage(");
        int call = actor.indexOf("LANG_HEAL_TIMEOUT, \"cap:full\", NowLine.NONE);", at);
        assertThat(call).isGreaterThan(at);
        assertThat(actor).contains("private static final Duration LANG_HEAL_TIMEOUT = Duration.ofSeconds(90);");
        assertThat(actor).contains("LanguageHealLedger.pathFor(WyrdConfig.get().dataDir(),");
    }
}
