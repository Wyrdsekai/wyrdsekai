package org.wyrdsekai.core.external.q;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.CredentialResolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.core.external.q.PhaseQTestSupport.*;

class NotionAdapterTest {

    private NotionAdapter adapter;
    private Recorder rec;

    @BeforeEach
    void setUp() {
        adapter = new NotionAdapter();
        rec = new Recorder();
        adapter.setTransportForTests(rec);
        wireCred("notion.token", "secret_xyz");
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void all_methods_advertised() {
        assertTrue(adapter.capabilities().containsAll(List.of(
            "search", "read_page", "create_page", "update_page", "append_block")));
    }

    @Test
    void search_posts_to_search_endpoint_with_version_header() {
        adapter.invoke(req("notion", "search", Map.of("query", "deep work")));
        assertEquals("POST", rec.method.get());
        assertTrue(rec.url.get().endsWith("/search"));
        assertEquals("2022-06-28", rec.headers.get().get("Notion-Version"));
        assertEquals("Bearer secret_xyz", rec.headers.get().get("Authorization"));
    }

    @Test
    void read_page_GET_by_id() {
        adapter.invoke(req("notion", "read_page", Map.of("pageId", "PAGE1")));
        assertEquals("GET", rec.method.get());
        assertTrue(rec.url.get().endsWith("/pages/PAGE1"));
    }

    @Test
    void create_page_requires_parent() {
        var resp = adapter.invoke(req("notion", "create_page",
            Map.of("properties", Map.of("title", "x"))));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void update_page_uses_PATCH() {
        adapter.invoke(req("notion", "update_page",
            Map.of("pageId", "P1", "properties", Map.of("k", "v"))));
        assertEquals("PATCH", rec.method.get());
        assertTrue(rec.url.get().endsWith("/pages/P1"));
    }

    @Test
    void append_block_PATCHes_block_children() {
        adapter.invoke(req("notion", "append_block",
            Map.of("blockId", "B1", "children", List.of(Map.of()))));
        assertEquals("PATCH", rec.method.get());
        assertTrue(rec.url.get().endsWith("/blocks/B1/children"));
    }

    @Test
    void no_creds_means_credentials_missing() {
        wireNoCreds();
        var resp = adapter.invoke(req("notion", "search", Map.of("query", "x")));
        assertEquals("credentials_missing", resp.error().code());
    }
}
