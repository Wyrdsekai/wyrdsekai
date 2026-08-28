package org.wyrdsekai.core.inference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full-request ride: a ChatRequest WITH TOOLS serializes into an
 * envelope, executes through the real client against an OpenAI-shaped
 * server that answers with a tool_call, and the folded content comes
 * back ReAct-parseable — identical to the router's own folding.
 */
class ABunshinTurnRidesTheMeshWithItsToolsTest {

    static HttpServer server;
    static int port;
    static volatile String lastBody = "";

    @BeforeAll
    static void stub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var body = """
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
                  "role":"assistant","content":"checking the library",
                  "tool_calls":[{"id":"c1","type":"function","function":{
                    "name":"library_card","arguments":"{\\"query\\":\\"vel shara\\"}"}}]}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void toolsSurviveTheRideAndFoldLikeTheRouter() throws Exception {
        var client = new InferenceClient("http://127.0.0.1:" + port, "", Duration.ofSeconds(10));
        var executor = new HermodInferenceExecutor(client, 10);

        var tools = List.of(InferenceClient.ToolDefinition.function(
            "library_card", "search the library", Json.mapper().createObjectNode()));
        var request = new InferenceClient.ChatRequest("default",
            List.of(new InferenceClient.ChatMessage("user", "find the vel shara")),
            256, 0.5, null, null, null, null, tools, "auto", null, null);

        var envelope = new TaskEnvelope("e-tools", "hh1", "phone",
            HermodInferenceExecutor.TASK_TYPE_FULL, "none", "llm.local-gpu",
            Map.of("chatRequestJson", Json.mapper().writeValueAsString(request)),
            256, Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), new byte[]{1});

        var result = executor.execute(envelope);
        assertTrue(result.ok(), result.error());
        assertTrue(lastBody.contains("library_card"), "tool definitions reached the backend");

        var resp = Json.mapper().readValue(result.output(), InferenceClient.ChatResponse.class);
        var folded = InferenceRouter.foldedContent(resp);
        assertTrue(folded.contains("checking the library"), folded);
        assertTrue(folded.contains("\"action\":\"library_card\""), folded);
        assertTrue(folded.contains("vel shara"), folded);
    }
}
