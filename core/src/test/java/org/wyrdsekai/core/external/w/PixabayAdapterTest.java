package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PixabayAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_pixabay_namespace() {
        var a = new PixabayAdapter();
        assertEquals("pixabay", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertEquals("pixabay.api_key", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new PixabayAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pixabay", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new PixabayAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pixabay", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new PixabayAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pixabay", "search", "query", "cats"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void search_parses_hits() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"hits\":[{\"id\":42,\"previewURL\":\"p\",\"webformatURL\":\"w\",\"largeImageURL\":\"l\",\"user\":\"alice\",\"tags\":\"cat,kitten\"}]}")) {
            AdapterTestHarness.setCredential("pixabay.api_key", "k");
            var a = new PixabayAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("pixabay", "search", "query", "cats"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals(42, list.get(0).get("id"));
            assertEquals("alice", list.get(0).get("user"));
        }
    }
}
