package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for the "relay" Docker profile.
 * Starts NATS + llama-phone (0.6B) + llama-laptop (4B).
 *
 * <p>Requires Docker + GPU + model GGUFs in data/models/.
 * Skips gracefully if unavailable.
 */
@DockerProfile("relay")
@Tag("integration")
// Starts a Docker nats + two llama-server containers. Without these needs-* tags
// the hermetic `./gradlew test` lane (which only excludes needs-*) would run this
// and the compose `up` collides with a host already running nats/llama (e.g. a
// prod node). These tags route it to the infra tier instead.
@Tag("needs-nats")
@Tag("needs-inference")
@Tag("needs-gpu")
class DockerInfraRelayLiveTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void nats_healthy() throws Exception {
        var resp = get(DockerInfraExtension.natsMonitorUrl() + "/healthz");
        assertEquals(200, resp.statusCode(), "NATS should be healthy");
    }

    @Test
    void llama_phone_healthy() throws Exception {
        var resp = get(DockerInfraExtension.llamaPhoneUrl() + "/health");
        assertEquals(200, resp.statusCode(), "llama-phone (0.6B) should be healthy");
    }

    @Test
    void llama_laptop_healthy() throws Exception {
        var resp = get(DockerInfraExtension.llamaLaptopUrl() + "/health");
        assertEquals(200, resp.statusCode(), "llama-laptop (4B) should be healthy");
    }

    @Test
    void llama_phone_accepts_completion() throws Exception {
        var body = """
            {"model":"default","messages":[{"role":"user","content":"Say hello"}],\
            "max_tokens":10,"temperature":0}""";
        var req = HttpRequest.newBuilder()
            .uri(URI.create(DockerInfraExtension.llamaPhoneUrl() + "/v1/chat/completions"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "llama-phone should accept completions");
        assertTrue(resp.body().contains("choices"),
            "Response should contain choices: " + resp.body());
    }

    private HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
