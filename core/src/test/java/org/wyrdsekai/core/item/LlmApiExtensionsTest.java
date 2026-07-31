package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApi;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.api.CapabilityDeniedError;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code world.llm.complete/classify/extract/tools}
 * surface tests. These exercise the JS side via {@link ItemScriptExecutor}
 * + capability gating, and the Java side via direct calls on a stub provider.
 *
 * <p>Real inference is exercised by integration tests; this file pins the
 * shape contracts and the gating rules.</p>
 */
class LlmApiExtensionsTest {

    @Test
    void llm_complete_default_returns_not_wired() {
        var provider = new ItemWorldApiProvider() {
            @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
            @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
            @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
            @Override public String webFetch(String url, int max) { return ""; }
            @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
            @Override public String llmSummarize(String t, String i) { return ""; }
            @Override public String llmAnalyze(String t, String p) { return ""; }
            @Override public void agentSpeak(String t) {}
            @Override public void agentRemember(String c) {}
            @Override public void agentTell(String t, String m) {}
            @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
            @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
        };
        var api = new ItemWorldApi(provider);
        var res = api.llm.complete("hi");
        assertThat(res).containsKey("error");
        assertThat((String) res.get("text")).startsWith("[error]");
    }

    @Test
    void llm_complete_routes_through_provider() {
        var captured = new AtomicReference<String>();
        var provider = stubProvider();
        provider.completeImpl = (prompt, opts) -> {
            captured.set(prompt);
            return Map.of("text", "echo:" + prompt, "latencyMs", 42L,
                "tokensIn", 5, "tokensOut", 2);
        };
        var api = new ItemWorldApi(provider);
        var res = api.llm.complete("hi", Map.of("maxTokens", 100));
        assertThat(captured.get()).isEqualTo("hi");
        assertThat(res.get("text")).isEqualTo("echo:hi");
        assertThat(res.get("latencyMs")).isEqualTo(42L);
    }

    @Test
    void llm_classify_passes_labels_and_returns_label() {
        var provider = stubProvider();
        provider.classifyImpl = (text, labels) -> Map.of("label", labels.getFirst(), "confidence", 0.9);
        var api = new ItemWorldApi(provider);
        var res = api.llm.classify("frustrated tone", List.of("calm", "angry", "sad"));
        assertThat(res.get("label")).isEqualTo("calm");
        assertThat((Double) res.get("confidence")).isGreaterThan(0.8);
    }

    @Test
    void llm_extract_returns_provider_map() {
        var provider = stubProvider();
        provider.extractImpl = (text, schema) -> Map.of("name", "Ember", "age", 3);
        var api = new ItemWorldApi(provider);
        var schema = Map.<String, Object>of("type", "object",
            "properties", Map.of("name", Map.of("type", "string")));
        var res = api.llm.extract("Ember is 3 years old.", schema);
        assertThat(res.get("name")).isEqualTo("Ember");
        assertThat(res.get("age")).isEqualTo(3);
    }

    @Test
    void llm_tools_returns_tool_calls_and_final_text() {
        var provider = stubProvider();
        provider.toolsImpl = (prompt, tools, opts) -> Map.of(
            "toolCalls", List.of(Map.of("name", "lookup", "arguments", Map.of("q", "x"))),
            "finalText", "");
        var api = new ItemWorldApi(provider);
        var res = api.llm.tools("find x", List.of(
            Map.of("name", "lookup", "description", "lookup something",
                "parameters", Map.of("type", "object"))));
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) res.get("toolCalls");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().get("name")).isEqualTo("lookup");
    }

    @Test
    void llm_budget_remaining_returns_shape() {
        var provider = stubProvider();
        provider.budgetImpl = () -> Map.of("tokens", 1000L, "costUsd", 0.5,
            "dailyResetAt", System.currentTimeMillis() + 86_400_000L);
        var api = new ItemWorldApi(provider);
        var res = api.llm.budget_remaining();
        assertThat(res.get("tokens")).isEqualTo(1000L);
    }

    @Test
    void llm_classify_blocked_without_capability_in_script() {
        var provider = stubProvider();
        provider.classifyImpl = (text, labels) -> Map.of("label", "calm", "confidence", 1.0);
        var executor = new ItemScriptExecutor();
        try {
            // No cap declared — script tries to call llm.classify, denied.
            var res = executor.execute("classifier",
                "function invoke(p){return world.llm.classify(p.text, p.labels);}",
                Map.of("text", "hi", "labels", List.of("a", "b")),
                provider, ItemCapabilitySet.of(List.of()));
            assertThat(res).containsKey("capability_denied");
            assertThat(res.get("capability_denied")).isEqualTo("llm.classify");
        } finally {
            executor.close();
        }
    }

    @Test
    void llm_classify_allowed_with_capability_in_script() {
        var provider = stubProvider();
        provider.classifyImpl = (text, labels) -> Map.of("label", labels.get(1), "confidence", 0.95);
        var executor = new ItemScriptExecutor();
        try {
            var caps = ItemCapabilitySet.of(List.of("llm.classify"));
            var res = executor.execute("classifier",
                "function invoke(p){return world.llm.classify(p.text, p.labels);}",
                Map.of("text", "hi", "labels", List.of("a", "b")), provider, caps);
            assertThat(res.get("label")).isEqualTo("b");
        } finally {
            executor.close();
        }
    }

    @Test
    void llm_complete_blocked_without_capability_in_script() {
        var provider = stubProvider();
        provider.completeImpl = (prompt, opts) -> Map.of("text", "ok");
        var executor = new ItemScriptExecutor();
        try {
            // llm.complete is Tier 4 — script must declare the cap.
            var res = executor.execute("completer",
                "function invoke(p){return world.llm.complete(p.prompt);}",
                Map.of("prompt", "hi"), provider, ItemCapabilitySet.of(List.of()));
            assertThat(res).containsKey("capability_denied");
            assertThat(res.get("capability_denied")).isEqualTo("llm.complete");
        } finally {
            executor.close();
        }
    }

    @Test
    void llm_complete_allowed_with_capability_in_script() {
        var provider = stubProvider();
        provider.completeImpl = (prompt, opts) -> Map.of("text", "ok");
        var executor = new ItemScriptExecutor();
        try {
            var caps = ItemCapabilitySet.of(List.of("llm.complete"));
            var res = executor.execute("completer",
                "function invoke(p){return world.llm.complete(p.prompt);}",
                Map.of("prompt", "hi"), provider, caps);
            assertThat(res.get("text")).isEqualTo("ok");
        } finally {
            executor.close();
        }
    }

    // ─── Stub provider with mutable impl hooks ─────────────────────

    private static StubLlmProvider stubProvider() { return new StubLlmProvider(); }

    private static final class StubLlmProvider implements ItemWorldApiProvider {
        BiFunction<String, Map<String, Object>, Map<String, Object>> completeImpl;
        BiFunction<String, List<String>, Map<String, Object>> classifyImpl;
        BiFunction<String, Map<String, Object>, Map<String, Object>> extractImpl;
        TriFunction<String, List<Map<String, Object>>, Map<String, Object>, Map<String, Object>> toolsImpl;
        Supplier<Map<String, Object>> budgetImpl;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
            return completeImpl != null ? completeImpl.apply(prompt, opts)
                : ItemWorldApiProvider.super.llmComplete(prompt, opts);
        }
        @Override public Map<String, Object> llmClassify(String text, List<String> labels) {
            return classifyImpl != null ? classifyImpl.apply(text, labels)
                : ItemWorldApiProvider.super.llmClassify(text, labels);
        }
        @Override public Map<String, Object> llmExtract(String text, Map<String, Object> schema) {
            return extractImpl != null ? extractImpl.apply(text, schema)
                : ItemWorldApiProvider.super.llmExtract(text, schema);
        }
        @Override public Map<String, Object> llmTools(String prompt,
                                                       List<Map<String, Object>> tools,
                                                       Map<String, Object> opts) {
            return toolsImpl != null ? toolsImpl.apply(prompt, tools, opts)
                : ItemWorldApiProvider.super.llmTools(prompt, tools, opts);
        }
        @Override public Map<String, Object> llmBudgetRemaining() {
            return budgetImpl != null ? budgetImpl.get()
                : ItemWorldApiProvider.super.llmBudgetRemaining();
        }
    }

    @FunctionalInterface
    private interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
