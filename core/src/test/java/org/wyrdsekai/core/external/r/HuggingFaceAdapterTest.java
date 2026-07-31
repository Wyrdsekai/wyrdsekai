package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HuggingFaceAdapterTest {

    private MockHttpFixture mock;
    private HuggingFaceAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new HuggingFaceAdapter(new HttpAdapterSupport(), mock.baseUrl(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "huggingface.api_key".equals(slot) ? Optional.of("hf-tk") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_hf() {
        assertEquals("hf", adapter.namespace());
    }

    @Test
    void inference_posts_to_models_endpoint() {
        mock.onPath("/models/", (ex, body) -> MockHttpFixture.Reply.json(
            "[{\"label\":\"POSITIVE\",\"score\":0.99}]"));
        var resp = adapter.invoke(new AdapterRequest("hf", "inference",
            Map.of("model", "distilbert/sst2", "inputs", "wonderful"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success(), () -> "got " + resp.error());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("output"));
    }

    @Test
    void inference_requires_model_and_inputs() {
        var resp = adapter.invoke(new AdapterRequest("hf", "inference",
            Map.of("model", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void search_models_works_without_credential() {
        CredentialResolver.get().setSafeReader(s -> Optional.empty());
        mock.onPath("/api/models", (ex, body) -> MockHttpFixture.Reply.json(
            "[{\"modelId\":\"foo/bar\"}]"));
        var resp = adapter.invoke(new AdapterRequest("hf", "search_models",
            Map.of("query", "bert"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void search_models_requires_query() {
        var resp = adapter.invoke(new AdapterRequest("hf", "search_models",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("hf", "fine_tune",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}
