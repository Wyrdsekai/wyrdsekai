package org.wyrdsekai.core.agent.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.ConversationChannel;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bidirectional Keybase conversation channel via the {@code keybase} CLI.
 *
 * <p>Outbound: runs {@code keybase chat send <username> <message>} as a subprocess.
 * <p>Inbound: runs {@code keybase chat api-listen} as a long-lived subprocess,
 * parses JSON lines from stdout, and routes incoming messages to the companion
 * via {@link AgentEventStream}.
 *
 * <p>Requires the {@code keybase} CLI to be installed and logged in on the server.
 * All communication is end-to-end encrypted by Keybase.</p>
 */
public class KeybaseChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(KeybaseChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SEND_TIMEOUT_SECS = 15;

    private final String username;
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile Thread listenerThread;
    private volatile Process listenerProcess;
    private volatile String companionName;

    public KeybaseChannel(String username) {
        this.username = username;
    }

    @Override
    public String name() { return "keybase"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        var text = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            text += "\n> " + deepLink;
        }
        final var messageText = text;

        return CompletableFuture.supplyAsync(() -> {
            try {
                var pb = new ProcessBuilder("keybase", "chat", "send", username, messageText);
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var exited = proc.waitFor(SEND_TIMEOUT_SECS, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    log.warn("Keybase send timed out after {}s", SEND_TIMEOUT_SECS);
                    return false;
                }
                if (proc.exitValue() == 0) {
                    log.debug("Keybase message sent to {}", username);
                    return true;
                }
                var stderr = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("Keybase send exited {}: {}", proc.exitValue(), stderr.trim());
                return false;
            } catch (Exception e) {
                log.warn("Keybase send failed: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public void startListener(String companionName) {
        this.companionName = companionName;
        if (listening.compareAndSet(false, true)) {
            listenerThread = Thread.ofVirtual().name("keybase-listener").start(this::listenLoop);
            log.info("Keybase listener started for user {}", username);
        }
    }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerProcess != null) {
            listenerProcess.destroyForcibly();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        log.info("Keybase listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    /**
     * Runs {@code keybase chat api-listen} and reads JSON lines from stdout.
     * Each line is a JSON object describing an incoming message. If the process
     * exits unexpectedly, it restarts after a brief pause.
     */
    private void listenLoop() {
        while (listening.get()) {
            Process proc = null;
            try {
                var pb = new ProcessBuilder("keybase", "chat", "api-listen");
                pb.redirectErrorStream(false);
                proc = pb.start();
                listenerProcess = proc;

                try (var reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (listening.get() && (line = reader.readLine()) != null) {
                        processLine(line);
                    }
                }

                // Process ended — if still listening, restart after a delay
                if (listening.get()) {
                    log.debug("Keybase api-listen exited, restarting in 5s");
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("Keybase listener error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            } finally {
                if (proc != null) {
                    proc.destroyForcibly();
                }
            }
        }
    }

    /**
     * Parse a single JSON line from {@code keybase chat api-listen}.
     *
     * <p>Expected structure (simplified):
     * <pre>{@code
     * {
     *   "type": "chat",
     *   "source": "remote",
     *   "msg": {
     *     "sender": { "username": "alice" },
     *     "content": { "type": "text", "text": { "body": "Hello!" } },
     *     "channel": { "name": "alice,wyrdbot" }
     *   }
     * }
     * }</pre>
     */
    private void processLine(String line) {
        try {
            var json = MAPPER.readTree(line);
            if (!"chat".equals(json.path("type").asText())) return;

            var msg = json.path("msg");
            if (msg.isMissingNode()) return;

            var contentType = msg.path("content").path("type").asText("");
            if (!"text".equals(contentType)) return;

            var senderUsername = msg.path("sender").path("username").asText("");
            if (senderUsername.isEmpty()) return;

            // Ignore messages from ourselves (from any device)
            // The Keybase CLI is logged in as our user — don't echo back
            var channelName = msg.path("channel").path("name").asText("");
            // channelName is "alice,wyrdbot" — both participants. We filter by sender.
            // Skip if the sender is likely us. We don't know our own username directly,
            // but we can check: if sender == the target user, that IS the human, route it.
            // If sender != username (i.e., it's someone else in a team chat or it's us), skip.
            // For 1:1 chats, the only other sender is the configured username (the human).
            if (!senderUsername.equals(username)) return;

            var text = msg.path("content").path("text").path("body").asText("");
            if (text.isBlank()) return;

            // dedup before publish. Keybase has no
            // offset cursor (api-listen is a stream, not a poll), but it
            // does replay recent backlog when the subprocess restarts. The
            // dedup ledger absorbs those replays. Key = "<sender>:<msg.id>"
            // so collisions across senders are impossible.
            var msgId = msg.path("id").asText("");
            var externalId = msgId.isEmpty() ? null : senderUsername + ":" + msgId;
            var store = ChannelStateStore.get();
            if (externalId != null && store != null && store.isProcessed(name(), externalId)) {
                log.debug("Keybase message id {} from {} already processed — skipping",
                    msgId, senderUsername);
                return;
            }

            // Route to companion via AgentEventStream
            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    if (externalId != null && store != null) {
                        store.markProcessed(name(), externalId);
                    }
                    stream.publishAgentMessage(
                        "keybase-" + senderUsername, senderUsername,
                        companionId.get(),
                        "[from " + senderUsername + " via Keybase] " + text);
                    log.debug("Keybase message from {} routed to companion '{}'",
                        senderUsername, companionName);
                } else {
                    log.debug("Companion '{}' not found in registry — message dropped", companionName);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse Keybase api-listen line: {}", e.getMessage());
        }
    }
}
