package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class WikipediaAdapterTest {

    private WikipediaAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new WikipediaAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
    }

    @Test
    void no_credentials_required() {
        wireNoCreds();
        adapter.invoke(req("wikipedia", "search", Map.of("query", "Tokyo")));
        assertEquals("GET", rec.method.get());
    }

    @Test
    void default_lang_is_en() {
        adapter.invoke(req("wikipedia", "search", Map.of("query", "Tokyo")));
        assertTrue(rec.url.get().startsWith("https://en.wikipedia.org/"));
    }

    @Test
    void custom_lang_routes_to_lang_subdomain() {
        adapter.invoke(req("wikipedia", "search", Map.of("query", "Tokyo", "lang", "ja")));
        assertTrue(rec.url.get().startsWith("https://ja.wikipedia.org/"));
    }

    @Test
    void summary_uses_rest_v1() {
        adapter.invoke(req("wikipedia", "summary", Map.of("title", "Apache Pekko")));
        var u = rec.url.get();
        assertTrue(u.contains("/api/rest_v1/page/summary/"));
        assertTrue(u.contains("Apache_Pekko"));
    }

    @Test
    void full_article_uses_extracts_prop() {
        adapter.invoke(req("wikipedia", "full_article", Map.of("title", "MUD")));
        var u = rec.url.get();
        assertTrue(u.contains("prop=extracts"));
        assertTrue(u.contains("explaintext"));
    }

    @Test
    void search_requires_query() {
        assertEquals("missing_arg",
            adapter.invoke(req("wikipedia", "search", Map.of())).error().code());
    }

    @Test
    void wildcard_domain_allows_any_lang() {
        // Verify the allowlist permits ja./es./de. subdomains.
        adapter.invoke(req("wikipedia", "summary", Map.of("title", "MUD", "lang", "es")));
        var resp = rec.url.get();
        assertTrue(resp.startsWith("https://es.wikipedia.org"));
    }

    @Test
    void user_agent_header_set() {
        adapter.invoke(req("wikipedia", "search", Map.of("query", "x")));
        assertNotNull(rec.headers.get().get("User-Agent"));
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("wikipedia", "delete", Map.of())).error().code());
    }
}
