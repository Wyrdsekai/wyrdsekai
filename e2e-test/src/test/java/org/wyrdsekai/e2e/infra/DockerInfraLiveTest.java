package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for {@link DockerInfraExtension}.
 * Verifies the extension actually starts Docker containers and they become healthy.
 *
 * <p>Requires Docker to be available. Skips gracefully if not.
 */
@DockerProfile("nats")
@Tag("integration")
// Starts a Docker nats container; routes to the infra tier so the hermetic
// `./gradlew test` lane (excludes needs-*) skips it instead of colliding with a
// host already running nats.
@Tag("needs-nats")
class DockerInfraLiveTest {

    @Test
    void nats_is_healthy_after_extension_runs() throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder()
            .uri(URI.create(DockerInfraExtension.natsMonitorUrl() + "/healthz"))
            .timeout(Duration.ofSeconds(5)).GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "NATS should be healthy at " +
            DockerInfraExtension.natsMonitorUrl());
    }

    @Test
    void nats_accepts_connections_on_client_port() throws Exception {
        // Verify the client port is reachable by connecting a socket
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 4222), 5000);
            assertTrue(socket.isConnected(), "Should connect to NATS on port 4222");
        }
    }

    @Test
    void nats_monitor_returns_server_info() throws Exception {
        var client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder()
            .uri(URI.create(DockerInfraExtension.natsMonitorUrl() + "/varz"))
            .timeout(Duration.ofSeconds(5)).GET().build();

        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("server_id"),
            "NATS /varz should return server info JSON");
    }
}
