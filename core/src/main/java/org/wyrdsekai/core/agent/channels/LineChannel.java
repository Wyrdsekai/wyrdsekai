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
 * LINE Messaging API alert channel (outbound only).
 *
 * <p>Sends push messages via the LINE Messaging API. LINE requires a public webhook
 * URL for inbound messages, which most self-hosted setups cannot provide. This
 * channel is therefore alert-only — it implements {@link AlertChannel}, not
 * {@code ConversationChannel}. Can be upgraded to bidirectional if users expose
 * a public endpoint (e.g., via ngrok or cloud deployment).</p>
 *
 * <p>Setup: create a LINE Official Account, enable Messaging API in the LINE
 * Developers console, issue a long-lived channel access token, and note the
 * target user's LINE user ID (starts with {@code U}).</p>
 */
public class LineChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(LineChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String PUSH_API = "https://api.line.me/v2/bot/message/push";

    private final String channelToken;
    private final String userId;

    public LineChannel(String channelToken, String userId) {
        this.channelToken = channelToken;
        this.userId = userId;
    }

    @Override
    public String name() { return "line"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var text = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            text += "\n> " + deepLink;
        }

        // LINE push message body: { "to": userId, "messages": [{ "type": "text", "text": "..." }] }
        var msgNode = MAPPER.createObjectNode()
            .put("type", "text")
            .put("text", text);
        var body = MAPPER.createObjectNode()
            .put("to", userId);
        body.putArray("messages").add(msgNode);

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(PUSH_API))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + channelToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        log.debug("LINE push message sent to user {}", userId);
                        return true;
                    }
                    log.warn("LINE API returned {}: {}", resp.statusCode(), resp.body());
                    return false;
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
