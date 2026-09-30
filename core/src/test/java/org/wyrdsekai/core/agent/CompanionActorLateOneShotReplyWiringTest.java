package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A one-shot request (felt-, cultural-, inner-, langgate-, langheal-, chronicle-, dream-) whose
 * timeout fired is still held by the router, and its reply still comes. The timeout takes the id
 * out of pendingOneShotVoice, so the reply used to match nothing in onInferenceResponse and fall
 * through to the turn handler: it took the trigger of the turn in flight (or ended a ReAct step)
 * and was spoken, written to the trail and kept in memory as her own words (2026-09-22).
 * Source-text per the package pattern (see {@link CompanionActorInnerMonologueWiringTest}): the
 * one-shots are fired from private paths (scene close, language heal, chronicle, dream) that a
 * test cannot drive to a timeout without a full actor harness.
 */
class CompanionActorLateOneShotReplyWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    private static String onInferenceResponseBody(String src) {
        int start = src.indexOf("private Behavior<Command> onInferenceResponse(");
        assertThat(start).as("onInferenceResponse must exist").isGreaterThan(0);
        int end = src.indexOf("\n    private", start + 200);
        return src.substring(start, end > 0 ? end : src.length());
    }

    @Test
    void timeout_remembers_the_id_only_while_the_one_shot_is_still_pending() throws Exception {
        var src = sourceText();
        int start = src.indexOf(".onMessage(OneShotVoiceTimeout.class");
        assertThat(start).as("OneShotVoiceTimeout handler must exist").isGreaterThan(0);
        int end = src.indexOf(".onMessage(", start + 10);
        var handler = src.substring(start, end > 0 ? end : src.length());

        int pending = handler.indexOf("if (callback != null)");
        int remember = handler.indexOf("timedOutOneShotIds.add(msg.requestId())");
        int fallback = handler.indexOf("callback.accept(Optional.empty())");
        int done = handler.indexOf("return this;");
        assertThat(pending).as("the handler still checks the callback is pending").isGreaterThan(0);
        assertThat(remember)
            .as("a timed-out id is remembered inside the still-pending branch, so a reply that "
                + "already completed the one-shot is never marked late")
            .isGreaterThan(pending)
            .isLessThan(done);
        assertThat(fallback)
            .as("the caller still gets its empty fallback on timeout")
            .isGreaterThan(pending)
            .isLessThan(done);
        assertThat(handler)
            .as("a timeout is visible at INFO, not only DEBUG")
            .contains("log.info(");
    }

    @Test
    void late_reply_is_dropped_before_any_routing_or_turn_state() throws Exception {
        var body = onInferenceResponseBody(sourceText());
        int guard = body.indexOf("timedOutOneShotIds.remove(respId)");
        assertThat(guard).as("late one-shot guard in onInferenceResponse").isGreaterThan(0);

        int drop = body.indexOf("return Behaviors.same();", guard);
        int oneShotBranch = body.indexOf("pendingOneShotVoice.containsKey(respId)");
        assertThat(oneShotBranch).as("the one-shot branch must still exist").isGreaterThan(0);
        assertThat(drop)
            .as("the guard returns before the one-shot branch")
            .isGreaterThan(guard)
            .isLessThan(oneShotBranch);
        assertThat(body.substring(guard, drop))
            .as("a dropped reply is logged at INFO")
            .contains("log.info(\"Dropping late one-shot reply");

        for (var later : List.of(
                "startsWith(\"polish-\")",
                "inFlightInferenceGen != resetGeneration",
                "state = State.IDLE;",
                "handleReactInferenceResult(",
                "var wasTrigger = pendingTrigger;")) {
            int at = body.indexOf(later);
            assertThat(at).as(later + " must still be in the handler").isGreaterThan(0);
            assertThat(drop)
                .as("a late reply leaves before " + later + " (no trigger taken, no ReAct "
                    + "step ended, nothing spoken or recorded)")
                .isLessThan(at);
        }
    }

    @Test
    void timed_out_id_memory_is_bounded() throws Exception {
        var src = sourceText();
        int decl = src.indexOf("timedOutOneShotIds = Collections.newSetFromMap(");
        assertThat(decl).as("bounded timed-out id set declared").isGreaterThan(0);
        var window = src.substring(decl, Math.min(src.length(), decl + 400));
        assertThat(window)
            .as("oldest ids are evicted so ids whose reply never comes cannot pile up")
            .contains("removeEldestEntry")
            .contains("size() > TIMED_OUT_ONE_SHOT_CAP");
    }
}
