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

class AnthropicAdapterTest {

    private MockHttpFixture mock;
    private AnthropicAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new AnthropicAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "anthropic.api_key".equals(slot) ? Optional.of("test-key") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_and_capabilities_are_phase_r_shape() {
        assertEquals("anthropic", adapter.namespace());
        assertTrue(adapter.capabilities().contains("complete"));
        assertTrue(adapter.capabilities().contains("vision"));
        assertEquals("anthropic.api_key", adapter.credentialSlot());
    }

    @Test
    void complete_returns_extracted_text() {
        mock.onPath("/v1/messages", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1},"
            + "\"stop_reason\":\"end_turn\"}"));
        var resp = adapter.invoke(new AdapterRequest("anthropic", "complete",
            Map.of("model", "claude-3-5-sonnet-latest",
                "messages", List.of(Map.of("role", "user", "content", "hi"))),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("hello", data.get("text"));
        assertNotNull(data.get("usage"));
    }

    @Test
    void missing_model_arg_returns_missing_arg() {
        var resp = adapter.invoke(new AdapterRequest("anthropic", "complete",
            Map.of("messages", List.of()), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void missing_credential_returns_credential_missing() {
        CredentialResolver.get().setSafeReader(s -> Optional.empty());
        var resp = adapter.invoke(new AdapterRequest("anthropic", "complete",
            Map.of("model", "x", "messages", List.of()), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_is_rejected() {
        var resp = adapter.invoke(new AdapterRequest("anthropic", "summon_demon",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void api_key_is_sent_as_x_api_key_header() {
        mock.onPath("/v1/messages", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"content\":[{\"type\":\"text\",\"text\":\"\"}]}"));
        adapter.invoke(new AdapterRequest("anthropic", "complete",
            Map.of("model", "x", "messages", List.of()), ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertEquals("test-key", rec.headers.get("x-api-key"));
        assertNotNull(rec.headers.get("anthropic-version"));
    }

    @Test
    void upstream_4xx_returns_upstream_error() {
        mock.onPath("/v1/messages", (ex, body) ->
            MockHttpFixture.Reply.json(401, "{\"error\":{\"message\":\"unauthorized\"}}"));
        var resp = adapter.invoke(new AdapterRequest("anthropic", "complete",
            Map.of("model", "x", "messages", List.of()), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("upstream_error", resp.error().code());
    }
}
