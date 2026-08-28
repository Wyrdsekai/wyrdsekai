package org.wyrdsekai.core.inference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Root-cause regression for the glimmerhouse born-mute trap: a base URL
 * configured WITH a trailing /v1 must not produce /v1/v1/... requests.
 */
class ABaseUrlNeverSmugglesItsOwnV1Test {

    @Test
    void aTrailingV1IsNormalizedAway() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] path = {""};
        server.createContext("/", ex -> {
            path[0] = ex.getRequestURI().getPath();
            var body = """
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            var client = new InferenceClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "", Duration.ofSeconds(5));
            var out = client.complete("m", "", "hi", 16, 0.0).get(5, TimeUnit.SECONDS);
            assertEquals("ok", out);
            assertEquals("/v1/chat/completions", path[0],
                "exactly one /v1 — no matter how the base was configured");
        } finally {
            server.stop(0);
        }
    }
}
