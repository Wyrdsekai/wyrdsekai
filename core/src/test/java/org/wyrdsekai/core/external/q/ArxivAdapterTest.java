package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class ArxivAdapterTest {

    private ArxivAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new ArxivAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
    }

    @Test
    void no_credentials_required() {
        // Empty slot — adapter should never try to resolve.
        wireNoCreds();
        adapter.invoke(req("arxiv", "search", Map.of("query", "transformers")));
        assertEquals("GET", rec.method.get());
    }

    @Test
    void search_includes_query_and_default_max() {
        adapter.invoke(req("arxiv", "search", Map.of("query", "transformers")));
        var u = rec.url.get();
        assertTrue(u.contains("search_query"));
        assertTrue(u.contains("transformers"));
        assertTrue(u.contains("max_results=10"));
    }

    @Test
    void search_with_category_combines_filter() {
        adapter.invoke(req("arxiv", "search",
            Map.of("query", "attention", "category", "cs.LG")));
        assertTrue(rec.url.get().contains("cs.LG"));
    }

    @Test
    void abstract_uses_id_list() {
        adapter.invoke(req("arxiv", "abstract", Map.of("paperId", "2310.06825")));
        assertTrue(rec.url.get().contains("id_list=2310.06825"));
    }

    @Test
    void abstract_requires_paperId() {
        var resp = adapter.invoke(req("arxiv", "abstract", Map.of()));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void full_text_returns_pdf_url_without_network() {
        var resp = adapter.invoke(req("arxiv", "full_text", Map.of("paperId", "1706.03762")));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("1706.03762", data.get("paperId"));
        assertTrue(data.get("pdfUrl").toString().endsWith("1706.03762.pdf"));
    }

    @Test
    void unknown_method() {
        assertEquals("unknown_method",
            adapter.invoke(req("arxiv", "withdraw", Map.of())).error().code());
    }
}
