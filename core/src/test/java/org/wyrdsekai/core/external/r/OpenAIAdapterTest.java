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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenAIAdapterTest {

    private MockHttpFixture mock;
    private OpenAIAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new OpenAIAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "openai.api_key".equals(slot) ? Optional.of("sk-test") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void capabilities_include_phase_r_methods() {
        assertEquals(Set.of("complete", "vision", "embed", "dalle"), adapter.capabilities());
    }

    @Test
    void complete_extracts_choice_text() {
        mock.onPath("/v1/chat/completions", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"choices\":[{\"message\":{\"content\":\"hi there\"}}],"
            + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"));
        var resp = adapter.invoke(new AdapterRequest("openai", "complete",
            Map.of("model", "gpt-4o", "messages",
                List.of(Map.of("role", "user", "content", "hi"))),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success(), () -> "expected ok, got " + resp.error());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("hi there", data.get("text"));
    }

    @Test
    void embed_returns_vectors() {
        mock.onPath("/v1/embeddings", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"model\":\"text-embedding-3-small\","
            + "\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}"));
        var resp = adapter.invoke(new AdapterRequest("openai", "embed",
            Map.of("model", "text-embedding-3-small", "input", "hello"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("vectors"));
    }

    @Test
    void dalle_requires_prompt() {
        var resp = adapter.invoke(new AdapterRequest("openai", "dalle",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void dalle_returns_images_array() {
        mock.onPath("/v1/images/generations", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"data\":[{\"url\":\"https://example.com/x.png\"}]}"));
        var resp = adapter.invoke(new AdapterRequest("openai", "dalle",
            Map.of("prompt", "a cat"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("images"));
    }

    @Test
    void bearer_auth_header_is_set() {
        mock.onPath("/v1/chat/completions", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"choices\":[]}"));
        adapter.invoke(new AdapterRequest("openai", "complete",
            Map.of("model", "x", "messages", List.of()),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertEquals("Bearer sk-test", rec.headers.get("authorization"));
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("openai", "fly_to_moon",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }

}
