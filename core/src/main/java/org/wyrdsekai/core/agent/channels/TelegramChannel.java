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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bidirectional Telegram conversation channel.
 *
 * <p>Outbound: sends messages via Bot API sendMessage.
 * <p>Inbound: long-polls getUpdates, routes messages to companion via AgentEventStream.
 * <p>User creates a bot via @BotFather, sends /start, and pastes the bot token + chat ID.
 */
public class TelegramChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    private static final String API = "https://api.telegram.org/bot";

    private final String botToken;
    private final String chatId;
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);
    private volatile Thread listenerThread;
    private volatile String companionName;

    public TelegramChannel(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
    }

    @Override
    public String name() { return "telegram"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var text = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            text += "\n→ " + deepLink;
        }
        var body = MAPPER.createObjectNode()
            .put("chat_id", chatId)
            .put("text", text);

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(API + botToken + "/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        log.debug("Telegram message sent to chat {}", chatId);
                        return true;
                    }
                    log.warn("Telegram API returned {}: {}", resp.statusCode(), resp.body());
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
            // resume from the last-checkpointed offset
            // rather than 0. Without this, restart either produces a replay
            // storm (re-fetch buffered updates) or message loss (Telegram
            // already trimmed behind the previous ack).
            var store = ChannelStateStore.get();
            if (store != null) {
                store.readOffset(name(), chatId)
                    .ifPresent(s -> {
                        try {
                            lastUpdateId.set(Long.parseLong(s));
                            log.info("Telegram listener resuming chat {} from update_id {}",
                                chatId, s);
                        } catch (NumberFormatException nfe) {
                            log.warn("Telegram offset for chat {} not a long: '{}' — starting from 0",
                                chatId, s);
                        }
                    });
            }
            listenerThread = Thread.ofVirtual().name("telegram-listener").start(this::pollLoop);
            log.info("Telegram listener started for chat {}", chatId);
        }
    }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        log.info("Telegram listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    /** Test seam: read the in-memory offset without starting the poll thread. */
    long peekLastUpdateId() { return lastUpdateId.get(); }

    private void pollLoop() {
        while (listening.get()) {
            try {
                var url = API + botToken + "/getUpdates?timeout=30&offset=" + (lastUpdateId.get() + 1);
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(35))
                    .build();

                var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = MAPPER.readTree(resp.body());
                    processUpdates(json);
                } else {
                    log.warn("Telegram getUpdates returned {}", resp.statusCode());
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("Telegram poll error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    private void processUpdates(JsonNode response) {
        var results = response.path("result");
        if (!results.isArray()) return;

        var store = ChannelStateStore.get();

        for (var update : results) {
            var updateId = update.path("update_id").asLong();
            lastUpdateId.set(updateId);

            var message = update.path("message");
            if (message.isMissingNode()) continue;

            var msgChatId = message.path("chat").path("id").asText();
            if (!chatId.equals(msgChatId)) continue;

            var text = message.path("text").asText("");
            if (text.isBlank()) continue;

            var externalId = String.valueOf(updateId);
            // dedup before publish. If the same
            // update_id resurfaces (e.g. crash between publish and offset
            // checkpoint, then replay), skip it.
            if (store != null && store.isProcessed(name(), externalId)) {
                log.debug("Telegram update_id {} already processed — skipping", updateId);
                continue;
            }

            var fromName = message.path("from").path("first_name").asText("Telegram user");

            // Route to companion via AgentEventStream — resolve companion by name
            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    // Mark BEFORE publish so a crash mid-publish still records intent.
                    if (store != null) store.markProcessed(name(), externalId);
                    stream.publishAgentMessage(
                        "telegram-" + msgChatId, fromName,
                        companionId.get(),
                        "[from " + fromName + " via Telegram] " + text);
                    log.debug("Telegram message from {} routed to companion '{}'",
                        fromName, companionName);
                } else {
                    log.debug("Companion '{}' not found in registry — message dropped", companionName);
                }
            }
        }

        // Checkpoint the latest offset after the batch. Done once at end so
        // we don't pay a SQLite write per update — but we DO mark each
        // update individually above so a crash mid-batch is recoverable
        // via the dedup ledger.
        if (store != null && results.size() > 0) {
            store.writeOffset(name(), chatId, String.valueOf(lastUpdateId.get()));
        }
    }
}
