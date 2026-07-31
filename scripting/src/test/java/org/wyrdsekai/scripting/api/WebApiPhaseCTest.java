package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase C web write surface (post/put/delete/
 * fetch_raw + allowed_domains). Pins gating behaviour and the per-item
 * domain allowlist enforcement.
 */
class WebApiPhaseCTest {

    @Test
    void post_requires_capability() {
        var p = new StubProvider();
        var caps = ItemCapabilitySet.of(List.of()); // no caps
        var api = new ItemWorldApi(p, caps);
        assertThatThrownBy(() -> api.web.post("https://example.com/", "hi"))
            .isInstanceOf(CapabilityDeniedError.class)
            .matches(e -> ((CapabilityDeniedError) e).capability().equals("web.post"));
    }

    @Test
    void post_allowed_with_capability_and_domain() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.post"), List.of("example.com"));
        var api = new ItemWorldApi(p, caps);
        var res = api.web.post("https://example.com/hook", "{\"ok\":true}");
        assertThat(p.lastUrl).isEqualTo("https://example.com/hook");
        assertThat(res.get("status")).isEqualTo(200);
    }

    @Test
    void post_blocked_when_domain_not_allowlisted() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.post"), List.of("example.com"));
        var api = new ItemWorldApi(p, caps);
        var res = api.web.post("https://evil.com/hook", "x");
        assertThat(res.get("error")).isEqualTo("domain_not_allowed");
        assertThat(p.lastUrl).isNull(); // provider not called
    }

    @Test
    void put_blocked_when_no_domains_declared() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.put"), List.of());
        var api = new ItemWorldApi(p, caps);
        var res = api.web.put("https://example.com/", "x");
        assertThat(res.get("error")).isEqualTo("domain_not_allowed");
    }

    @Test
    void delete_succeeds_with_wildcard_subdomain_match() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.delete"), List.of("*.example.com"));
        var api = new ItemWorldApi(p, caps);
        var res = api.web.delete("https://api.example.com/foo");
        assertThat(p.lastUrl).isEqualTo("https://api.example.com/foo");
        assertThat(res.get("status")).isEqualTo(200);
    }

    @Test
    void fetch_raw_tier4_requires_cap() {
        var p = new StubProvider();
        var caps = capsWith(List.of(), List.of("example.com"));
        var api = new ItemWorldApi(p, caps);
        assertThatThrownBy(() -> api.web.fetch_raw("https://example.com/"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void fetch_raw_returns_provider_shape() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.fetch_raw"), List.of("example.com"));
        var api = new ItemWorldApi(p, caps);
        var res = api.web.fetch_raw("https://example.com/");
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(res.get("body")).isEqualTo("ok");
    }

    @Test
    void search_remains_implicit_tier1() {
        var p = new StubProvider();
        var caps = ItemCapabilitySet.of(List.of()); // no caps
        var api = new ItemWorldApi(p, caps);
        var res = api.web.search("query");
        assertThat(res).isNotNull();
    }

    @Test
    void allowed_domains_returns_caps_list() {
        var p = new StubProvider();
        var caps = capsWith(List.of("web.post"), List.of("example.com", "*.api.io"));
        var api = new ItemWorldApi(p, caps);
        assertThat(api.web.allowed_domains()).containsExactly("example.com", "*.api.io");
    }

    @Test
    void unrestricted_caps_bypass_domain_check() {
        var p = new StubProvider();
        // UNRESTRICTED mimics JVM-baked items.
        var api = new ItemWorldApi(p, ItemCapabilitySet.UNRESTRICTED);
        var res = api.web.post("https://anything.example/", "x");
        assertThat(p.lastUrl).isEqualTo("https://anything.example/");
        assertThat(res.get("status")).isEqualTo(200);
    }

    private static ItemCapabilitySet capsWith(List<String> declared, List<String> domains) {
        var m = new ItemManifest("test", "1.0.0", "T.", "did:wyrd:x",
            declared, Map.of(), "low", List.of(),
            domains, List.of(), List.of(),
            null, null, null, null, null);
        return ItemCapabilitySet.from(m);
    }

    private static final class StubProvider implements ItemWorldApiProvider {
        String lastUrl;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) {
            return new ArrayList<>(List.of(Map.of("title", "t", "url", "u", "snippet", "s")));
        }
        @Override public String webFetch(String url, int max) { return "fetched"; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Map<String, Object> webPost(String url, Object body, Map<String, Object> opts) {
            lastUrl = url; return Map.of("status", 200, "body", "ok");
        }
        @Override public Map<String, Object> webPut(String url, Object body, Map<String, Object> opts) {
            lastUrl = url; return Map.of("status", 200, "body", "ok");
        }
        @Override public Map<String, Object> webDelete(String url, Map<String, Object> opts) {
            lastUrl = url; return Map.of("status", 200);
        }
        @Override public Map<String, Object> webFetchRaw(String url, Map<String, Object> opts) {
            lastUrl = url; return Map.of("status", 200, "body", "ok",
                "headers", Map.of(), "contentType", "text/plain");
        }
    }
}
