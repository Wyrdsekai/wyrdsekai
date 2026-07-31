package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code world.json.*} surface delegates
 * to provider methods. Provider-level Jackson behaviour is covered by
 * ItemJsonHelperTest in core; this file pins the API-class wiring.
 */
class JsonApiTest {

    private final AtomicReference<String> lastParseInput = new AtomicReference<>();
    private final AtomicReference<Boolean> lastStringifyPretty = new AtomicReference<>();
    private final AtomicReference<String> lastJsonPath = new AtomicReference<>();

    private final ItemWorldApiProvider provider = new ItemWorldApiProvider() {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String topic, String type) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String tgt, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Object jsonParse(String text) {
            lastParseInput.set(text);
            return Map.of("parsed", text);
        }
        @Override public String jsonStringify(Object value, boolean pretty) {
            lastStringifyPretty.set(pretty);
            return pretty ? "{pretty}" : "{compact}";
        }
        @Override public Object jsonPath(Object value, String jsonPath) {
            lastJsonPath.set(jsonPath);
            return "leaf";
        }
        @Override public Object jsonMerge(Object a, Object b) {
            var m = new LinkedHashMap<>();
            if (a instanceof Map<?, ?> ma) m.putAll((Map) ma);
            if (b instanceof Map<?, ?> mb) m.putAll((Map) mb);
            return m;
        }
        @Override public List<Map<String, Object>> jsonDiff(Object a, Object b) {
            return List.of(Map.of("op", "replace", "path", "/x", "from", a, "to", b));
        }
    };

    private final ItemWorldApi.JsonApi json = new ItemWorldApi.JsonApi(provider);

    @Test
    void parse_delegates_to_provider() {
        var out = json.parse("{\"k\":1}");
        assertThat(lastParseInput.get()).isEqualTo("{\"k\":1}");
        assertThat(out).isInstanceOf(Map.class);
    }

    @Test
    void stringify_default_is_compact() {
        assertThat(json.stringify(Map.of("k", 1))).isEqualTo("{compact}");
        assertThat(lastStringifyPretty.get()).isFalse();
    }

    @Test
    void stringify_pretty_threads_through() {
        assertThat(json.stringify(Map.of("k", 1), true)).isEqualTo("{pretty}");
        assertThat(lastStringifyPretty.get()).isTrue();
    }

    @Test
    void path_delegates_with_expression() {
        assertThat(json.path(Map.of("a", 1), "$.a")).isEqualTo("leaf");
        assertThat(lastJsonPath.get()).isEqualTo("$.a");
    }

    @Test
    void merge_delegates_to_provider() {
        var merged = json.merge(Map.of("a", 1), Map.of("b", 2));
        assertThat(merged).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var m = (Map<String, Object>) merged;
        assertThat(m).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void diff_returns_patch_list() {
        var patches = json.diff("old", "new");
        assertThat(patches).hasSize(1);
        assertThat(patches.get(0)).containsEntry("op", "replace");
    }
}
