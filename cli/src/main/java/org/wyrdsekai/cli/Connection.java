package org.wyrdsekai.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * WebSocket client connection with reconnect, seq tracking, and auth token support.
 * State machine: Disconnected → Connecting → Connected → Reconnecting.
 */
public class Connection implements WebSocket.Listener, WyrdSession {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    private static final int MAX_BACKOFF_SECONDS = 30;

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private final String host;
    private final int port;
    private final Consumer<S2CMessage> messageHandler;
    private final Consumer<State> stateHandler;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<State> state = new AtomicReference<>(State.DISCONNECTED);
    private final AtomicLong lastSeenSeq = new AtomicLong(0);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final StringBuilder messageBuffer = new StringBuilder();

    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private final CountDownLatch closeLatch = new CountDownLatch(1);

    private volatile WebSocket webSocket;
    private volatile boolean shutdownRequested = false;
    private volatile String token;

    public Connection(String host, int port,
                      Consumer<S2CMessage> messageHandler,
                      Consumer<State> stateHandler) {
        this.host = host;
        this.port = port;
        this.messageHandler = messageHandler;
        this.stateHandler = stateHandler;
        this.httpClient = HttpClient.newHttpClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    public void connect() {
        setState(State.CONNECTING);
        doConnect();
    }

    /** Block until connected or timeout. Returns true if connected. */
    public boolean awaitConnected(long timeoutMs) {
        try {
            return connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void disconnect() {
        shutdownRequested = true;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        scheduler.shutdown();
        setState(State.DISCONNECTED);
    }

    /**
     * Mark this connection for an imminent server-driven close ({@code quit} /
     * {@code logout}): suppress the auto-reconnect that {@link #onClose} would
     * otherwise schedule, WITHOUT yet tearing down the socket — so the server's
     * farewell prose still arrives and renders before we exit.
     */
    public void prepareClose() {
        shutdownRequested = true;
    }

    /**
     * Block until the server closes this channel (or timeout). Used after sending
     * {@code quit}/{@code logout} so the server-computed detach hint renders before
     * the CLI exits. Returns true if the close arrived within the window.
     */
    public boolean awaitClosed(long timeoutMs) {
        try {
            return closeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void send(C2SMessage msg) {
        if (state.get() != State.CONNECTED || webSocket == null) {
            log.warn("Cannot send: not connected");
            return;
        }
        try {
            var json = Json.mapper().writeValueAsString(msg);
            webSocket.sendText(json, true);
        } catch (Exception e) {
            log.error("Failed to send message", e);
        }
    }

    public String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public State getState() {
        return state.get();
    }

    /** Set auth token for WebSocket connection. Null clears token (anonymous). */
    public void setToken(String token) {
        this.token = token;
    }

    /** Disconnect and reconnect with current token. Used after /login or /logout. */
    public void reconnectWithToken() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
            } catch (Exception ignored) {}
        }
        // Reset seq since we're starting a fresh session
        lastSeenSeq.set(0);
        reconnectAttempts.set(0);
        scheduler.schedule(() -> {
            if (!shutdownRequested) {
                setState(State.CONNECTING);
                doConnect();
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    // --- WebSocket.Listener ---

    @Override
    public void onOpen(WebSocket webSocket) {
        log.info("Connected to {}:{}", host, port);
        this.webSocket = webSocket;
        setState(State.CONNECTED);
        reconnectAttempts.set(0);
        connectedLatch.countDown();

        // If reconnecting, request replay
        long lastSeq = lastSeenSeq.get();
        if (lastSeq > 0) {
            send(new C2SMessage.Reconnect(newId(), "nexus", lastSeq));
        }

        webSocket.request(1);
    }

    @Override
    public CompletionStage<Void> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        if (last) {
            var fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0);
            handleMessage(fullMessage);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.sendPong(message);
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.info("Disconnected: {} {}", statusCode, reason);
        closeLatch.countDown();
        if (!shutdownRequested) {
            scheduleReconnect();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.error("WebSocket error", error);
        if (!shutdownRequested) {
            scheduleReconnect();
        }
    }

    // --- Internal ---

    private URI buildUri() {
        var uri = "ws://" + host + ":" + port + "/ws";
        if (token != null && !token.isBlank()) {
            uri += "?token=" + token;
        }
        return URI.create(uri);
    }

    private void doConnect() {
        httpClient.newWebSocketBuilder()
            .buildAsync(buildUri(), this)
            .whenComplete((ws, err) -> {
                if (err != null) {
                    log.warn("Connection failed: {}", err.getMessage());
                    if (!shutdownRequested) {
                        scheduleReconnect();
                    }
                }
            });
    }

    private void scheduleReconnect() {
        if (shutdownRequested) return;
        setState(State.RECONNECTING);
        int attempt = reconnectAttempts.getAndIncrement();
        int delaySec = Math.min(1 << attempt, MAX_BACKOFF_SECONDS);
        log.info("Reconnecting in {}s (attempt {})", delaySec, attempt + 1);
        scheduler.schedule(() -> {
            if (!shutdownRequested) {
                setState(State.CONNECTING);
                doConnect();
            }
        }, delaySec, TimeUnit.SECONDS);
    }

    private void handleMessage(String json) {
        try {
            var msg = Json.mapper().readValue(json, S2CMessage.class);
            long seq = msg.seq();
            if (seq > lastSeenSeq.get()) {
                lastSeenSeq.set(seq);
            }
            messageHandler.accept(msg);
        } catch (Exception e) {
            log.error("Failed to parse S2C message: {}", json, e);
        }
    }

    private void setState(State newState) {
        var old = state.getAndSet(newState);
        if (old != newState) {
            stateHandler.accept(newState);
        }
    }
}
