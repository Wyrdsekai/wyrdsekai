package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class GoogleScholarAdapterTest {

    private GoogleScholarAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new GoogleScholarAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("serpapi.key", "serp_test");
    }

    @AfterEach
    void tearDown() { CredentialResolver.get().resetForTests(); }

    @Test
    void namespace_is_scholar() {
        assertEquals("scholar", adapter.namespace());
    }

    @Test
    void no_creds_means_credentials_missing() {
        wireNoCreds();
        var resp = adapter.invoke(req("scholar", "search", Map.of("query", "x")));
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void search_uses_serpapi_engine() {
        adapter.invoke(req("scholar", "search", Map.of("query", "transformers")));
        var u = rec.url.get();
        assertTrue(u.contains("serpapi.com"));
        assertTrue(u.contains("engine=google_scholar"));
        assertTrue(u.contains("api_key=serp_test"));
    }

    @Test
    void citations_uses_cite_engine() {
        adapter.invoke(req("scholar", "citations", Map.of("paperId", "abc")));
        assertTrue(rec.url.get().contains("engine=google_scholar_cite"));
    }

    @Test
    void search_requires_query() {
        assertEquals("missing_arg",
            adapter.invoke(req("scholar", "search", Map.of())).error().code());
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("scholar", "explode", Map.of())).error().code());
    }
}
