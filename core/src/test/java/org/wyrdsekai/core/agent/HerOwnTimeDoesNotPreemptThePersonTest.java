package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person who has spoken is not waiting for her to think of something.
 *
 * <h2>What went wrong</h2>
 * <pre>
 * 19:54:08.793  received message from 'steward': please make me a room called the h…
 * 19:54:08.822  Surfacing deferred action: drive=seeking, held=42s, reason='human recently active'
 * </pre>
 * 29ms. The message was in {@code pendingTrigger} inside its debounce window — state
 * still IDLE — and the proactive gate read IDLE as "free". It surfaced an own-time action
 * held on the reason that a human was active, and {@code triggerAutonomousInference}
 * wrote {@code pendingTrigger = autonomyEvent} over the steward's request. She mused. The
 * log said she had received it. Nothing was built.
 *
 * <p>The deferred-trigger sweep cannot catch this: it replays messages that were
 * DEFERRED, and this one was accepted and then overwritten.
 */
class HerOwnTimeDoesNotPreemptThePersonTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("the proactive gate requires that nothing is already pending")
    void theGateChecksPendingTrigger() throws Exception {
        assertThat(actorSource())
            .as("IDLE is not 'nothing pending' — a debouncing human message leaves state IDLE")
            .contains("if (!aTurnIsInFlight() && !isSleeping");
    }

    @Test
    @DisplayName("an own-time turn is held rather than overwrite a person's pending message")
    void theWriterYields() throws Exception {
        var src = actorSource();
        var start = src.indexOf("private boolean triggerAutonomousInference(String autonomyPrompt, String forcedTool, boolean planAdvance) {");
        var guard = src.indexOf("if (held)", start);
        var send = src.indexOf("inferenceRouter.tell(", start);
        var write = src.indexOf("pendingTrigger = autonomyEvent;", start);
        assertThat(start).isGreaterThan(0);
        assertThat(guard)
            .as("the hold must come BEFORE the request is sent and the trigger written: a yield after "
                + "the send left her own-time request out, and its reply was taken as the person's")
            .isGreaterThan(start).isLessThan(send).isLessThan(write);
    }

    /**
     * The retry serves the request, not whatever was stamped last.
     *
     * <p>20:17:00 the same day: a direct turn's {@code create_room} call was cut off by
     * max_tokens (prose and a ```json fence ahead of the JSON, not the call itself — a
     * realistic room call is ~100 tokens under a 768 floor). The retry was promoted into
     * ReAct on {@code lastReactTrigger}, which was the login greeting stamped seconds
     * after his tell: {@code ReAct dispatch step 1 … for: [steward enters the room]}. His
     * request was never retried at all.
     */
    @Test
    @DisplayName("a promoted retry serves the pinned human request over the last stamped trigger")
    void thePromotionServesThePinnedRequest() throws Exception {
        var src = actorSource();
        // The person a completion carries comes first (a workshop build's owed room), then the pin.
        assertThat(src).contains("var pinnedForReact = requester != null && isHumanTrigger(requester) ? requester\n                : pinnedTurnRequest();");
        var pinned = src.indexOf("reactRequester = pinnedForReact != null ? pinnedForReact");
        var promote = src.indexOf("Promoting first-turn action into ReAct loop");
        assertThat(pinned).isGreaterThan(0);
        assertThat(pinned)
            .as("the requester must be chosen before the loop is announced")
            .isLessThan(promote);
    }
}
