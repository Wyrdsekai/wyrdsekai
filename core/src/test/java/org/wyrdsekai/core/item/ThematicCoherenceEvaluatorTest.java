package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ThematicCoherenceEvaluator — composition evaluation.
 */
class ThematicCoherenceEvaluatorTest {

    private final ThematicCoherenceEvaluator evaluator = new ThematicCoherenceEvaluator();

    @Test
    void compatibleItemsScoreHighViaFastPath() {
        var crystal = makeItem("crystal", "observation",
            new ThematicProfile(List.of("observation"), List.of("sight", "clarity"), List.of("observe"), 0.1));
        var oracleLens = makeItem("oracle-lens", "observation crystal",
            new ThematicProfile(List.of("observation", "knowledge"), List.of("foresight", "sight"), List.of("predict", "observe"), 0.2));

        var result = evaluator.evaluate(crystal, oracleLens);
        assertTrue(result.compatible(), "Crystal + Oracle Lens should be compatible");
        assertTrue(result.score() > 0.2, "Score should be meaningful: " + result.score());
        assertEquals("fast", result.evaluationPath());
        assertNotNull(result.suggestion());
    }

    @Test
    void incompatibleItemsGetBindingHint() {
        var key = makeItem("room-key", "access key",
            new ThematicProfile(List.of("access"), List.of("passage", "trust"), List.of("unlock"), 0.0));
        var consumable = makeItem("potion", "clarity potion",
            new ThematicProfile(List.of("state"), List.of("clarity", "focus"), List.of("enhance"), 0.0));

        var result = evaluator.evaluate(key, consumable);
        assertFalse(result.compatible(), "Key + Consumable should not be naturally compatible");
        assertNotNull(result.bindingHint(), "Should provide a binding hint");
    }

    @Test
    void portalAndAutomatorShareDomain() {
        var portal = makeItem("web-window", "web portal",
            new ThematicProfile(List.of("communication", "knowledge"), List.of("portal", "window"), List.of("fetch", "view"), 0.1));
        var automator = makeItem("signal-mirror", "alert system",
            new ThematicProfile(List.of("communication", "observation"), List.of("vigilance", "signal"), List.of("watch", "alert"), 0.0));

        var result = evaluator.evaluate(portal, automator);
        // Share "communication" domain — should have some overlap
        assertTrue(result.score() > 0.0, "Portal + Automator should have nonzero overlap");
    }

    @Test
    void crystalAndBookNeedNarrativeEvaluation() {
        var crystal = makeItem("crystal", "observation crystal",
            new ThematicProfile(List.of("observation"), List.of("sight", "clarity", "truth"), List.of("observe", "sense"), 0.0));
        var book = makeItem("book", "knowledge container",
            new ThematicProfile(List.of("knowledge"), List.of("memory", "wisdom", "record"), List.of("read", "search"), 0.0));

        var result = evaluator.evaluate(crystal, book);
        // No overlap in domains, symbols, or actions — needs LLM or binding element
        assertFalse(result.compatible(), "Crystal + Book should need narrative evaluation");
        assertNotNull(result.bindingHint());
    }

    @Test
    void cacheReturnsSameResult() {
        var item1 = makeItem("a", "item A",
            new ThematicProfile(List.of("knowledge"), List.of("sight"), List.of("search"), 0.0));
        var item2 = makeItem("b", "item B",
            new ThematicProfile(List.of("knowledge"), List.of("sight"), List.of("read"), 0.0));

        var result1 = evaluator.evaluate(item1, item2);
        var result2 = evaluator.evaluate(item1, item2);
        assertSame(result1, result2, "Cached result should be returned");
        assertEquals(1, evaluator.cacheSize());
    }

    @Test
    void cacheIsOrderIndependent() {
        var item1 = makeItem("x", "item X",
            new ThematicProfile(List.of("creation"), List.of("craft"), List.of("build"), 0.0));
        var item2 = makeItem("y", "item Y",
            new ThematicProfile(List.of("creation"), List.of("forge"), List.of("craft"), 0.0));

        var resultAB = evaluator.evaluate(item1, item2);
        var resultBA = evaluator.evaluate(item2, item1);
        assertEquals(resultAB.score(), resultBA.score(), 0.001);
        assertEquals(1, evaluator.cacheSize(), "Same pair reversed should hit cache");
    }

    @Test
    void clearCacheWorks() {
        var item = makeItem("c", "item C",
            new ThematicProfile(List.of("state"), List.of("fire"), List.of("burn"), 0.0));
        evaluator.evaluate(item, item);
        assertEquals(1, evaluator.cacheSize());
        evaluator.clearCache();
        assertEquals(0, evaluator.cacheSize());
    }

    @Test
    void llmEvaluatorUsedForNovelCompositions() {
        var withLlm = new ThematicCoherenceEvaluator((desc1, desc2) ->
            "Yes, these items are coherent — the crystal's sight could guide the key's access.");

        var crystal = makeItem("crystal", "sight crystal",
            new ThematicProfile(List.of("observation"), List.of("sight"), List.of("observe"), 0.0));
        var key = makeItem("key", "access key",
            new ThematicProfile(List.of("access"), List.of("passage"), List.of("unlock"), 0.0));

        var result = withLlm.evaluate(crystal, key);
        assertTrue(result.compatible(), "LLM said yes — should be compatible");
        assertEquals("llm", result.evaluationPath());
        assertTrue(result.score() > 0.5);
    }

    @Test
    void llmEvaluatorRejectsIncompatible() {
        var withLlm = new ThematicCoherenceEvaluator((desc1, desc2) ->
            "No, these items are unrelated — a potion and a key have no narrative connection.");

        var potion = makeItem("potion", "potion",
            new ThematicProfile(List.of("state"), List.of("liquid"), List.of("drink"), 0.0));
        var key = makeItem("key", "key",
            new ThematicProfile(List.of("access"), List.of("metal"), List.of("turn"), 0.0));

        var result = withLlm.evaluate(potion, key);
        assertFalse(result.compatible());
        assertEquals("llm", result.evaluationPath());
    }

    @Test
    void evaluateProfilesDirectly() {
        var p1 = new ThematicProfile(List.of("knowledge"), List.of("sight", "wisdom"), List.of("search"), 0.0);
        var p2 = new ThematicProfile(List.of("knowledge"), List.of("sight", "record"), List.of("read"), 0.0);

        var result = evaluator.evaluateProfiles(p1, "item1", p2, "item2");
        assertTrue(result.compatible());
        assertTrue(result.score() > 0.2);
    }

    @Test
    void nullProfileReturnsIncompatible() {
        var result = evaluator.evaluateProfiles(null, "a", null, "b");
        assertFalse(result.compatible());
    }

    @Test
    void itemsWithNoProfilesAndNoLlm() {
        var item1 = new ToolItem("a", "A", "desc", "tool", List.of(), null, null, null, null, null,
            "test", Instant.now(), true, null, null, null);
        var item2 = new ToolItem("b", "B", "desc", "tool", List.of(), null, null, null, null, null,
            "test", Instant.now(), true, null, null, null);

        var result = evaluator.evaluate(item1, item2);
        assertFalse(result.compatible());
    }

    // ─── Helper ─────────────────────────────────────────────────

    private ToolItem makeItem(String id, String description, ThematicProfile thematic) {
        return new ToolItem(id, id, description, "tool", List.of(), null, null, null, null, null,
            "test", Instant.now(), true, null, thematic, null);
    }
}
