package org.wyrdsekai.e2e.tier2;

import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

/**
 * Empty-by-default base for {@link ItemWorldApiProvider} stubs used by
 * the diverse-item-shape E2E tasks (PiCodingE2ETest task5–task8).
 * Each test extends this and overrides the surfaces its item exercises;
 * everything else returns the empty / no-op shape so a wayward model
 * call doesn't blow up the test with NPE — it just gets nothing back,
 * which is the right "polite refusal" semantic for a sandboxed item.
 *
 * <p>The 12 abstract methods on {@link ItemWorldApiProvider} are made
 * concrete here so subclasses only need to override what they care
 * about. Mirrors the pattern in
 * {@link OpenHandsItemReplayTest.ReplayProvider} but without the live
 * services — these stubs are deterministic for assertion clarity.</p>
 */
class StubItemProvider implements ItemWorldApiProvider {

    @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
    @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
    @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
    @Override public String webFetch(String url, int max) { return ""; }
    @Override public List<Map<String, Object>> queryOracle(String t, String type) { return List.of(); }
    @Override public String llmSummarize(String text, String inst) { return ""; }
    @Override public String llmAnalyze(String text, String prompt) { return ""; }
    @Override public void agentSpeak(String t) { /* no-op */ }
    @Override public void agentRemember(String c) { /* no-op */ }
    @Override public void agentTell(String tgt, String msg) { /* no-op */ }
    @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
    @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
        return Map.of();
    }
}
