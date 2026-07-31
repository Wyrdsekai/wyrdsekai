package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiAdapterTest {

    private MockHttpFixture mock;
    private GeminiAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new GeminiAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "gemini.api_key".equals(slot) ? Optional.of("g-test") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_gemini() {
        assertEquals("gemini", adapter.namespace());
        assertEquals("gemini.api_key", adapter.credentialSlot());
    }

    @Test
    void complete_with_prompt_wraps_into_contents() {
        mock.onPath("/v1beta/models", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"reply\"}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":3}}"));
        var resp = adapter.invoke(new AdapterRequest("gemini", "complete",
            Map.of("model", "gemini-1.5-pro", "prompt", "ping"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success(), () -> "got " + resp.error());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("reply", data.get("text"));
    }

    @Test
    void api_key_appears_in_query_string() {
        mock.onPath("/v1beta/models", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"candidates\":[]}"));
        adapter.invoke(new AdapterRequest("gemini", "complete",
            Map.of("model", "gemini-1.5-pro", "prompt", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertTrue(rec.path.contains("key=g-test"), "expected key in query, got " + rec.path);
    }

    @Test
    void missing_prompt_and_contents_fails() {
        var resp = adapter.invoke(new AdapterRequest("gemini", "complete",
            Map.of("model", "gemini-1.5-pro"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void contents_arg_passed_through() {
        mock.onPath("/v1beta/models", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"candidates\":[]}"));
        adapter.invoke(new AdapterRequest("gemini", "vision",
            Map.of("model", "gemini-1.5-pro", "contents",
                List.of(Map.of("role", "user", "parts", List.of(Map.of("text", "see this"))))),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertTrue(rec.body.contains("\"role\":\"user\""), "expected user role, got " + rec.body);
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("gemini", "magic",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}
