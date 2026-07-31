package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApi;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransitItemProviderTest {

    @Test
    void homeZone_uses_visitor_home() {
        var host = new FakeHostProvider("beta");
        var transit = new TransitItemProvider(host, "alpha");
        assertEquals("alpha", transit.homeZone());
        assertEquals("beta", transit.currentZone());
    }

    @Test
    void isTraveling_via_itemWorldApi() {
        var host = new FakeHostProvider("beta");
        var transit = new TransitItemProvider(host, "alpha");
        var api = new ItemWorldApi(transit);
        assertTrue(api.zone.isTraveling());
    }

    @Test
    void not_traveling_when_home_equals_current() {
        var host = new FakeHostProvider("alpha");
        var transit = new TransitItemProvider(host, "alpha");
        var api = new ItemWorldApi(transit);
        assertFalse(api.zone.isTraveling());
    }

    @Test
    void service_calls_delegate_to_host() {
        var host = new FakeHostProvider("beta");
        var transit = new TransitItemProvider(host, "alpha");
        // Library search delegates to host (uses beta's library, not alpha's)
        var results = transit.searchKnowledge("test", 10);
        assertEquals(1, results.size());
        assertEquals("beta-result", results.get(0).get("zone"));
    }

    @Test
    void null_visitor_home_falls_back_to_host_home() {
        var host = new FakeHostProvider("beta");
        var transit = new TransitItemProvider(host, null);
        assertEquals("beta", transit.homeZone());
    }

    /** Fake provider that returns traceable zone-tagged results. */
    static class FakeHostProvider implements ItemWorldApiProvider {
        private final String zone;

        FakeHostProvider(String zone) { this.zone = zone; }

        @Override public String currentZone() { return zone; }
        @Override public String homeZone() { return zone; }

        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) {
            return List.of(Map.of("text", q, "zone", zone + "-result"));
        }
        @Override public Map<String, Object> readKnowledgeChunk(String id) {
            return Map.of("zone", zone);
        }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int m) { return "host=" + zone; }
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
