package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenLibraryAdapterTest {

    @Test
    void declares_openlib_namespace() {
        var a = new OpenLibraryAdapter();
        assertEquals("openlib", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertTrue(a.capabilities().contains("work_info"));
        assertTrue(a.capabilities().contains("edition_info"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new OpenLibraryAdapter();
        var resp = a.invoke(AdapterTestHarness.req("openlib", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new OpenLibraryAdapter();
        var resp = a.invoke(AdapterTestHarness.req("openlib", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void work_info_missing_id_is_missing_arg() {
        var a = new OpenLibraryAdapter();
        var resp = a.invoke(AdapterTestHarness.req("openlib", "work_info"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_parses_docs() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"docs\":[{\"key\":\"/works/OL1W\",\"title\":\"Frankenstein\",\"author_name\":[\"Mary Shelley\"],\"first_publish_year\":1818,\"edition_count\":300,\"cover_i\":42}]}")) {
            var a = new OpenLibraryAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("openlib", "search", "query", "frankenstein"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("Frankenstein", list.get(0).get("title"));
            assertEquals(1818, list.get(0).get("firstPublishYear"));
        }
    }

    @Test
    void work_info_returns_raw_payload() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"title\":\"Frankenstein\",\"description\":\"A novel.\"}")) {
            var a = new OpenLibraryAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("openlib", "work_info", "workId", "OL1W"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("Frankenstein", data.get("title"));
        }
    }

    @Test
    void edition_info_resolves_editionId() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"title\":\"Frankenstein - 1st ed\"}")) {
            var a = new OpenLibraryAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("openlib", "edition_info",
                "editionId", "OL1M"));
            assertTrue(resp.success());
            assertTrue(srv.captured().path().contains("/books/OL1M"));
        }
    }
}
