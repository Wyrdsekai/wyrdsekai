package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PexelsAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_pexels_namespace() {
        var a = new PexelsAdapter();
        assertEquals("pexels", a.namespace());
        assertTrue(a.capabilities().contains("search"));
        assertEquals("pexels.api_key", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new PexelsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pexels", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new PexelsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pexels", "search"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new PexelsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("pexels", "search", "query", "cats"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void search_parses_photos() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"photos\":[{\"id\":7,\"src\":{\"medium\":\"m\"},\"photographer\":\"bob\",\"alt\":\"x\"}]}")) {
            AdapterTestHarness.setCredential("pexels.api_key", "k");
            var a = new PexelsAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("pexels", "search", "query", "cats"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals(7, list.get(0).get("photoId"));
            assertEquals("bob", list.get(0).get("photographer"));
        }
    }

    @Test
    void search_sends_authorization_header() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"photos\":[]}")) {
            AdapterTestHarness.setCredential("pexels.api_key", "secret");
            var a = new PexelsAdapter(new HttpAdapterSupport(), srv.baseUrl());
            a.invoke(AdapterTestHarness.req("pexels", "search", "query", "x"));
            var auth = srv.captured().headers().get("Authorization");
            assertNotNull(auth);
            assertEquals("secret", auth.get(0));
        }
    }
}
