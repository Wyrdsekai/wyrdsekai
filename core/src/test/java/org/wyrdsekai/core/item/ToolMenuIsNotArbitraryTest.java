package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * second-node, 2026-07-13. mia was asked <b>"what is 17 times 3?"</b>. She holds 110 tools, one of which
 * is a calculator. The eight she was offered were {@code summon_familiar}, {@code dispatch_bunshin},
 * {@code bunshin_check_in}, and five more like them. She could not call a tool she was never shown,
 * so she delegated the arithmetic to the coding backend, which reported SUCCESS having touched zero
 * files.
 *
 * <p>That menu was not ranked by anything. {@link ToolSearchIndex} embedded over HTTP against
 * Ollama's {@code /api/embed} — an endpoint llama-server does not serve — so the first call 404'd,
 * embedding disabled itself at debug level, and search fell through to keyword matching. Keyword
 * matching scored zero (no tool description contains the word "times"), and the pad-to-topK branch
 * filled the menu from a {@code ConcurrentHashMap}'s {@code values()}. <b>Hash order.</b>
 *
 * <p>This is the class of bug that gets misfiled as a model failure. We wrote it up as "talks but
 * doesn't do" and pointed at the 4B. The agent behaved sensibly given a false world: shown no
 * calculator, she reached for the most general-purpose thing on the menu. <b>Fix the menu before
 * blaming the choice.</b>
 *
 * <p>These tests run WITHOUT the bundled embedding model, i.e. on the lexical fallback — the exact
 * configuration production was silently stuck in. Even there, the right tool must be reachable.
 */
class ToolMenuIsNotArbitraryTest {

    /** A menu shaped like mia's: mostly agent-society verbs, one calculator, no arithmetic words. */
    private static ToolSearchIndex ritasMenu() {
        var index = new ToolSearchIndex();
        index.register(tool("summon_familiar", "Summon a familiar to assist with a task"));
        index.register(tool("dispatch_bunshin", "Dispatch a bunshin to work autonomously"));
        index.register(tool("bunshin_check_in", "Check in on a dispatched bunshin's progress"));
        index.register(tool("tell_agent", "Speak to another agent in the zone"));
        index.register(tool("seek_sanctuary", "Withdraw to a place of safety and rest"));
        index.register(tool("bear_the_wound", "Sit with a difficult feeling rather than act"));
        index.register(tool("chronicle", "Record an event in the chronicle"));
        // Note the description: it never says "times", "multiply", or "17".
        index.register(tool("calculator", "Evaluate an arithmetic expression precisely"));
        index.register(tool("morning_briefing", "The day's forecast for a given address"));
        index.register(tool("searching_glass", "Search the web for current information"));
        return index;
    }

    @Test
    void theCalculatorIsOfferedForArithmetic() {
        var results = ritasMenu().search("what is 17 times 3?", 8);
        assertEquals("calculator", results.getFirst().function().name(),
            "asked to multiply two numbers, the calculator must be the FIRST tool offered — "
                + "on second-node it was not offered at all, and she delegated the sum to a coding agent "
                + "that did nothing and said it succeeded");
    }

    @Test
    void theWeatherToolIsOfferedForWeather() {
        var results = ritasMenu().search("what's the weather in San Francisco tomorrow?", 8);
        assertEquals("morning_briefing", results.getFirst().function().name(),
            "the forecast tool must outrank web search for a plain weather question — picking "
                + "web_search is how the model ends up inventing 'low 70s, scattered showers'");
    }

    /**
     * The heart of it. A query nobody matches must not silently become a ranking.
     *
     * <p>Padding a short result set is fine — the model wants a full menu. Padding it from a hash
     * map and returning it as though it were relevant is not: it is an arbitrary answer wearing
     * the costume of a considered one, which is precisely the "fake success is worse than dead"
     * failure this codebase keeps re-learning.
     */
    /**
     * Targets {@code keywordSearch} directly rather than {@code search}, because reaching the
     * degraded path through {@code search} depends on whether the machine running the test happens
     * to have the embedding model — which would make this pass or fail for reasons that have
     * nothing to do with the contract.
     */
    @Test
    void anUnmatchedQueryPadsInRegistrationOrderNotHashOrder() {
        var order = List.of("alpha_tool", "beta_tool", "gamma_tool", "delta_tool", "epsilon_tool");
        var index = new ToolSearchIndex();
        for (var name : order) index.register(tool(name, "Does a thing called " + name));

        var results = index.keywordSearch("zzzz nothing here matches this at all", 5).stream()
            .map(t -> t.function().name()).toList();

        assertEquals(order, results,
            "with nothing to rank, the menu must fall back to the caller's curated registration "
                + "order — never to Map.values() iteration order, which is what handed mia "
                + "summon_familiar/dispatch_bunshin/bunshin_check_in when she was asked to multiply");
    }

    /** Registration order must survive the map, whatever the names hash to. */
    @Test
    void registrationOrderIsStableAcrossManyTools() {
        var index = new ToolSearchIndex();
        var expected = new java.util.ArrayList<String>();
        for (int i = 0; i < 40; i++) {
            var name = "tool_" + i;
            expected.add(name);
            index.register(tool(name, "Description " + i));
        }
        var got = index.all().stream().map(t -> t.function().name()).toList();
        assertEquals(expected, got, "all() must preserve insertion order");
    }

    /** The tool's NAME is matchable, not just its prose — "use your calculator" must find it. */
    @Test
    void aToolIsFoundByItsOwnName() {
        var results = ritasMenu().search("could you use your calculator for this", 8);
        assertEquals("calculator", results.getFirst().function().name());
    }

    /** Relevance must not evict everything else — the agent keeps a working menu. */
    @Test
    void theMenuStaysFull() {
        var results = ritasMenu().search("what is 17 times 3?", 8);
        assertEquals(8, results.size(), "top-K is a budget, not a filter — still offer 8 tools");
        assertTrue(results.stream().anyMatch(t -> t.function().name().equals("tell_agent")),
            "the agent must still be able to speak, not just compute");
    }

    private static ToolDefinition tool(String name, String description) {
        return ToolDefinition.function(name, description,
            new LinkedHashMap<>(Map.of("type", "object")));
    }
}
