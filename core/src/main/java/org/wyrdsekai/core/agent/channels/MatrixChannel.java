package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.ConversationChannel;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional Matrix conversation channel via the Client-Server API.
 *
 * <p>Matrix is the federation-aligned channel — open protocol, runs over
 * any homeserver (matrix.org public, self-hosted Synapse, etc.). v1 of
 * this channel speaks unencrypted rooms only; E2EE (Olm/Megolm) is a
 * separate large undertaking and would need a real Matrix client lib
 * rather than direct HTTP. Bot accounts in unencrypted rooms is a useful
 * baseline: many community bots run this way.</p>
 *
 * <p>Setup:
 * <ol>
 *   <li>Register a bot account on your homeserver. Get an access_token
 *       (e.g. via Element settings or {@code /login} HTTP).</li>
 *   <li>Invite the bot to an unencrypted room. Note the {@code !roomId:server}.</li>
 *   <li>Configure this channel with {@code homeserverUrl}, {@code accessToken},
 *       and {@code roomId}.</li>
 * </ol>
 *
 * <p>Outbound: PUT {@code /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txn_id}}.</p>
 *
 * <p>Inbound: GET {@code /_matrix/client/v3/sync?since={next_batch}&timeout=30000}
 * long-polls. Each response advances {@code next_batch} which we
 * checkpoint via {@link ChannelStateStore} so a restart resumes from the
 * last ack rather than picking up the entire room history.</p>
 *
 * <p>Dedup is keyed on Matrix's globally-unique {@code event_id}
 * ({@code $abc123:server}). Belt-and-suspenders against {@code /sync}
 * occasionally redelivering on connection re-establishment.</p>
 */
public class MatrixChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(MatrixChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    /** {@code /sync} long-poll timeout. Matrix recommends 30s. */
    private static final int SYNC_TIMEOUT_MS = 30_000;
    /** Per-poll HTTP timeout — sync timeout + small buffer. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(35);

    private final String homeserverUrl;
    private final String accessToken;
    private final String roomId;

    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile Thread listenerThread;
    private volatile String companionName;
    /** Matrix opaque sync token. {@code null} = initial sync (full state). */
    private volatile String nextBatch;
    /** Bot's own user_id, resolved on first sync to filter out self-echo. */
    private volatile String selfUserId;

    public MatrixChannel(String homeserverUrl, String accessToken, String roomId) {
        // Normalize: strip trailing slash so URL building is consistent.
        this.homeserverUrl = homeserverUrl.endsWith("/")
            ? homeserverUrl.substring(0, homeserverUrl.length() - 1)
            : homeserverUrl;
        this.accessToken = accessToken;
        this.roomId = roomId;
    }

    @Override
    public String name() { return "matrix"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority,
                                            String fromAgent, String deepLink) {
        var body = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            body += "\n→ " + deepLink;
        }

        var payload = MAPPER.createObjectNode()
            .put("msgtype", "m.text")
            .put("body", body);

        // Matrix needs a unique txn_id per send for client-side idempotency.
        // UUID is overkill but trivially correct.
        var txnId = "wyrd-" + UUID.randomUUID().toString().replace("-", "");
        var url = homeserverUrl
            + "/_matrix/client/v3/rooms/" + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
            + "/send/m.room.message/" + txnId;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        log.debug("Matrix message sent to room {}", roomId);
                        return true;
                    }
                    log.warn("Matrix send returned HTTP {}: {}",
                        resp.statusCode(), resp.body());
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
            // resume from the persisted next_batch
            // token so /sync picks up only new events. Without this, the
            // initial sync replays the entire room state to the agent.
            var store = ChannelStateStore.get();
            if (store != null) {
                store.readOffset(name(), roomId).ifPresent(token -> {
                    nextBatch = token;
                    log.info("Matrix listener resuming room {} from sync token {}",
                        roomId, token);
                });
            }
            // Resolve our own user_id so we can ignore self-echo.
            resolveSelfUserId();

            listenerThread = Thread.ofVirtual()
                .name("matrix-listener").start(this::pollLoop);
            log.info("Matrix listener started for room {}", roomId);
        }
    }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerThread != null) listenerThread.interrupt();
        log.info("Matrix listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    /** Test seam: read the in-memory sync token without starting the poll thread. */
    String peekNextBatch() { return nextBatch; }

    private void pollLoop() {
        while (listening.get()) {
            try {
                var url = homeserverUrl
                    + "/_matrix/client/v3/sync?timeout=" + SYNC_TIMEOUT_MS
                    + (nextBatch != null
                        ? "&since=" + URLEncoder.encode(nextBatch, StandardCharsets.UTF_8)
                        : "");
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(HTTP_TIMEOUT)
                    .build();

                var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = MAPPER.readTree(resp.body());
                    processSync(json);
                } else {
                    log.warn("Matrix /sync returned HTTP {}", resp.statusCode());
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("Matrix poll error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    /**
     * Parse a Matrix {@code /sync} response. Walks
     * {@code rooms.join.<roomId>.timeline.events} for our configured room
     * and routes each text message to the agent. Updates {@code nextBatch}
     * and persists it after the batch.
     */
    void processSync(JsonNode response) {
        // Always advance the sync token first — even if no events to process,
        // we want the next /sync to start from this point.
        var newBatch = response.path("next_batch").asText("");
        if (!newBatch.isEmpty()) nextBatch = newBatch;

        var roomNode = response.path("rooms").path("join").path(roomId);
        if (roomNode.isMissingNode()) {
            // No events for our room this cycle — just checkpoint and move on.
            persistOffset();
            return;
        }
        var events = roomNode.path("timeline").path("events");
        if (!events.isArray()) {
            persistOffset();
            return;
        }

        var store = ChannelStateStore.get();
        for (var event : events) {
            if (!"m.room.message".equals(event.path("type").asText())) continue;

            var content = event.path("content");
            if (!"m.text".equals(content.path("msgtype").asText())) continue;

            var sender = event.path("sender").asText("");
            if (sender.isEmpty()) continue;
            // Skip our own messages (Matrix returns them in /sync too).
            if (selfUserId != null && selfUserId.equals(sender)) continue;

            var body = content.path("body").asText("");
            if (body.isBlank()) continue;

            var eventId = event.path("event_id").asText("");
            if (eventId.isEmpty()) continue;

            // dedup before publish.
            if (store != null && store.isProcessed(name(), eventId)) {
                log.debug("Matrix event {} already processed — skipping", eventId);
                continue;
            }

            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    if (store != null) store.markProcessed(name(), eventId);
                    stream.publishAgentMessage(
                        "matrix-" + roomId, sender,
                        companionId.get(),
                        "[from " + sender + " via Matrix] " + body);
                    log.debug("Matrix message from {} routed to companion '{}'",
                        sender, companionName);
                } else {
                    log.debug("Companion '{}' not found in registry — message dropped",
                        companionName);
                }
            }
        }

        persistOffset();
    }

    private void persistOffset() {
        var store = ChannelStateStore.get();
        if (store != null && nextBatch != null) {
            store.writeOffset(name(), roomId, nextBatch);
        }
    }

    /**
     * Resolve our own {@code user_id} via {@code /_matrix/client/v3/account/whoami}
     * so we can filter out self-echoed events from {@code /sync}. Best-effort —
     * if it fails we just log and self-echo through (visible but harmless).
     */
    private void resolveSelfUserId() {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(homeserverUrl + "/_matrix/client/v3/account/whoami"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
            var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var json = MAPPER.readTree(resp.body());
                selfUserId = json.path("user_id").asText(null);
                log.debug("Matrix self user_id resolved: {}", selfUserId);
            }
        } catch (Exception e) {
            log.debug("Could not resolve Matrix self user_id: {}", e.getMessage());
        }
    }
}
