package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IconifyAdapterTest {

    @Test
    void declares_iconify_namespace() {
        var a = new IconifyAdapter();
        assertEquals("iconify", a.namespace());
        assertTrue(a.capabilities().contains("search_icons"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new IconifyAdapter();
        var resp = a.invoke(AdapterTestHarness.req("iconify", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void search_missing_query_is_missing_arg() {
        var a = new IconifyAdapter();
        var resp = a.invoke(AdapterTestHarness.req("iconify", "search_icons"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_parses_icons() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"total\":2,\"icons\":[\"mdi:home\",\"mdi:home-outline\"]}")) {
            var a = new IconifyAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("iconify", "search_icons", "query", "home"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals(2, data.get("total"));
            @SuppressWarnings("unchecked")
            var icons = (List<Map<String, Object>>) data.get("icons");
            assertEquals(2, icons.size());
            assertEquals("mdi:home", icons.get(0).get("name"));
            assertTrue(((String) icons.get(0).get("svgUrl")).endsWith("/mdi:home.svg"));
        }
    }

    @Test
    void search_alias_works() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"total\":0,\"icons\":[]}")) {
            var a = new IconifyAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("iconify", "search", "query", "x"));
            assertTrue(resp.success());
        }
    }
}
