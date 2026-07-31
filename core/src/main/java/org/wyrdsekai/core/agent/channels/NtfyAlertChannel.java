package org.wyrdsekai.core.agent.channels;

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
 * Alert channel via ntfy.sh (or self-hosted ntfy server).
 * Zero-config push notifications — user installs ntfy app, subscribes to a topic.
 * We POST to the topic. That's it.
 *
 * @see <a href="https://ntfy.sh">ntfy.sh</a>
 */
public class NtfyAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(NtfyAlertChannel.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private final String server;
    private final String topic;

    public NtfyAlertChannel(String topic) {
        this("https://ntfy.sh", topic);
    }

    public NtfyAlertChannel(String server, String topic) {
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        this.topic = topic;
    }

    @Override
    public String name() { return "ntfy"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var ntfyPriority = switch (priority) {
            case "critical" -> "5";
            case "normal" -> "3";
            default -> "2";
        };

        var body = deepLink != null && !deepLink.isBlank()
            ? "[" + fromAgent + "] " + message + "\n→ " + deepLink
            : "[" + fromAgent + "] " + message;

        var request = HttpRequest.newBuilder()
            .uri(URI.create(server + "/" + topic))
            .header("Title", fromAgent + " — Wyrdsekai")
            .header("Priority", ntfyPriority)
            .header("Tags", "crystal_ball")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
            .build();

        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    log.debug("ntfy notification sent to topic '{}'", topic);
                    return true;
                }
                log.warn("ntfy returned {}: {}", resp.statusCode(), resp.body());
                return false;
            });
    }
}
