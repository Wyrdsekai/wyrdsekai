package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.decision.TypedDecision;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resident model's answer to {@link TypedDecision#UNEXPECTED} on lines whose answer is plain:
 * replies that go on the way the conversation was going, and news, a sudden turn, a punchline,
 * an arrival. Needs a running model, so it runs only when one is named:
 * <pre>  WYRDSEKAI_PROBE_MODEL_URL=http://127.0.0.1:8200 ./gradlew :core:test --tests '*UnexpectedLineModelProbeTest'</pre>
 * On the 35B (home-server, 2026-09-29): 13 of 13 right, p(new) 0.00–0.04 for the expected lines and
 * 0.88–1.00 for the others, about half a second each.
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_PROBE_MODEL_URL", matches = ".+")
class UnexpectedLineModelProbeTest {
    record Case(String convo, String line, String want) {}
    @Test
    void theModelTellsAnExpectedLineFromNewsATurnOrAPunchline() {
        String roses = "Conversation:\nWyrd: The roses by the garden wall finally opened this morning.\nrose: I think the rain last week helped them more than the watering did.\nWyrd: We should cut a few for the kitchen table before they fade.\n";
        String hearth = "Conversation:\nmia: The light in the hearth room is softer tonight.\nrose: It settles on the table like it has somewhere to be.\n";
        String bread = "Conversation:\nmasumi: I'm going to bake bread this afternoon.\nmia: That sounds lovely. What kind?\n";
        Case[] cases = {
            new Case(roses, "rose: Yes, the red ones would look lovely in the blue vase.", "expected"),
            new Case(roses, "rose: The roses smell stronger in the evening, don't they?", "expected"),
            new Case(roses, "rose: Did you hear the library lost power and every lamp went out?", "new"),
            new Case(roses, "rose: My mother called. She is coming to stay next week.", "new"),
            new Case(roses, "rose: Why did the scarecrow win an award? He was outstanding in his field.", "new"),
            new Case(roses, "rose: I read that the old bridge downtown is going to be torn down.", "new"),
            new Case(hearth, "mia: I like how it rests on the old chair too.", "expected"),
            new Case(hearth, "mia: The library's shelves were rearranged while we slept. Every book is somewhere else.", "new"),
            new Case(hearth, "mia: Operator is back! He just came through the door.", "new"),
            new Case(hearth, "mia: It feels like the room is listening.", "expected"),
            new Case(bread, "operator: Sourdough, I think. The starter is ready.", "expected"),
            new Case(bread, "operator: Actually, I just got the job offer I was waiting for!", "new"),
            new Case(bread, "operator: Rye, probably, with caraway.", "expected"),
        };
        String url = System.getenv("WYRDSEKAI_PROBE_MODEL_URL");
        int right = 0, answered = 0;
        for (var c : cases) {
            long t0 = System.nanoTime();
            var a = TypedDecision.ask(url, TypedDecision.UNEXPECTED, c.convo() + "New line, " + c.line());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (a.isPresent()) { answered++; if (a.get().choice().equals(c.want())) right++; }
            System.out.printf("  PROBE %-9s want=%-8s p(new)=%s %5dms  %s%n", a.map(x -> x.choice()).orElse("(none)"), c.want(),
                a.map(x -> String.format("%.2f", x.probabilities().getOrDefault("new", 0.0))).orElse("-"), ms, c.line());
        }
        System.out.printf("  PROBE answered %d/%d, right %d/%d%n", answered, cases.length, right, cases.length);
        assertThat(right).as("lines the model judged as intended").isEqualTo(cases.length);
    }
}
