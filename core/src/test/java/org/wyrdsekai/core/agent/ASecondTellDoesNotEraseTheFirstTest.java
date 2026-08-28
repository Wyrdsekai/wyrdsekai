package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two messages that arrive while she is busy are both owed a reply.
 *
 * <h2>What went wrong</h2>
 * The deferred-trigger slot was single-shot. Battery run cpB2 (2026-08-23): an ask
 * arrived mid-turn and was deferred; the next ask arrived before the first was
 * replayed and OVERWROTE the slot. The overwrite warn fired — and the first message
 * still died. A warn is not a fix; the queue is.
 */
class ASecondTellDoesNotEraseTheFirstTest {

    private static String source(String cls) throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/" + cls + ".java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("the companion parks deferred messages in a queue, not a slot")
    void companionUsesAQueue() throws Exception {
        var src = source("CompanionActor");
        assertThat(src).contains("Deque<WorldEvent.Said> deferredTriggers");
        assertThat(Pattern.compile("deferredTrigger\\s*=").matcher(src).find())
            .as("no single-slot write may remain — every path goes through the queue")
            .isFalse();
    }

    @Test
    @DisplayName("both busy-arrival paths (room say, tell) enqueue through the same door")
    void bothDeferPathsEnqueue() throws Exception {
        var src = source("CompanionActor");
        // deferTrigger(...) is the one door: cap + addLast + sweep armed.
        var calls = Pattern.compile("deferTrigger\\((said|syntheticSaid)\\);")
            .matcher(src).results().count();
        assertThat(calls)
            .as("room-say defer and tell defer must both use deferTrigger()")
            .isGreaterThanOrEqualTo(2);
        assertThat(src).contains("deferredTriggers.addLast(");
        assertThat(src).contains("DEFERRED_TRIGGER_CAP");
    }

    @Test
    @DisplayName("promotion never clobbers a pending message that hasn't been served")
    void promotionYieldsToPending() throws Exception {
        var src = source("CompanionActor");
        var body = src.substring(src.indexOf("private void promoteDeferredTrigger"));
        body = body.substring(0, body.indexOf("\n    }"));
        assertThat(body)
            .as("promoteDeferredTrigger polls only when pendingTrigger is free")
            .contains("if (pendingTrigger == null)");
        assertThat(body)
            .as("while messages remain queued the sweep stays armed")
            .contains("if (!deferredTriggers.isEmpty())");
    }

    @Test
    @DisplayName("the sweep drains the queue head, oldest first")
    void sweepDrainsOldestFirst() throws Exception {
        var src = source("CompanionActor");
        assertThat(src).contains("deferredTriggers.pollFirst()");
        var sweep = src.substring(src.indexOf("private Behavior<Command> onDeferredTriggerSweep"));
        sweep = sweep.substring(0, sweep.indexOf("\n    }"));
        assertThat(sweep).contains("promoteDeferredTrigger(");
    }

    @Test
    @DisplayName("the warden and chief engineer got the same queue, not the same bug")
    void siblingActorsAlsoQueue() throws Exception {
        for (var cls : new String[] {"WardenActor", "ChiefEngineerActor"}) {
            var src = source(cls);
            assertThat(src)
                .as(cls + " parks deferred messages in a queue")
                .contains("Deque<WorldEvent.Said> deferredTriggers");
            assertThat(Pattern.compile("deferredTrigger\\s*=").matcher(src).find())
                .as(cls + " has no single-slot write left")
                .isFalse();
        }
    }
}
