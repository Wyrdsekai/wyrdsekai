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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional WhatsApp conversation channel via a {@code whatsmeow}-based
 * sidecar process.
 *
 * <p><b>Why a sidecar</b>: WhatsApp's Multi-Device protocol is closed and
 * reverse-engineered. The maintained reference implementation is
 * {@code whatsmeow} (Go). OpenClaw and Hermes Agent both follow this
 * pattern — Go binary handles the WhatsApp protocol + SQLite session
 * store, exposes a small HTTP API to the host language. We do the same
 * to avoid re-implementing E2EE / pairing / QR-code-linking in Java.</p>
 *
 * <p><b>Sidecar contract</b> (the Go binary must implement this):</p>
 * <pre>{@code
 *   GET  /health
 *        → 200 OK with body "ok" (used by health probes).
 *
 *   POST /send
 *        body: {"recipient": "<phone-or-jid>", "body": "<text>"}
 *        → 200 OK on enqueue, 4xx/5xx on error.
 *
 *   GET  /events?since=<offset>&timeout=30000
 *        → 200 OK with body:
 *          {
 *            "events": [
 *              {
 *                "id":     "<message-id>",     // whatsmeow stanza ID
 *                "from":   "<sender-jid>",     // e.g. "1234567890@s.whatsapp.net"
 *                "name":   "<display-name>",   // optional, may be null
 *                "body":   "<text>",
 *                "ts":     <unix-millis>
 *              }, ...
 *            ],
 *            "next_offset": "<opaque-cursor>"
 *          }
 *        Long-polls up to {@code timeout} ms for new events. Returns
 *        empty {@code events} array on timeout. {@code next_offset} is
 *        always returned and must be passed back as {@code since} on the
 *        next call.
 * }</pre>
 *
 * <p>The sidecar binary itself is a separate deliverable
 * (item #6 phase 2): a small Go program that wraps whatsmeow, persists
 * the WhatsApp session under {@code ~/.wyrdsekai/whatsapp/}, and binds
 * to {@code localhost:<port>}. The first run prints a QR code to its
 * stdout for the user to scan with WhatsApp mobile to pair.</p>
 *
 * <p>Setup once the sidecar exists:
 * <ol>
 *   <li>Start the sidecar (it prints a QR code).</li>
 *   <li>Open WhatsApp on phone → Settings → Linked Devices → scan QR.</li>
 *   <li>Configure this channel with the sidecar URL + recipient JID.</li>
 * </ol>
 *
 * <p>This Java class is the client side and is committable today —
 * unit-tested via fake sidecar JSON responses. End-to-end live testing
 * waits for the Go sidecar.</p>
 */
public class WhatsAppChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private static final int LONG_POLL_MS = 30_000;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(35);

    private final String sidecarUrl;
    private final String recipient;

    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile Thread listenerThread;
    private volatile String companionName;
    /** Sidecar's opaque event cursor. {@code null} = first poll (sidecar default). */
    private volatile String nextOffset;

    public WhatsAppChannel(String sidecarUrl, String recipient) {
        // Normalize trailing slash so URL construction is consistent.
        this.sidecarUrl = sidecarUrl.endsWith("/")
            ? sidecarUrl.substring(0, sidecarUrl.length() - 1)
            : sidecarUrl;
        this.recipient = recipient;
    }

    @Override
    public String name() { return "whatsapp"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority,
                                            String fromAgent, String deepLink) {
        var body = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            body += "\n→ " + deepLink;
        }
        var payload = MAPPER.createObjectNode()
            .put("recipient", recipient)
            .put("body", body);

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(sidecarUrl + "/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 200) {
                        log.debug("WhatsApp message sent to {}", recipient);
                        return true;
                    }
                    log.warn("WhatsApp sidecar /send returned HTTP {}: {}",
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
            // resume from sidecar offset cursor.
            var store = ChannelStateStore.get();
            if (store != null) {
                store.readOffset(name(), recipient).ifPresent(o -> {
                    nextOffset = o;
                    log.info("WhatsApp listener resuming for {} from offset {}",
                        recipient, o);
                });
            }
            listenerThread = Thread.ofVirtual()
                .name("whatsapp-listener").start(this::pollLoop);
            log.info("WhatsApp listener started against sidecar {}", sidecarUrl);
        }
    }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerThread != null) listenerThread.interrupt();
        log.info("WhatsApp listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    /** Test seam: read the in-memory cursor without starting the poll thread. */
    String peekNextOffset() { return nextOffset; }

    private void pollLoop() {
        while (listening.get()) {
            try {
                var url = sidecarUrl + "/events?timeout=" + LONG_POLL_MS
                    + (nextOffset != null
                        ? "&since=" + URLEncoder.encode(nextOffset, StandardCharsets.UTF_8)
                        : "");
                var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(HTTP_TIMEOUT)
                    .build();

                var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = MAPPER.readTree(resp.body());
                    processEvents(json);
                } else {
                    log.warn("WhatsApp sidecar /events returned HTTP {}", resp.statusCode());
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("WhatsApp poll error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    /**
     * Parse a sidecar {@code /events} response. See class javadoc for the
     * expected shape. Only events whose {@code from} matches the configured
     * recipient are routed — group chats and other-recipient DMs are
     * silently skipped (the sidecar may broadcast all of them).
     */
    void processEvents(JsonNode response) {
        var newOffset = response.path("next_offset").asText("");
        if (!newOffset.isEmpty()) nextOffset = newOffset;

        var events = response.path("events");
        if (!events.isArray()) {
            persistOffset();
            return;
        }

        var store = ChannelStateStore.get();
        for (var event : events) {
            var from = event.path("from").asText("");
            // Only handle messages from our configured recipient. Sidecar
            // may forward all conversations; we filter by JID.
            if (!recipient.equals(from)) continue;

            var body = event.path("body").asText("");
            if (body.isBlank()) continue;

            var eventId = event.path("id").asText("");
            if (eventId.isEmpty()) continue;

            // dedup before publish. Sidecar may
            // re-emit on its own restart.
            if (store != null && store.isProcessed(name(), eventId)) {
                log.debug("WhatsApp event {} already processed — skipping", eventId);
                continue;
            }

            var fromName = event.path("name").asText(from);

            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    if (store != null) store.markProcessed(name(), eventId);
                    stream.publishAgentMessage(
                        "whatsapp-" + from, fromName,
                        companionId.get(),
                        "[from " + fromName + " via WhatsApp] " + body);
                    log.debug("WhatsApp message from {} routed to companion '{}'",
                        fromName, companionName);
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
        if (store != null && nextOffset != null) {
            store.writeOffset(name(), recipient, nextOffset);
        }
    }
}
