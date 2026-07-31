package org.wyrdsekai.e2e.tier1;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.LlamaServerFixture;
import org.wyrdsekai.e2e.infra.ModelManager;
import org.wyrdsekai.e2e.infra.NodeProfile;
import org.wyrdsekai.e2e.infra.PortAllocator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP roundtrip: InferenceClient → llama-server (Qwen3-0.6B).
 */
@Tag("smoke")
class InferenceClientLlamaServerTest {

    private static LlamaServerFixture llama;
    private static InferenceClient client;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaServerFixture.assumeAvailable();
        LlamaServerFixture.assumeModelAvailable(NodeProfile.PHONE);

        var modelPath = ModelManager.modelPath(NodeProfile.PHONE);
        var port = PortAllocator.allocate();
        llama = new LlamaServerFixture(modelPath, port);
        llama.start();

        client = new InferenceClient(llama.baseUrl());
    }

    @AfterAll
    static void tearDown() {
        if (llama != null) llama.stop();
    }

    @Test
    void chat_completion_returns_non_empty() throws Exception {
        var messages = List.of(
            new InferenceClient.ChatMessage("system", "You are a helpful assistant."),
            new InferenceClient.ChatMessage("user", "Say hello in exactly one sentence.")
        );
        var request = new InferenceClient.ChatRequest(null, messages, 64, 0.7);
        var response = client.chatCompletion(request).get();

        assertNotNull(response);
        assertFalse(response.choices().isEmpty(), "Should have at least one choice");
        var text = response.choices().getFirst().message().content();
        assertNotNull(text);
        assertFalse(text.isBlank(), "Response should not be blank");
    }

    @Test
    void health_check_returns_true() throws Exception {
        var healthy = client.healthCheck("/health").get();
        assertTrue(healthy, "llama-server should report healthy");
    }
}
