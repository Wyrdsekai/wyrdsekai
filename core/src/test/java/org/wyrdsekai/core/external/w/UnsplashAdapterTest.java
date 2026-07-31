package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnsplashAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_unsplash_namespace() {
        var a = new UnsplashAdapter();
        assertEquals("unsplash", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertTrue(a.capabilities().contains("download_url"));
        assertEquals("unsplash.access_key", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new UnsplashAdapter();
        var resp = a.invoke(AdapterTestHarness.req("unsplash", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new UnsplashAdapter();
        var resp = a.invoke(AdapterTestHarness.req("unsplash", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new UnsplashAdapter();
        var resp = a.invoke(AdapterTestHarness.req("unsplash", "search", "query", "cats"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void search_parses_results() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"results\":[{\"id\":\"abc\",\"urls\":{\"small\":\"u\"},\"user\":{\"name\":\"alice\"},\"links\":{\"download_location\":\"https://x/d\"},\"description\":\"d\"}]}")) {
            AdapterTestHarness.setCredential("unsplash.access_key", "k");
            var a = new UnsplashAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("unsplash", "search", "query", "cats"));
            assertTrue(resp.success(), () -> "expected ok, got " + resp);
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("abc", list.get(0).get("photoId"));
            assertEquals("https://x/d", list.get(0).get("downloadLocation"));
        }
    }

    @Test
    void download_url_calls_unsplash_endpoint() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"url\":\"https://images.example/a.jpg\"}")) {
            AdapterTestHarness.setCredential("unsplash.access_key", "k");
            var a = new UnsplashAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("unsplash", "download_url",
                "photoId", "abc"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("https://images.example/a.jpg", data.get("url"));
            assertEquals("abc", data.get("photoId"));
            assertTrue(srv.captured().path().contains("/photos/abc/download"));
        }
    }

    @Test
    void download_url_missing_photoId_is_missing_arg() {
        AdapterTestHarness.setCredential("unsplash.access_key", "k");
        var a = new UnsplashAdapter();
        var resp = a.invoke(AdapterTestHarness.req("unsplash", "download_url"));
        assertEquals("missing_arg", resp.error().code());
    }
}
