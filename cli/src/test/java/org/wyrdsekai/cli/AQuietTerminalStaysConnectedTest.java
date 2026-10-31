package org.wyrdsekai.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.C2SMessage;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server closes a connection that has been quiet for five minutes. A person reading a quiet
 * room in the terminal sent nothing, so every five minutes they were taken out of the room and
 * brought back. The terminal now sends a ping on a timer while it is connected.
 */
class AQuietTerminalStaysConnectedTest {

    /** A socket that records what is sent and takes a little while to send each text. */
    static final class RecordingSocket implements WebSocket {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            int now = inFlight.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            texts.add(data.toString());
            return CompletableFuture.supplyAsync(() -> {
                inFlight.decrementAndGet();
                return (WebSocket) this;
            }, CompletableFuture.delayedExecutor(5, TimeUnit.MILLISECONDS));
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }

        long pings() { return texts.stream().filter("{\"type\":\"ping\"}"::equals).count(); }
    }

    private Connection connection;

    @AfterEach
    void tearDown() {
        if (connection != null) connection.disconnect();
    }

    @Test
    void a_connected_terminal_pings_on_its_own() throws Exception {
        var socket = new RecordingSocket();
        connection = new Connection("localhost", 7070, null, m -> { }, s -> { });
        connection.keepaliveEvery(40);
        connection.onOpen(socket);

        Thread.sleep(400);

        assertThat(socket.pings()).as("pings while nothing is typed").isGreaterThanOrEqualTo(3);
    }

    @Test
    void the_ping_and_a_line_being_sent_never_overlap() throws Exception {
        var socket = new RecordingSocket();
        connection = new Connection("localhost", 7070, null, m -> { }, s -> { });
        connection.keepaliveEvery(3);
        connection.onOpen(socket);

        for (int i = 0; i < 60; i++) {
            connection.send(new C2SMessage.Say("s" + i, "hearth", "hello " + i));
        }
        Thread.sleep(50);

        assertThat(socket.pings()).isPositive();
        assertThat(socket.texts.stream().filter(t -> t.contains("hello")).count()).isEqualTo(60);
        assertThat(socket.mostAtOnce.get()).as("one send at a time on the socket").isEqualTo(1);
    }

    @Test
    void the_period_is_well_inside_the_servers_five_minutes() {
        assertThat(Connection.KEEPALIVE_MILLIS).isLessThanOrEqualTo(60_000);
    }
}
