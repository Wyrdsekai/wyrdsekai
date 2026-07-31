package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeepLAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_namespace_and_capabilities() {
        var a = new DeepLAdapter();
        assertEquals("deepl", a.namespace());
        assertTrue(a.capabilities().contains("translate"));
        assertTrue(a.capabilities().contains("detect_language"));
        assertEquals("deepl.api_key", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new DeepLAdapter();
        var resp = a.invoke(AdapterTestHarness.req("deepl", "destroy", "x", "y"));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void translate_missing_text_returns_missing_arg() {
        var a = new DeepLAdapter();
        var resp = a.invoke(AdapterTestHarness.req("deepl", "translate", "targetLang", "EN"));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void translate_missing_target_returns_missing_arg() {
        var a = new DeepLAdapter();
        var resp = a.invoke(AdapterTestHarness.req("deepl", "translate", "text", "Hallo"));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void translate_without_credential_returns_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new DeepLAdapter();
        var resp = a.invoke(AdapterTestHarness.req("deepl", "translate",
            "text", "Hallo Welt", "targetLang", "EN"));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void translate_success_parses_translations_list() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"translations\":[{\"detected_source_language\":\"DE\",\"text\":\"Hello world\"}]}")) {
            AdapterTestHarness.setCredential("deepl.api_key", "test-key");
            var a = new DeepLAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("deepl", "translate",
                "text", "Hallo Welt", "targetLang", "EN"));
            assertTrue(resp.success(), () -> "expected ok, got: " + resp);
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("Hello world", data.get("translated"));
            assertEquals("DE", data.get("sourceLang"));
        }
    }

    @Test
    void translate_sends_auth_header() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"translations\":[{\"text\":\"x\"}]}")) {
            AdapterTestHarness.setCredential("deepl.api_key", "secret-123");
            var a = new DeepLAdapter(new HttpAdapterSupport(), srv.baseUrl());
            a.invoke(AdapterTestHarness.req("deepl", "translate",
                "text", "x", "targetLang", "EN"));
            var captured = srv.captured();
            assertNotNull(captured);
            var auth = captured.headers().get("Authorization");
            assertNotNull(auth, "Authorization header missing");
            assertTrue(auth.get(0).contains("DeepL-Auth-Key"));
            assertTrue(auth.get(0).contains("secret-123"));
        }
    }

    @Test
    void detect_language_success_returns_lang() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"translations\":[{\"detected_source_language\":\"JA\",\"text\":\"x\"}]}")) {
            AdapterTestHarness.setCredential("deepl.api_key", "k");
            var a = new DeepLAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("deepl", "detect_language",
                "text", "こんにちは"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("JA", data.get("lang"));
        }
    }

    @Test
    void upstream_error_collapses_to_upstream_error() {
        try (var srv = AdapterTestHarness.startMock(403, "application/json",
            "{\"message\":\"forbidden\"}")) {
            AdapterTestHarness.setCredential("deepl.api_key", "k");
            var a = new DeepLAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("deepl", "translate",
                "text", "x", "targetLang", "EN"));
            assertFalse(resp.success());
            assertEquals("upstream_error", resp.error().code());
        }
    }
}
