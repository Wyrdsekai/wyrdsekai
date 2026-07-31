package org.wyrdsekai.daemon.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void request_roundTrip() throws Exception {
        var req = new InferenceRequest(
            "req-001", "qwen3-4b-q4",
            List.of(
                new InferenceRequest.ChatMessage("system", "You are helpful."),
                new InferenceRequest.ChatMessage("user", "Hello!")
            ),
            256, 0.7
        );

        var json = MAPPER.writeValueAsString(req);
        var parsed = MAPPER.readValue(json, InferenceRequest.class);

        assertThat(parsed.requestId()).isEqualTo("req-001");
        assertThat(parsed.messages()).hasSize(2);
        assertThat(parsed.messages().get(0).role()).isEqualTo("system");
    }

    @Test
    void response_ok() throws Exception {
        var resp = InferenceResponse.ok("req-001", "Hello there!", 10, 5);

        assertThat(resp.hasError()).isFalse();
        assertThat(resp.content()).isEqualTo("Hello there!");

        var json = MAPPER.writeValueAsString(resp);
        var parsed = MAPPER.readValue(json, InferenceResponse.class);
        assertThat(parsed).isEqualTo(resp);
    }

    @Test
    void response_error() throws Exception {
        var resp = InferenceResponse.error("req-001", "Model not loaded");

        assertThat(resp.hasError()).isTrue();
        assertThat(resp.content()).isNull();

        var json = MAPPER.writeValueAsString(resp);
        var parsed = MAPPER.readValue(json, InferenceResponse.class);
        assertThat(parsed.error()).isEqualTo("Model not loaded");
    }
}
