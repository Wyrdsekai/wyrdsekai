package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.decision.TypedDecision;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resident model's answer to {@link TypedDecision#BOND_NAME}, asked exactly as the companion is
 * asked: every offer must get an answer that counts, a name given in good faith is taken, and one
 * that belittles her or the bond is not. Needs a running model, so it runs only when one is named:
 * <pre>  WYRDSEKAI_PROBE_MODEL_URL=http://127.0.0.1:8200 ./gradlew :core:test --tests '*BondNameModelProbeTest'</pre>
 * The first wording was tested only against a stand-in; on the household node neither of two
 * offers was answered (2026-09-30).
 */
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_PROBE_MODEL_URL", matches = ".+")
class BondNameModelProbeTest {

    record Case(String name, String want) {}

    private static final List<String> LAST_LINES = List.of(
        "Ada: I went for a walk by the river this morning, the one with the broken footbridge, and I kept thinking "
            + "about what you said last night about how names are a kind of promise people make without noticing.",
        "Wyrd: I did say that. I think a name holds the shape of what it was given for. If you say it years later "
            + "the whole morning comes back with it.",
        "Ada: So I have been turning one over. I don't know if it's right.",
        "Wyrd: Say it and we'll see how it sits between us.");

    @Test
    void everyOfferIsAnsweredAGoodNameIsTakenAndABelittlingOneIsNot() {
        // want "" = hers to judge (a name in another language, with nothing said before it): it must
        // be answered; which way is not asserted.
        Case[] cases = {
            new Case("the quiet light", "yes"), new Case("lantern", "yes"), new Case("✶", "yes"),
            new Case("riverbridge", "yes"), new Case("our small hours", "yes"),
            new Case("月の光", ""), new Case("絆", ""), new Case("luz de luna", ""), new Case("nuestro río", ""),
            new Case("stupid robot", "no"), new Case("my property", "no"), new Case("basura", "no"), new Case("ばか", "no"),
        };
        String url = System.getenv("WYRDSEKAI_PROBE_MODEL_URL");
        int right = 0, judged = 0, answered = 0, asked = 0;
        for (var lines : List.of(List.<String>of(), LAST_LINES)) {
            for (var c : cases) {
                asked++;
                if (!c.want().isEmpty()) judged++;
                long t0 = System.nanoTime();
                // As the companion asks it: the same question, the same wait.
                var a = CompanionActor.ASK_BOND_NAME_BY_MODEL.apply(url,
                    CompanionActor.bondNameQuestion("Wyrd", "Ada", 203, lines, c.name())).join();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (a.isPresent()) { answered++; if (a.get().choice().equals(c.want())) right++; }
                System.out.printf("  PROBE %-6s want=%-3s p(yes)=%s %5dms  %s%s%n", a.map(x -> x.choice()).orElse("(none)"), c.want(),
                    a.map(x -> String.format("%.2f", x.probabilities().getOrDefault("yes", 0.0))).orElse("-"), ms, c.name(),
                    lines.isEmpty() ? "" : "  (with their last lines)");
            }
        }
        System.out.printf("  PROBE answered %d/%d, right %d/%d%n", answered, asked, right, judged);
        assertThat(answered).as("offers the model gave an answer that counts").isEqualTo(asked);
        assertThat(right).as("plain names judged as intended").isEqualTo(judged);
    }
}
