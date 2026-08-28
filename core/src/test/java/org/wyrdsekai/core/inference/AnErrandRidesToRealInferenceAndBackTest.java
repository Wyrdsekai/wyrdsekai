package org.wyrdsekai.core.inference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.AdmissionGate;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.CapabilityTable;
import org.wyrdsekai.hermod.DefaultRouter;
import org.wyrdsekai.hermod.LocalAdmissionGate;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: envelope → router → admission gate → HermodInferenceExecutor →
 * the REAL InferenceClient over HTTP → an OpenAI-shaped server → result
 * back through the mesh. The server also asserts the /v1-appending
 * contract that once left a companion born mute.
 */
class AnErrandRidesToRealInferenceAndBackTest {

    static HttpServer server;
    static int port;
    static volatile String lastPath = "";
    static volatile String lastBody = "";

    @BeforeAll
    static void inferenceStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().getPath();
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var body = """
                {"choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"the answer, carried home"}}]}
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
    void theFullRide() {
        var clock = Clock.systemUTC();
        var table = new CapabilityTable(Duration.ofMinutes(1));
        table.merge(new Capability("home-node", "hh1", "llm.dense-9b", List.of("stub"),
            List.of(), true, true, 0.1, Instant.now()));

        var client = new InferenceClient("http://127.0.0.1:" + port, "", Duration.ofSeconds(10));
        var executor = new HermodInferenceExecutor(client, 10);
        var gate = new LocalAdmissionGate(clock, 4096, g -> true, e -> false);
        var mesh = new Mesh(new DefaultRouter(table, clock),
            (e, cap) -> Mesh.local(gate, executor));

        var envelope = new TaskEnvelope("e-ride", "hh1", "phone", HermodInferenceExecutor.TASK_TYPE,
            "none", "llm.dense-9b", Map.of("model", "stub", "prompt", "hello from the mesh"),
            256, Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), new byte[]{1});

        var result = mesh.submit(envelope);

        assertTrue(result.ok(), result.error());
        assertEquals("the answer, carried home", result.output());
        assertEquals("/v1/chat/completions", lastPath,
            "base URL must not carry /v1 — the client appends the full path");
    }

    @Test
    void aNothinkSeatTellsTheTemplateSo() {
        var clock = Clock.systemUTC();
        var table = new CapabilityTable(Duration.ofMinutes(1));
        table.merge(new Capability("home-node", "hh1", "llm.a3b", List.of("stub"),
            List.of(), true, true, 0.1, Instant.now()));
        var client = new InferenceClient("http://127.0.0.1:" + port, "", Duration.ofSeconds(10));
        var executor = new HermodInferenceExecutor(client, 10, false); // nothink seat
        var mesh = new Mesh(new DefaultRouter(table, clock),
            (e, cap) -> Mesh.local(
                new LocalAdmissionGate(clock, 4096, g -> true, e2 -> false), executor));
        var envelope = new TaskEnvelope("e-nothink", "hh1", "phone", HermodInferenceExecutor.TASK_TYPE,
            "none", "llm.a3b", Map.of("model", "stub", "prompt", "go"),
            128, Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), new byte[]{1});
        var result = mesh.submit(envelope);
        assertTrue(result.ok(), result.error());
        assertTrue(lastBody.contains("\"chat_template_kwargs\""), lastBody);
        assertTrue(lastBody.contains("\"enable_thinking\":false"), lastBody);
    }

    @Test
    void anOversizedErrandIsRefusedAtTheDoorNotTheServer() {
        var clock = Clock.systemUTC();
        var gate = new LocalAdmissionGate(clock, 128, g -> true, e -> false);
        var envelope = new TaskEnvelope("e-big", "hh1", "phone", HermodInferenceExecutor.TASK_TYPE,
            "none", "llm.dense-9b", Map.of("model", "stub", "prompt", "x"),
            999_999, Instant.now(), Instant.now().plusSeconds(30), Optional.empty(), new byte[]{1});
        var d = gate.consider(envelope);
        assertEquals(AdmissionGate.Verdict.REFUSE, d.verdict());
        assertTrue(d.reason().contains("ceiling"), d.reason());
    }
}
