package org.wyrdsekai.e2e.tier3;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plane-B — gate ON, but plain outbound HTTP still works.
 * The phase-1 gate scrubs credentials and blocks key-backed lateral movement;
 * it does NOT wall off the web (operator 2026-07-02 — agents already reach the
 * web via web_search). This proves a dispatched task's shell, running under the
 * enforcing gate with NO credentials, can still open a real outbound TCP/HTTP
 * connection to a host with no allowlist entry.
 *
 * <p>The probe uses bash's {@code /dev/tcp} to make a genuine HTTP GET against a
 * local {@link HttpServer}, so the assertion is a real network round-trip from
 * inside the gated subprocess — not a mock.
 */
@Tag("e2e")
final class CodingEgressGateHttpStaysOpenE2ETest extends CodingEgressGateE2EBase {

    private HttpServer server;
    private final AtomicBoolean hit = new AtomicBoolean(false);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hit.set(true);
            var body = "EGRESS_HTTP_OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void enforcing_gate_still_permits_plain_outbound_http() throws Exception {
        if (!bashOnPath()) return; // /dev/tcp needs bash; env-scrub proof lives in the ON test

        int port = server.getAddress().getPort();
        // A credential-free HTTP GET via bash /dev/tcp — a real outbound socket.
        var probe = java.util.List.of("/bin/bash", "-c", """
            echo "SSH_AUTH_SOCK=${SSH_AUTH_SOCK:-<empty>}"
            echo "MYCLOUD_TOKEN=${MYCLOUD_TOKEN:-<empty>}"
            exec 3<>/dev/tcp/127.0.0.1/%d
            printf 'GET / HTTP/1.0\\r\\nHost: 127.0.0.1\\r\\n\\r\\n' >&3
            cat <&3
            """.formatted(port));

        var out = runProbeThroughGate(enforcingFromConfig(), probe);

        // Credentials are gone (gate is ON) …
        assertTrue(out.sshSockEmpty(),
            () -> "gate must still be enforcing for this proof; got:\n" + out.stdout());
        // … yet the outbound HTTP round-trip completed.
        assertTrue(out.stdout().contains("EGRESS_HTTP_OK"),
            () -> "gated subprocess should reach the HTTP server; got:\n" + out.stdout());
        assertTrue(hit.get(), "the HTTP server should have received the request");
    }

    private static boolean bashOnPath() {
        return new java.io.File("/bin/bash").canExecute();
    }
}
