package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillItemCodec;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillItemRetrieverTest {

    static SoulItem makeSkill(String name, String description, double significance) {
        var def = SkillItemCodec.create("graaljs", "function execute(p) {}",
            null, description, null, null);
        String json = SkillItemCodec.encode(def);
        return SoulItem.create("skill", name, json, "did:key:z6test", significance,
            name, description.split("\\s+")[0]);
    }

    @Test
    void empty_items_returns_empty() {
        var result = SkillItemRetriever.retrieve("weather", List.of(), 3);
        assertTrue(result.isEmpty());
    }

    @Test
    void null_items_returns_empty() {
        var result = SkillItemRetriever.retrieve("weather", null, 3);
        assertTrue(result.isEmpty());
    }

    @Test
    void zero_k_returns_empty() {
        var items = List.of(makeSkill("test", "A test skill", 0.5));
        var result = SkillItemRetriever.retrieve("test", items, 0);
        assertTrue(result.isEmpty());
    }

    @Test
    void null_keywords_returns_by_significance() {
        var high = makeSkill("important", "High significance skill", 0.9);
        var low = makeSkill("minor", "Low significance skill", 0.2);
        var result = SkillItemRetriever.retrieve(null, List.of(low, high), 5);
        assertEquals(2, result.size());
        assertEquals("important", result.get(0).label());
    }

    @Test
    void blank_keywords_returns_by_significance() {
        var high = makeSkill("important", "High significance skill", 0.9);
        var low = makeSkill("minor", "Low significance skill", 0.2);
        var result = SkillItemRetriever.retrieve("  ", List.of(low, high), 5);
        assertEquals(2, result.size());
        assertEquals("important", result.get(0).label());
    }

    @Test
    void keyword_match_ranks_higher() {
        var weather = makeSkill("weather-check", "Fetch weather forecast for a city", 0.5);
        var stock = makeSkill("stock-price", "Get stock market price for a ticker", 0.5);
        var result = SkillItemRetriever.retrieve("weather forecast", List.of(stock, weather), 5);
        assertEquals(2, result.size());
        assertEquals("weather-check", result.get(0).label());
    }

    @Test
    void significance_boost_affects_ranking() {
        var lowSigMatch = makeSkill("weather-basic", "Basic weather check", 0.3);
        var highSigMatch = makeSkill("weather-pro", "Professional weather forecast", 0.8);
        var result = SkillItemRetriever.retrieve("weather",
            List.of(lowSigMatch, highSigMatch), 5);
        assertEquals("weather-pro", result.get(0).label());
    }

    @Test
    void k_limits_results() {
        var items = List.of(
            makeSkill("a", "First skill", 0.5),
            makeSkill("b", "Second skill", 0.5),
            makeSkill("c", "Third skill", 0.5)
        );
        var result = SkillItemRetriever.retrieve("skill", items, 2);
        assertEquals(2, result.size());
    }

    @Test
    void formatSkillLine_includes_label_and_description() {
        var item = makeSkill("weather-check", "Fetch weather for a city", 0.5);
        var line = SkillItemRetriever.formatSkillLine(item);
        assertTrue(line.startsWith("[skill] weather-check:"));
        assertTrue(line.contains("Fetch weather for a city"));
    }

    @Test
    void extractDescription_from_json() {
        String json = """
            {"version":1,"description":"Fetch weather data","code":"x"}
            """;
        assertEquals("Fetch weather data", SkillItemRetriever.extractDescription(json));
    }

    @Test
    void extractDescription_returns_null_for_missing() {
        assertNull(SkillItemRetriever.extractDescription(null));
        assertNull(SkillItemRetriever.extractDescription("{}"));
    }

    @Test
    void extractDependencies_from_json() {
        String json = """
            {"dependencies":["searxng","hearth-ha"]}
            """;
        String deps = SkillItemRetriever.extractDependencies(json);
        assertNotNull(deps);
        assertTrue(deps.contains("searxng"));
        assertTrue(deps.contains("hearth-ha"));
    }

    @Test
    void extractDependencies_empty_returns_null() {
        String json = """
            {"dependencies":[]}
            """;
        assertNull(SkillItemRetriever.extractDependencies(json));
    }
}
