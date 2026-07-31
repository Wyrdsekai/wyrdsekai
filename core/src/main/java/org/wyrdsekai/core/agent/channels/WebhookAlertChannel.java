package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AlertChannel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Generic webhook alert channel — POST JSON to any URL.
 * Covers: Google Chat, Slack (incoming webhook), Pushover, IFTTT, Zapier,
 * Make, n8n, Home Assistant, Matrix, or any custom endpoint.
 */
public class WebhookAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private final String url;
    private final String label;

    public WebhookAlertChannel(String url) {
        this(url, "webhook");
    }

    public WebhookAlertChannel(String url, String label) {
        this.url = url;
        this.label = label;
    }

    @Override
    public String name() { return label; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var body = MAPPER.createObjectNode()
            .put("text", "[" + fromAgent + "] " + message)
            .put("priority", priority)
            .put("from", fromAgent)
            .put("source", "wyrdsekai");
        if (deepLink != null && !deepLink.isBlank()) {
            body.put("link", deepLink);
        }

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(10))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.debug("Webhook notification sent to {}", label);
                    return true;
                }
                log.warn("Webhook '{}' returned {}: {}", label, resp.statusCode(), resp.body());
                return false;
            });
    }
}
