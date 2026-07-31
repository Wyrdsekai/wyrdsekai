package org.wyrdsekai.core.external.w;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleTranslateAdapterTest {

    @AfterEach
    void tearDown() { AdapterTestHarness.clearCredentials(); }

    @Test
    void declares_translate_namespace() {
        var a = new GoogleTranslateAdapter();
        assertEquals("translate", a.namespace());
        assertTrue(a.capabilities().contains("translate"));
        assertTrue(a.capabilities().contains("detect_language"));
        assertEquals("google.translate.api_key", a.credentialSlot());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        var a = new GoogleTranslateAdapter();
        var resp = a.invoke(AdapterTestHarness.req("translate", "summon", "x", "y"));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void translate_missing_text_is_missing_arg() {
        var a = new GoogleTranslateAdapter();
        var resp = a.invoke(AdapterTestHarness.req("translate", "translate", "targetLang", "ja"));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void translate_without_credential_is_missing_credential() {
        AdapterTestHarness.clearCredentials();
        var a = new GoogleTranslateAdapter();
        var resp = a.invoke(AdapterTestHarness.req("translate", "translate",
            "text", "Hello", "targetLang", "ja"));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void translate_success_parses_translations() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"data\":{\"translations\":[{\"translatedText\":\"こんにちは\",\"detectedSourceLanguage\":\"en\"}]}}")) {
            AdapterTestHarness.setCredential("google.translate.api_key", "k");
            var a = new GoogleTranslateAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("translate", "translate",
                "text", "Hello", "targetLang", "ja"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("こんにちは", data.get("translated"));
            assertEquals("en", data.get("sourceLang"));
        }
    }

    @Test
    void detect_language_success_parses_first_detection() {
        try (var srv = AdapterTestHarness.startMock(200, "application/json",
            "{\"data\":{\"detections\":[[{\"language\":\"ja\",\"confidence\":0.97}]]}}")) {
            AdapterTestHarness.setCredential("google.translate.api_key", "k");
            var a = new GoogleTranslateAdapter(new HttpAdapterSupport(), srv.baseUrl());
            var resp = a.invoke(AdapterTestHarness.req("translate", "detect_language",
                "text", "こんにちは"));
            assertTrue(resp.success());
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) resp.data();
            assertEquals("ja", data.get("lang"));
        }
    }

    @Test
    void detect_missing_text_is_missing_arg() {
        var a = new GoogleTranslateAdapter();
        var resp = a.invoke(AdapterTestHarness.req("translate", "detect_language"));
        assertEquals("missing_arg", resp.error().code());
    }
}
