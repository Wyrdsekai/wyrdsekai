package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleFontsAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_fonts_namespace() {
        var a = new GoogleFontsAdapter();
        assertEquals("fonts", a.namespace());
        assertTrue(a.capabilities().contains("list"));
        assertTrue(a.capabilities().contains("font_info"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new GoogleFontsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("fonts", "summon"));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void list_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new GoogleFontsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("fonts", "list"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void list_parses_items() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"items\":[{\"family\":\"Roboto\",\"category\":\"sans-serif\",\"variants\":[\"regular\"],\"subsets\":[\"latin\"]}]}")) {
            AdapterTestHarness.setCredential("googlefonts.api_key", "k");
            var a = new GoogleFontsAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("fonts", "list"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var list = (List<Map<String, Object>>) resp.data();
            assertEquals(1, list.size());
            assertEquals("Roboto", list.get(0).get("family"));
            assertEquals("sans-serif", list.get(0).get("category"));
        }
    }

    @Test
    void font_info_missing_family_is_missing_arg() {
        AdapterTestHarness.setCredential("googlefonts.api_key", "k");
        var a = new GoogleFontsAdapter();
        var resp = a.invoke(AdapterTestHarness.req("fonts", "font_info"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void font_info_returns_first_match() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"items\":[{\"family\":\"Inter\",\"category\":\"sans-serif\",\"variants\":[\"regular\"],\"subsets\":[\"latin\"],\"version\":\"v1\"}]}")) {
            AdapterTestHarness.setCredential("googlefonts.api_key", "k");
            var a = new GoogleFontsAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("fonts", "font_info", "family", "Inter"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("Inter", data.get("family"));
            assertEquals("v1", data.get("version"));
        }
    }
}
