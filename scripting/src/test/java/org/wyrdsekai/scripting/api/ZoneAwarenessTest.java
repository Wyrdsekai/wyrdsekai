package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for zone awareness in WorldApi and ItemWorldApi.
 */
class ZoneAwarenessTest {

    @Test
    void worldApi_currentZone_defaults_to_local() {
        var api = new WorldApi("test-room");
        assertEquals("local", api.getCurrentZone());
    }

    @Test
    void worldApi_currentZone_reflects_setZoneId() {
        var api = new WorldApi("test-room");
        api.setZoneId("alpha");
        assertEquals("alpha", api.getCurrentZone());
    }

    @Test
    void worldApi_homeZone_defaults_to_current() {
        var api = new WorldApi("test-room");
        api.setZoneId("alpha");
        assertEquals("alpha", api.getHomeZone());
        assertFalse(api.isTraveling());
    }

    @Test
    void worldApi_homeZone_differs_when_traveling() {
        var api = new WorldApi("test-room");
        api.setZoneId("beta");      // host zone
        api.setHomeZoneId("alpha"); // visitor's home
        assertEquals("beta", api.getCurrentZone());
        assertEquals("alpha", api.getHomeZone());
        assertTrue(api.isTraveling());
    }

    @Test
    void itemWorldApi_zone_default() {
        var provider = new MinimalProvider("alpha", "alpha");
        var api = new ItemWorldApi(provider);
        assertEquals("alpha", api.zone.current());
        assertEquals("alpha", api.zone.home());
        assertFalse(api.zone.isTraveling());
    }

    @Test
    void itemWorldApi_zone_traveling() {
        var provider = new MinimalProvider("beta", "alpha");
        var api = new ItemWorldApi(provider);
        assertEquals("beta", api.zone.current());
        assertEquals("alpha", api.zone.home());
        assertTrue(api.zone.isTraveling());
    }

    /** Minimal provider for testing zone API. */
    static class MinimalProvider implements ItemWorldApiProvider {
        private final String current;
        private final String home;

        MinimalProvider(String current, String home) {
            this.current = current;
            this.home = home;
        }

        @Override public String currentZone() { return current; }
        @Override public String homeZone() { return home; }

        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int m) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String inst) { return ""; }
        @Override public String llmAnalyze(String text, String p) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}
