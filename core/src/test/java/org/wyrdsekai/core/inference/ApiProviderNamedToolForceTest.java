package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When exactly one tool is offered, the force names it.
 *
 * <h2>What went wrong</h2>
 * Live 2026-08-22 20:48:49 a ReAct step offered exactly one tool —
 * {@code create_room_from_template} — with {@code tool_choice: "required"}, and the 9B
 * answered 1,569 characters of prose about a room that did not exist. The generic
 * {@code "required"} is a request the small model can decline; the named form
 * {@code {"type":"function","function":{"name":…}}} is what llama-server's grammar
 * actually enforces. This is the wire contract for that upgrade.
 */
class ApiProviderNamedToolForceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode bodyOf(HttpRequest req) throws Exception {
        var bp = req.bodyPublisher().orElseThrow();
        var captured = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        bp.subscribe(new Flow.Subscriber<ByteBuffer>() {
            final StringBuilder buf = new StringBuilder();
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer b) { buf.append(java.nio.charset.StandardCharsets.UTF_8.decode(b)); }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { captured.set(buf.toString()); done.countDown(); }
        });
        done.await();
        return M.readTree(captured.get());
    }

    private static InferenceClient.ChatRequest request(List<InferenceClient.ToolDefinition> tools) {
        return new InferenceClient.ChatRequest("wyrdsekai-3.5-9b-v6-q4km",
            List.of(new InferenceClient.ChatMessage("user", "make the room")),
            256, 0.7, null, null, null, null, tools, "required", null, null);
    }

    private static InferenceClient.ToolDefinition tool(String name) {
        return InferenceClient.ToolDefinition.function(name, "desc", Map.of("type", "object"));
    }

    @Test
    @DisplayName("one tool + required becomes a named function force")
    void oneToolIsNamed() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var req = provider.buildChatRequest("http://localhost:8200", null,
            request(List.of(tool("create_room_from_template"))), Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.path("tool_choice").isObject()).isTrue();
        assertThat(body.path("tool_choice").path("function").path("name").asText())
            .isEqualTo("create_room_from_template");
    }

    @Test
    @DisplayName("two tools + required stays the generic required")
    void twoToolsStayGeneric() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var req = provider.buildChatRequest("http://localhost:8200", null,
            request(List.of(tool("create_room_from_template"), tool("decline_with_reason"))),
            Duration.ofSeconds(30));
        assertThat(bodyOf(req).path("tool_choice").asText()).isEqualTo("required");
    }
}
