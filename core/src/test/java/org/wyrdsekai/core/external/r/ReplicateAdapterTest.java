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

class ReplicateAdapterTest {

    private MockHttpFixture mock;
    private ReplicateAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new ReplicateAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "replicate.token".equals(slot) ? Optional.of("rep-tk") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void run_returns_prediction_id() {
        mock.onPath("/v1/predictions", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"id\":\"pred-123\",\"status\":\"starting\",\"output\":null}"));
        var resp = adapter.invoke(new AdapterRequest("replicate", "run",
            Map.of("model", "v1", "input", Map.of("prompt", "x")),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success(), () -> "got " + resp.error());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("pred-123", data.get("predictionId"));
    }

    @Test
    void status_returns_logs_and_output() {
        mock.onPath("/v1/predictions/", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"status\":\"succeeded\",\"output\":[\"https://r/x.png\"],\"logs\":\"done\"}"));
        var resp = adapter.invoke(new AdapterRequest("replicate", "status",
            Map.of("predictionId", "abc"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("succeeded", data.get("status"));
        assertEquals("done", data.get("logs"));
    }

    @Test
    void run_uses_token_auth() {
        mock.onPath("/v1/predictions", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"id\":\"x\"}"));
        adapter.invoke(new AdapterRequest("replicate", "run",
            Map.of("model", "v", "input", Map.of()),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertEquals("Token rep-tk", rec.headers.get("authorization"));
    }

    @Test
    void run_requires_model_and_input() {
        var resp = adapter.invoke(new AdapterRequest("replicate", "run",
            Map.of("model", "v"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void status_requires_prediction_id() {
        var resp = adapter.invoke(new AdapterRequest("replicate", "status",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }
}
