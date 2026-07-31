package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CalibreAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_calibre_namespace() {
        var a = new CalibreAdapter();
        assertEquals("calibre", a.namespace());
        assertTrue(a.capabilities().contains("library_list"));
        assertTrue(a.capabilities().contains("book_info"));
        assertTrue(a.capabilities().contains("search"));
        assertEquals("calibre.url", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new CalibreAdapter();
        var resp = a.invoke(AdapterTestHarness.req("calibre", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void library_list_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new CalibreAdapter();
        var resp = a.invoke(AdapterTestHarness.req("calibre", "library_list"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void library_list_parses_books_map() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"1\":{\"title\":\"T\",\"authors\":[\"a\"],\"formats\":[\"EPUB\"],\"tags\":[\"fiction\"]}}")) {
            AdapterTestHarness.setCredential("calibre.url", srv.baseUrl());
            var a = new CalibreAdapter();
            var resp = a.invoke(AdapterTestHarness.req("calibre", "library_list"));
            assertTrue(resp.success(), () -> "expected ok, got " + resp);
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("T", list.get(0).get("title"));
        }
    }

    @Test
    void book_info_missing_id_is_missing_arg() {
        AdapterTestHarness.setCredential("calibre.url", "http://x");
        var a = new CalibreAdapter();
        var resp = a.invoke(AdapterTestHarness.req("calibre", "book_info"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void book_info_parses_metadata() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"title\":\"Frankenstein\",\"authors\":[\"Mary Shelley\"]}")) {
            AdapterTestHarness.setCredential("calibre.url", srv.baseUrl());
            var a = new CalibreAdapter();
            var resp = a.invoke(AdapterTestHarness.req("calibre", "book_info", "bookId", 7));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("Frankenstein", data.get("title"));
            assertEquals("7", data.get("bookId"));
        }
    }

    @Test
    void search_parses_book_ids() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"total_num\":2,\"book_ids\":[1,2]}")) {
            AdapterTestHarness.setCredential("calibre.url", srv.baseUrl());
            var a = new CalibreAdapter();
            var resp = a.invoke(AdapterTestHarness.req("calibre", "search", "query", "x"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals(2, data.get("totalNum"));
        }
    }
}
