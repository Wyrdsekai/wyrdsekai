package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.affordance.RequestRelevance;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;
import org.wyrdsekai.core.item.ToolSearchIndex;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test we were running was the wrong test.
 *
 * <p>We had been asking a companion <b>"what is 17 times 3?"</b> and calling it a failure when she
 * answered "51" without touching the calculator. But she can do 17×3 in her head, reliably. In that
 * case the calculator <b>is not needed</b>, and reaching for it would be the odd behaviour. What we
 * were actually measuring was <i>compliance</i> — will she perform the tool call we have decided she
 * ought to perform — and dressing it up as capability. A test that punishes an agent for correctly
 * judging that she doesn't need a tool is not a test of tool use. It is a test of obedience, and it
 * would have driven us to force tool calls she has no reason to make.
 *
 * <p>So the arithmetic here is arithmetic <b>no one does in their head</b>: six-digit products,
 * long division to a decimal, the standard deviation of a list. Now a tool call is the honest move,
 * not the compliant one — and if she skips the tool and confabulates a number, that is a real
 * finding rather than an artefact of our scoring.
 *
 * <p>What we owe her is that the tool be REACHABLE when the need is real (see
 * {@code ToolMenuIsNotArbitraryTest} — on second-node it was not, and she was blamed for the consequence).
 * What we do NOT get to demand is that she use it when it isn't.
 *
 * <p>These are unit-level: they assert the calculator is ranked onto the menu for a request that
 * genuinely needs it. Whether the model then calls it is the live question, and it is only a fair
 * question once the tool is actually on the menu.
 */
class ToolNeedIsGenuineTest {

    /** Arithmetic a competent mind cannot reliably do unaided — a real reason to want a tool. */
    private static final String[] GENUINE_NEED = {
        "what is 48273 times 9182?",
        "divide 91435 by 317 and give me three decimal places",
        "what's the standard deviation of 12, 47, 8, 93, 21, 66, 5?",
        "compute 17.5 percent of 84920",
        "what is 2 to the power of 37?",
    };

    /** Arithmetic she can simply do. Reaching for a tool here is not required of her. */
    private static final String[] NO_NEED = {
        "what is 17 times 3?",
        "what's 10 plus 5?",
    };

    private static ToolSearchIndex menu() {
        var index = new ToolSearchIndex();
        index.register(tool("tell_agent", "Speak to another agent in the zone"));
        index.register(tool("summon_familiar", "Summon a familiar to assist with a task"));
        index.register(tool("dispatch_bunshin", "Dispatch a bunshin to work autonomously"));
        index.register(tool("chronicle", "Record an event in the chronicle"));
        index.register(tool("searching_glass", "Search the web for current information"));
        index.register(tool("calculator", "Evaluate an arithmetic expression precisely"));
        return index;
    }

    @Test
    void theCalculatorIsReachableWhenTheNeedIsReal() {
        for (var request : GENUINE_NEED) {
            var results = menu().search(request, 6);
            assertEquals("calculator", results.getFirst().function().name(),
                "the calculator must top the menu for: \"" + request + "\" — this is arithmetic "
                    + "nobody does unaided, so the tool is the honest move and she must be able "
                    + "to reach it");
        }
    }

    /**
     * Offering the tool is not the same as demanding it. For arithmetic she can just do, the
     * calculator may rank — we simply must never build a mechanism that FORCES the call, because
     * the agent's judgment that she doesn't need it is a correct judgment.
     */
    @Test
    void easyArithmeticStillLeavesHerAWholeMenu() {
        for (var request : NO_NEED) {
            var results = menu().search(request, 6);
            assertTrue(results.stream().anyMatch(t -> t.function().name().equals("tell_agent")),
                "she must still be able to simply ANSWER \"" + request + "\" — if the only thing "
                    + "on the menu is the calculator, we have not enabled tool use, we have "
                    + "mandated it");
        }
    }

    /** Bare expressions carry no cue word at all — "48273 * 9182" says neither "times" nor "math". */
    @Test
    void bareExpressionsAreRecognisedAsArithmetic() {
        assertTrue(RequestRelevance.looksArithmetic("48273 * 9182"));
        assertTrue(RequestRelevance.looksArithmetic("what's 91435 / 317 ?"));
        assertTrue(RequestRelevance.looksArithmetic("2^37"));
        assertTrue(RequestRelevance.looksArithmetic("17 x 3"));
    }

    @Test
    void ordinaryProseIsNotMistakenForArithmetic() {
        assertTrue(!RequestRelevance.looksArithmetic("how are you feeling today?"));
        assertTrue(!RequestRelevance.looksArithmetic("tell me about the garden"));
    }

    private static ToolDefinition tool(String name, String description) {
        return ToolDefinition.function(name, description,
            new LinkedHashMap<>(Map.of("type", "object")));
    }
}
