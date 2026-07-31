package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ItemWorldApi.ScrollApi} gating
 * + delegation tests.
 */
class ScrollApiTest {

    @Test
    void scroll_create_requires_scroll_write_cap() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.scroll.create("Title",
            List.of(Map.of("type", "text", "content", "x"))))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void scroll_create_routes_through_provider_when_cap_held() {
        var capturedTitle = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> scrollCreate(String title,
                                                                 List<Map<String, Object>> sections) {
                capturedTitle.set(title);
                return Map.of("ok", true, "id", "scroll_1", "version", 1);
            }
        };
        var api = new ItemWorldApi(provider,
            ItemCapabilitySet.of(List.of("scroll.write")));
        var res = api.scroll.create("Test",
            List.of(Map.of("type", "heading", "content", "Hi")));
        assertThat(capturedTitle.get()).isEqualTo("Test");
        assertThat(res.get("id")).isEqualTo("scroll_1");
    }

    @Test
    void scroll_read_implicit_tier1_no_cap_required() {
        var captured = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> scrollRead(String id) {
                captured.set(id);
                return Map.of("ok", true, "title", "x");
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of()));
        api.scroll.read("scroll_1");
        assertThat(captured.get()).isEqualTo("scroll_1");
    }

    @Test
    void scroll_list_implicit_tier1() {
        var provider = new StubProvider() {
            @Override public List<Map<String, Object>> scrollList(Map<String, Object> f) {
                return List.of(Map.of("id", "s1"), Map.of("id", "s2"));
            }
        };
        var api = new ItemWorldApi(provider, ItemCapabilitySet.of(List.of()));
        var list = api.scroll.list();
        assertThat(list).hasSize(2);
    }

    @Test
    void scroll_revise_requires_scroll_write() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.scroll.revise("scroll_1",
            List.of(Map.of("type", "text", "content", "v2"))))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void scroll_lock_requires_scroll_write() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.scroll.lock("scroll_1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void scroll_share_requires_scroll_share_cap() {
        // scroll.write alone is NOT enough — share is Tier 5
        var api = new ItemWorldApi(new StubProvider(),
            ItemCapabilitySet.of(List.of("scroll.write")));
        assertThatThrownBy(() -> api.scroll.share("scroll_1", "agent-b"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void scroll_share_succeeds_with_explicit_cap() {
        var captured = new AtomicReference<String>();
        var provider = new StubProvider() {
            @Override public Map<String, Object> scrollShare(String id, String target) {
                captured.set(id + ">" + target);
                return Map.of("ok", true);
            }
        };
        var api = new ItemWorldApi(provider,
            ItemCapabilitySet.of(List.of("scroll.share")));
        api.scroll.share("s1", "agent-b");
        assertThat(captured.get()).isEqualTo("s1>agent-b");
    }

    @Test
    void scroll_default_provider_returns_not_wired_error() {
        var api = new ItemWorldApi(new StubProvider(),
            ItemCapabilitySet.of(List.of("scroll.write")));
        var res = api.scroll.create("x", List.of());
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("not wired");
    }

    static class StubProvider implements ItemWorldApiProvider {
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
    }
}
