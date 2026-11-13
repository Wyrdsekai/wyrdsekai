package org.wyrdsekai.server.http;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.BrainLimbs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HealthRoutesTest {

    @Test void initially_not_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        assertThat(health.isReady()).isFalse();
    }

    @Test void set_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        health.setReady(true);
        assertThat(health.isReady()).isTrue();
    }

    @Test void set_not_ready() {
        var metrics = new MetricsCollector();
        var health = new HealthRoutes(metrics);
        health.setReady(true);
        health.setReady(false);
        assertThat(health.isReady()).isFalse();
    }

    /** The brainstem reads this to start a brain the node has run without (household node, 2026-11-11). */
    @Test void health_says_when_the_brain_this_node_uses_here_is_numb() throws Exception {
        var map = BodyMap.inMemory();
        map.attach(BrainLimbs.forBackend("llama-server", "llama-server", "http://127.0.0.1:8200", 1,
            Duration.ofSeconds(30), null, null));
        var app = Javalin.create(cfg -> new HealthRoutes(new MetricsCollector()).register(cfg.routes)).start(0);
        try {
            var client = HttpClient.newHttpClient();
            var get = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/health")).build();
            assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).body())
                .as("nothing has reported on it yet").doesNotContain("\"brain\"");
            map.heartbeat(BrainLimbs.id("llama-server"), false, null);
            assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).body()).contains("\"brain\":\"numb\"");
            map.heartbeat(BrainLimbs.id("llama-server"), true, null);
            assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).body()).contains("\"brain\":\"up\"");
        } finally {
            app.stop();
            BodyMap.resetForTests();
        }
    }
}
