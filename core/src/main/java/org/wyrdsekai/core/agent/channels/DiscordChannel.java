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
 * Discord alert channel via incoming webhook.
 *
 * <p>One-way: sends notifications as webhook messages. For bidirectional,
 * use a full Discord bot (future — requires gateway connection).</p>
 *
 * <p>Setup: Server Settings → Integrations → Webhooks → New Webhook → Copy URL.</p>
 */
public class DiscordChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private final String webhookUrl;

    public DiscordChannel(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public String name() { return "discord"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var content = "**[" + fromAgent + "]** " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            content += "\n→ `" + deepLink + "`";
        }

        var body = MAPPER.createObjectNode()
            .put("content", content)
            .put("username", fromAgent + " — Wyrdsekai");

        var request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(10))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.debug("Discord notification sent");
                    return true;
                }
                log.warn("Discord webhook returned {}: {}", resp.statusCode(), resp.body());
                return false;
            });
    }
}
