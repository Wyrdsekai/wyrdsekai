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
 * Bidirectional Signal conversation channel via the {@code signal-cli} CLI.
 *
 * <p>Signal's official protocol is closed; signal-cli is the de-facto
 * unofficial bridge that other open-source projects (including Hermes
 * Agent and OpenClaw) lean on for Signal access. Setup:
 * <ol>
 *   <li>Install {@code signal-cli} (pkg / homebrew / .deb on linux).</li>
 *   <li>Register your phone number: {@code signal-cli -u +1XXX register}
 *       and verify the SMS code.</li>
 *   <li>Configure this channel with the registered number + the recipient
 *       number (or group ID) you want the companion to talk to.</li>
 * </ol>
 *
 * <p>Outbound: {@code signal-cli -u <us> send -m <text> <recipient>}
 * subprocess. Encrypted by Signal end-to-end automatically.</p>
 *
 * <p>Inbound: {@code signal-cli --output=json daemon} subprocess streams
 * JSON-RPC envelopes to stdout. We parse each line and route the inner
 * {@code dataMessage.message} to the companion via {@link AgentEventStream}.
 * dedup keyed on
 * {@code <sourceUuid>:<envelope.timestamp>} since signal-cli replays the
 * recent local backlog when the daemon restarts.</p>
 *
 * <p>v1 supports 1:1 chats. Group messages (envelope has
 * {@code groupInfo.groupId}) are deferred — straightforward extension
 * once we have a real group to test against.</p>
 */
public class SignalChannel implements ConversationChannel {

    private static final Logger log = LoggerFactory.getLogger(SignalChannel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SEND_TIMEOUT_SECS = 20;

    /** Our registered Signal phone number ({@code +1XXX...}). */
    private final String accountNumber;
    /** The recipient — phone number or {@code group.<id>}. */
    private final String recipient;

    private final AtomicBoolean listening = new AtomicBoolean(false);
    private volatile Thread listenerThread;
    private volatile Process listenerProcess;
    private volatile String companionName;

    public SignalChannel(String accountNumber, String recipient) {
        this.accountNumber = accountNumber;
        this.recipient = recipient;
    }

    @Override
    public String name() { return "signal"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority,
                                            String fromAgent, String deepLink) {
        var text = "[" + fromAgent + "] " + message;
        if (deepLink != null && !deepLink.isBlank()) {
            text += "\n→ " + deepLink;
        }
        final var messageText = text;

        return CompletableFuture.supplyAsync(() -> {
            try {
                var pb = new ProcessBuilder(
                    "signal-cli", "-u", accountNumber, "send",
                    "-m", messageText, recipient);
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var exited = proc.waitFor(SEND_TIMEOUT_SECS, TimeUnit.SECONDS);
                if (!exited) {
                    proc.destroyForcibly();
                    log.warn("Signal send timed out after {}s", SEND_TIMEOUT_SECS);
                    return false;
                }
                if (proc.exitValue() == 0) {
                    log.debug("Signal message sent to {}", recipient);
                    return true;
                }
                var stderr = new String(
                    proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("Signal send exited {}: {}",
                    proc.exitValue(), stderr.trim());
                return false;
            } catch (Exception e) {
                log.warn("Signal send failed: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public void startListener(String companionName) {
        this.companionName = companionName;
        if (listening.compareAndSet(false, true)) {
            listenerThread = Thread.ofVirtual()
                .name("signal-listener").start(this::listenLoop);
            log.info("Signal listener started for account {}", accountNumber);
        }
    }

    @Override
    public void stopListener() {
        listening.set(false);
        if (listenerProcess != null) listenerProcess.destroyForcibly();
        if (listenerThread != null) listenerThread.interrupt();
        log.info("Signal listener stopped");
    }

    @Override
    public boolean isListening() { return listening.get(); }

    /**
     * Runs {@code signal-cli --output=json daemon} and reads JSON-RPC
     * lines from stdout. Each receive notification is one JSON object
     * with {@code method:"receive"} and an inner envelope. If the
     * subprocess exits unexpectedly, restart after a brief pause —
     * signal-cli's local store will replay the recent backlog, which the
     * dedup ledger absorbs.
     */
    private void listenLoop() {
        while (listening.get()) {
            Process proc = null;
            try {
                var pb = new ProcessBuilder(
                    "signal-cli", "-u", accountNumber, "--output=json", "daemon");
                pb.redirectErrorStream(false);
                proc = pb.start();
                listenerProcess = proc;

                try (var reader = new BufferedReader(new InputStreamReader(
                        proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (listening.get() && (line = reader.readLine()) != null) {
                        processLine(line);
                    }
                }

                if (listening.get()) {
                    log.debug("signal-cli daemon exited, restarting in 5s");
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.debug("Signal listener error: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            } finally {
                if (proc != null) proc.destroyForcibly();
            }
        }
    }

    /**
     * Parse a single JSON-RPC line from {@code signal-cli daemon}.
     *
     * <p>Expected envelope (simplified):
     * <pre>{@code
     * { "jsonrpc": "2.0",
     *   "method":  "receive",
     *   "params": {
     *     "envelope": {
     *       "source":     "+1234567890",
     *       "sourceUuid": "...",
     *       "sourceName": "Alice",
     *       "timestamp":  1745526789123,
     *       "dataMessage": {
     *         "timestamp": 1745526789123,
     *         "message":   "hello"
     *       }
     *     }
     *   }
     * }
     * }</pre>
     */
    void processLine(String line) {
        try {
            var json = MAPPER.readTree(line);
            if (!"receive".equals(json.path("method").asText())) return;

            var env = json.path("params").path("envelope");
            if (env.isMissingNode()) return;

            var data = env.path("dataMessage");
            if (data.isMissingNode()) return;

            var text = data.path("message").asText("");
            if (text.isBlank()) return;

            var sourceUuid = env.path("sourceUuid").asText(env.path("source").asText(""));
            if (sourceUuid.isEmpty()) return;

            // v1: only 1:1 chats. Group messages have envelope.groupInfo.groupId
            // and need different scoping. Skip for now — the agent never sees
            // them, so dedup state stays clean.
            if (!data.path("groupInfo").isMissingNode()) {
                log.debug("Signal group message from {} ignored (v1 = 1:1 only)",
                    sourceUuid);
                return;
            }

            var ts = env.path("timestamp").asLong(0L);
            var externalId = ts > 0 ? sourceUuid + ":" + ts : null;

            // dedup before publish. signal-cli
            // replays recent envelopes when the daemon restarts.
            var store = ChannelStateStore.get();
            if (externalId != null && store != null
                    && store.isProcessed(name(), externalId)) {
                log.debug("Signal envelope {}:{} already processed — skipping",
                    sourceUuid, ts);
                return;
            }

            var fromName = env.path("sourceName").asText(sourceUuid);

            var stream = AgentEventStream.get();
            var registry = EntityRegistry.get();
            if (stream != null && registry != null && companionName != null) {
                var companionId = registry.findByName(companionName);
                if (companionId.isPresent()) {
                    if (externalId != null && store != null) {
                        store.markProcessed(name(), externalId);
                    }
                    stream.publishAgentMessage(
                        "signal-" + sourceUuid, fromName,
                        companionId.get(),
                        "[from " + fromName + " via Signal] " + text);
                    log.debug("Signal message from {} routed to companion '{}'",
                        fromName, companionName);
                } else {
                    log.debug("Companion '{}' not found in registry — message dropped",
                        companionName);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse signal-cli line: {}", e.getMessage());
        }
    }
}
