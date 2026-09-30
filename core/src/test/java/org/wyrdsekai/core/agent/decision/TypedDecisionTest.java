package org.wyrdsekai.core.agent.decision;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.classifier.Classification;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The answer is read from the first token's probabilities; the head's classification has the same shape. */
class TypedDecisionTest {

    private static String completion(String... tokenLogprobPairs) {
        var sb = new StringBuilder("{\"choices\":[{\"message\":{\"content\":\"none\"},\"logprobs\":{\"content\":[{\"token\":\"none\",\"top_logprobs\":[");
        for (int i = 0; i + 1 < tokenLogprobPairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"token\":\"").append(tokenLogprobPairs[i]).append("\",\"logprob\":").append(tokenLogprobPairs[i + 1]).append('}');
        }
        return sb.append("]}]}}]}").toString();
    }

    @Test
    void theOptionsProbabilitiesComeFromTheTopLogprobs() {
        // ln(0.8) and ln(0.15); the remaining mass is on tokens that name no option
        var a = TypedDecision.fromLogprobs(completion("none", "-0.2231", "action", "-1.8971", "The", "-4.0"), TypedDecision.TASK_PRESENT).orElseThrow();
        assertThat(a.choice()).isEqualTo("none");
        assertThat(a.probabilities().get("none")).isCloseTo(0.842, within(0.01));
        assertThat(a.probabilities().get("actionable")).isCloseTo(0.158, within(0.01));
        assertThat(a.source()).isEqualTo("model-logprobs");
    }

    @Test
    void aYesInTheNamesOwnLanguageCounts() {
        // the smaller model answers a Spanish or Japanese name in that language
        var si = TypedDecision.fromLogprobs(completion("sí", "-0.2485", "yes", "-2.0", "no", "-3.5"), TypedDecision.BOND_NAME).orElseThrow();
        assertThat(si.choice()).isEqualTo("yes");
        assertThat(si.probabilities().get("yes")).isCloseTo(0.968, within(0.01));
        var hai = TypedDecision.fromLogprobs(completion("はい", "-0.3", "月の", "-2.5"), TypedDecision.BOND_NAME).orElseThrow();
        assertThat(hai.choice()).isEqualTo("yes");
        assertThat(TypedDecision.fromLogprobs(completion("いいえ", "-0.2", "はい", "-2.5"), TypedDecision.BOND_NAME).orElseThrow().choice())
            .isEqualTo("no");
        assertThat(TypedDecision.fromLogprobs(completion("since", "-0.1", "yes", "-3.0"), TypedDecision.BOND_NAME))
            .as("a word that only starts like one of them is not an answer").isEmpty();
        assertThat(TypedDecision.fromLogprobs("{\"choices\":[{\"message\":{\"content\":\"sí\"},\"logprobs\":{\"content\":[{\"token\":\"sí\",\"top_logprobs\":[{\"token\":\"sí\",\"logprob\":-0.1}]}]}}]}",
            TypedDecision.TASK_PRESENT)).as("only a question that names the word counts it").isEmpty();
    }

    @Test
    void aTokenThatNamesNoOptionLeavesNoAnswer() {
        assertThat(TypedDecision.fromLogprobs("{\"choices\":[{\"message\":{\"content\":\"Sure!\"},\"logprobs\":{\"content\":[{\"token\":\"Sure\",\"top_logprobs\":[{\"token\":\"Sure\",\"logprob\":-0.1}]}]}}]}",
            TypedDecision.TASK_PRESENT)).isEmpty();
        assertThat(TypedDecision.fromLogprobs("not json", TypedDecision.TASK_PRESENT)).isEmpty();
    }

    @Test
    void theHeadsClassificationHasTheSameShape() {
        var a = TypedDecision.fromHead(new Classification("actionable", 0.9, Map.of("actionable", 0.9, "none", 0.1), "onnx")).orElseThrow();
        assertThat(a.choice()).isEqualTo("actionable");
        assertThat(a.confidence()).isEqualTo(0.9);
        assertThat(a.source()).isEqualTo("head:onnx");
        assertThat(TypedDecision.fromHead(Classification.unavailable())).isEmpty();
        assertThat(TypedDecision.fromHead(null)).isEmpty();
    }

    @Test
    void anOptionOnlyInTheTailIsNotADecision() {
        // The first token starts an answer ("Sure"); "No" is far down the list. Renormalised over
        // the options alone that would read as none@1.0.
        assertThat(TypedDecision.fromLogprobs(completion("Sure", "-0.05", "I", "-3.2", "No", "-7.5"), TypedDecision.TASK_PRESENT)).isEmpty();
    }
}
