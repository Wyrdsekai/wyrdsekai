package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinguaAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_lingua_namespace() {
        var a = new LinguaAdapter();
        assertEquals("lingua", a.namespace());
        assertTrue(a.capabilities().contains("translate"));
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new LinguaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("lingua", "summon"));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void translate_missing_text_is_missing_arg() {
        var a = new LinguaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("lingua", "translate", "targetLang", "ja"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void translate_missing_target_is_missing_arg() {
        var a = new LinguaAdapter();
        var resp = a.invoke(AdapterTestHarness.req("lingua", "translate", "text", "x"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void translate_success_parses_translatedText() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"translatedText\":\"こんにちは\"}")) {
            var a = new LinguaAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("lingua", "translate",
                "text", "Hello", "targetLang", "ja", "endpoint", srv.baseUrl()));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("こんにちは", data.get("translated"));
        }
    }

    @Test
    void translate_uses_explicit_endpoint_arg() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"translatedText\":\"x\"}")) {
            var a = new LinguaAdapter(new HttpAdapterSupport(), "https://invalid.example");
            var resp = a.invoke(AdapterTestHarness.req("lingua", "translate",
                "text", "Hello", "targetLang", "ja", "endpoint", srv.baseUrl()));
            assertTrue(resp.success());
            assertEquals("/translate", srv.captured().path());
        }
    }
}
