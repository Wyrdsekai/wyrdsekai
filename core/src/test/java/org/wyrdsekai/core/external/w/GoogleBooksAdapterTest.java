package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleBooksAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_gbooks_namespace() {
        var a = new GoogleBooksAdapter();
        assertEquals("gbooks", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertTrue(a.capabilities().contains("volume_info"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new GoogleBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("gbooks", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new GoogleBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("gbooks", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_parses_items_without_credential() {
        // gbooks doesn't require a key for low-volume reads; verify it works
        // even when credential is absent.
        AdapterTestHarness.clearCredentials();
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"items\":[{\"id\":\"v1\",\"volumeInfo\":{\"title\":\"X\",\"authors\":[\"a\"],\"description\":\"d\",\"publishedDate\":\"2020\"}}]}")) {
            var a = new GoogleBooksAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("gbooks", "search", "query", "x"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("v1", list.get(0).get("volumeId"));
            assertEquals("X", list.get(0).get("title"));
        }
    }

    @Test
    void volume_info_missing_id_is_missing_arg() {
        var a = new GoogleBooksAdapter();
        var resp = a.invoke(AdapterTestHarness.req("gbooks", "volume_info"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void volume_info_parses_response() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"id\":\"v1\",\"volumeInfo\":{\"title\":\"T\",\"authors\":[\"a\"],\"description\":\"d\",\"previewLink\":\"p\",\"pageCount\":100,\"categories\":[\"fiction\"]}}")) {
            var a = new GoogleBooksAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("gbooks", "volume_info", "volumeId", "v1"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("v1", data.get("volumeId"));
            assertEquals("T", data.get("title"));
            assertEquals(100, data.get("pageCount"));
        }
    }
}
