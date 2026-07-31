package org.wyrdsekai.between.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format regression tests. The cross-zone inference protocol is load-bearing
 * across zone boundaries where the provider and requestor may be on different
 * JVM versions or code revisions — field-name compatibility and null-handling
 * for optional fields must not silently drift.
 */
class NatsInferenceProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void subject_helpers_are_stable() {
        // These subject names are the wire contract. Changing them silently
        // would orphan existing peers on older builds.
        assertThat(NatsInferenceProtocol.requestSubject("alpha"))
            .isEqualTo("federation.inference.alpha.complete");
        assertThat(NatsInferenceProtocol.streamSubject("abc-123"))
            .isEqualTo("federation.inference.stream.abc-123");
    }

    @Test void request_round_trips_with_all_fields() throws Exception {
        var req = new NatsInferenceProtocol.Request(
            "stream-1", "beta", "agent-42",
            "wyrdsekai-3.5-9b",
            List.of(
                new NatsInferenceProtocol.Message("system", "You are a helpful agent."),
                new NatsInferenceProtocol.Message("user", "hello")),
            512, 0.7, true);

        var bytes = MAPPER.writeValueAsBytes(req);
        var decoded = MAPPER.readValue(bytes, NatsInferenceProtocol.Request.class);

        assertThat(decoded.streamId()).isEqualTo("stream-1");
        assertThat(decoded.sourceZone()).isEqualTo("beta");
        assertThat(decoded.agentId()).isEqualTo("agent-42");
        assertThat(decoded.model()).isEqualTo("wyrdsekai-3.5-9b");
        assertThat(decoded.messages()).hasSize(2);
        assertThat(decoded.messages().get(0).role()).isEqualTo("system");
        assertThat(decoded.messages().get(1).content()).isEqualTo("hello");
        assertThat(decoded.maxTokens()).isEqualTo(512);
        assertThat(decoded.temperature()).isEqualTo(0.7);
        assertThat(decoded.stream()).isTrue();
    }

    @Test void request_carries_source_node_for_household_check() throws Exception {
        // the requester's node id rides on the request
        // so a household provider can apply the auto-share quota exemption.
        var req = new NatsInferenceProtocol.Request(
            "stream-h", "beta", "agent", "m",
            List.of(new NatsInferenceProtocol.Message("user", "hi")),
            64, 0.0, false, "node-fam");
        var decoded = MAPPER.readValue(MAPPER.writeValueAsBytes(req),
            NatsInferenceProtocol.Request.class);
        assertThat(decoded.sourceNode()).isEqualTo("node-fam");
    }

    @Test void request_without_source_node_decodes_null() throws Exception {
        // Older clients / federation peers omit sourceNode → null (no exemption).
        var json = """
            {"streamId":"s9","sourceZone":"alpha","agentId":"a1",
             "model":"qwen","messages":[{"role":"user","content":"hi"}],
             "stream":false}
            """;
        var decoded = MAPPER.readValue(json, NatsInferenceProtocol.Request.class);
        assertThat(decoded.sourceNode()).isNull();
    }

    @Test void request_tolerates_nullable_optional_fields() throws Exception {
        // maxTokens/temperature are nullable — a peer on an older build or a
        // minimal harness may omit them. JSON must not fail on missing Integer/Double.
        var json = """
            {"streamId":"s2","sourceZone":"alpha","agentId":"a1",
             "model":"qwen","messages":[{"role":"user","content":"hi"}],
             "stream":false}
            """;
        var decoded = MAPPER.readValue(json, NatsInferenceProtocol.Request.class);

        assertThat(decoded.maxTokens()).isNull();
        assertThat(decoded.temperature()).isNull();
        assertThat(decoded.stream()).isFalse();
    }

    @Test void token_chunk_round_trip() throws Exception {
        // Per-token streaming chunk: token set, done=false, usage absent.
        var chunk = new NatsInferenceProtocol.StreamChunk(
            "s-1", "hello", null, false, null, null, null, null);

        var bytes = MAPPER.writeValueAsBytes(chunk);
        var decoded = MAPPER.readValue(bytes, NatsInferenceProtocol.StreamChunk.class);

        assertThat(decoded.streamId()).isEqualTo("s-1");
        assertThat(decoded.token()).isEqualTo("hello");
        assertThat(decoded.fullContent()).isNull();
        assertThat(decoded.done()).isFalse();
        assertThat(decoded.promptTokens()).isNull();
        assertThat(decoded.completionTokens()).isNull();
        assertThat(decoded.error()).isNull();
    }

    @Test void terminal_chunk_round_trip() throws Exception {
        // Terminal chunk: done=true + usage stats + full content, no per-token field.
        var chunk = new NatsInferenceProtocol.StreamChunk(
            "s-1", null, "hello world", true, 27, 10, "stop", null);

        var bytes = MAPPER.writeValueAsBytes(chunk);
        var decoded = MAPPER.readValue(bytes, NatsInferenceProtocol.StreamChunk.class);

        assertThat(decoded.done()).isTrue();
        assertThat(decoded.fullContent()).isEqualTo("hello world");
        assertThat(decoded.promptTokens()).isEqualTo(27);
        assertThat(decoded.completionTokens()).isEqualTo(10);
        assertThat(decoded.finishReason()).isEqualTo("stop");
        assertThat(decoded.error()).isNull();
    }

    @Test void error_chunk_round_trip() throws Exception {
        // Error path: done=true + error set, no content/usage.
        var chunk = new NatsInferenceProtocol.StreamChunk(
            "s-1", null, null, true, null, null, null, "backend timeout");

        var bytes = MAPPER.writeValueAsBytes(chunk);
        var decoded = MAPPER.readValue(bytes, NatsInferenceProtocol.StreamChunk.class);

        assertThat(decoded.done()).isTrue();
        assertThat(decoded.error()).isEqualTo("backend timeout");
        assertThat(decoded.token()).isNull();
        assertThat(decoded.fullContent()).isNull();
    }

    @Test void message_round_trip() throws Exception {
        var msg = new NatsInferenceProtocol.Message("assistant", "Hi there.");
        var bytes = MAPPER.writeValueAsBytes(msg);
        var decoded = MAPPER.readValue(bytes, NatsInferenceProtocol.Message.class);

        assertThat(decoded.role()).isEqualTo("assistant");
        assertThat(decoded.content()).isEqualTo("Hi there.");
    }
}
