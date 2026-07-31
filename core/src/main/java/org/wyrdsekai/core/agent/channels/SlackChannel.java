package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.ConversationChannel;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional Slack conversation channel.
 *
 * <p>Outbound: sends messages via Slack Web API {@code chat.postMessage} with a bot token.
 * <p>Inbound: polls {@code conversations.history} every 5 seconds with a {@code oldest}
 * timestamp cursor. This avoids the need for a public URL (no Events API / Socket Mode).
 * <p>Setup: create a Slack app, add {@code chat:write} and {@code channels:history} scopes,
 * install to workspace, invite the bot to the channel, copy the bot token and channel ID.
 */
public class SlackChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String API = "https://slack.com/api/";
    private static final long POLL_INTERVAL_MS = 5000;

    private final String botToken;
    private final String channelId;
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile Thread listenerThread;
    private volatile String companionName;
    /** Slack timestamp cursor — only messages after this are fetched. */
    private volatile String oldestTs;
    /** The bot's own user ID, resolved on first poll to filter out self-messages. */
    private volatile String botUserId;

    public SlackChannel(String botToken, String channelId) {
        this.botToken = botToken;
        this.channelId = channelId;
        // Start cursor at "now" so we don't replay history
        this.oldestTs = String.valueOf(Instant.now().getEpochSecond());
    }

    @Override
    public String name() { return "slack"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var text = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            text += "\n> " + deepLink;
        }
        var body = MAPPER.createObjectNode()
            .put("channel", channelId)
            .put("text", text);

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(API + "chat.postMessage"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        try {
                            var json = MAPPER.readTree(resp.body());
                            if (json.path("ok").asBoolean(false)) {
                                log.debug("Slack message sent to channel {}", channelId);
                                return true;
                            }
                            log.warn("Slack API error: {}", json.path("error").asText());
                        } catch (Exception e) {
                            log.warn("Slack response parse error: {}", e.getMessage());
                        }
                        return false;
                    }
                    log.warn("Slack API returned HTTP {}: {}", resp.statusCode(), resp.body());
                    return false;
                });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public void startListener(String companionName) {
        this.companionName = companionName;
        if (listening.compareAndSet(false, true)) {
            // resume from the last-checkpointed
            // Slack ts cursor so messages between previous shutdown and now
            // aren't skipped (default oldestTs = "now" loses everything).
            var store = ChannelStateStore.get();
            if (store != null) {
                store.readOffset(name(), channelId).ifPresent(s -> {
                    oldestTs = s;
                    log.info("Slack listener resuming channel {} from ts {}", channelId, s);
                });
            }
            listenerThread = Thread.ofVirtual().name("slack-listener").start(this::pollLoop);
            log.info("Slack listener started for channel {}", channelId);
        }
    }

    /** Test seam: read the in-memory cursor without starting the poll thread. */
    String peekOldestTs() { return oldestTs; }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        log.info("Slack listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    private void pollLoop() {
        // Resolve the bot's own user ID so we can ignore our own messages
        resolveBotUserId();

        while (listening.get()) {
            try {
                var url = API + "conversations.history?channel=" + channelId
                    + "&oldest=" + oldestTs + "&limit=100";
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

                var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = MAPPER.readTree(resp.body());
                    if (json.path("ok").asBoolean(false)) {
                        processMessages(json);
                    } else {
                        log.warn("Slack conversations.history error: {}", json.path("error").asText());
                    }
                } else {
                    log.warn("Slack conversations.history returned HTTP {}", resp.statusCode());
                }

                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("Slack poll error: {}", e.getMessage());
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void processMessages(JsonNode response) {
        var messages = response.path("messages");
        if (!messages.isArray()) return;

        var store = ChannelStateStore.get();
        var publishedAny = false;

        for (var msg : messages) {
            // Skip bot messages, subtypes (joins, topic changes, etc.)
            var subtype = msg.path("subtype").asText("");
            if (!subtype.isEmpty()) continue;

            var userId = msg.path("user").asText("");
            if (userId.isEmpty()) continue;

            // Skip messages from the bot itself
            if (botUserId != null && botUserId.equals(userId)) continue;

            var text = msg.path("text").asText("");
            if (text.isBlank()) continue;

            var ts = msg.path("ts").asText("");
            if (!ts.isEmpty()) {
                // Advance cursor past this message
                oldestTs = ts;
                publishedAny = true;
            }

            // dedup before publish.
            if (!ts.isEmpty() && store != null && store.isProcessed(name(), ts)) {
                log.debug("Slack ts {} already processed — skipping", ts);
                continue;
            }

            // Route to companion via AgentEventStream
            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    if (store != null && !ts.isEmpty()) store.markProcessed(name(), ts);
                    stream.publishAgentMessage(
                        "slack-" + channelId, "Slack user " + userId,
                        companionId.get(),
                        "[from Slack user " + userId + "] " + text);
                    log.debug("Slack message from {} routed to companion '{}'",
                        userId, companionName);
                } else {
                    log.debug("Companion '{}' not found in registry — message dropped", companionName);
                }
            }
        }

        // Checkpoint cursor at end of batch (if we advanced it).
        if (publishedAny && store != null) {
            store.writeOffset(name(), channelId, oldestTs);
        }
    }

    /**
     * Resolve the bot's own user ID via {@code auth.test} so we can filter out self-messages.
     */
    private void resolveBotUserId() {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(API + "auth.test"))
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

            var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var json = MAPPER.readTree(resp.body());
                if (json.path("ok").asBoolean(false)) {
                    botUserId = json.path("user_id").asText(null);
                    log.debug("Slack bot user ID resolved: {}", botUserId);
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve Slack bot user ID: {}", e.getMessage());
        }
    }
}
