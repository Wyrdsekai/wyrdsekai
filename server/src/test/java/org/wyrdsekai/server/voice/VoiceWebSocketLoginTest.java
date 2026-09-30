package org.wyrdsekai.server.voice;

import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** The voice socket asks for a login (2026-09-28); it used to take anyone. */
class VoiceWebSocketLoginTest {

    private Javalin app;

    @AfterEach
    void tearDown() {
        VoiceWebSocket.setAuthenticator(null);
        if (app != null) app.stop();
    }

    private int closeCodeFor(String query) throws Exception {
        VoiceWebSocket.setAuthenticator(t -> "good".equals(t) ? "user-1" : null);
        var ws = new VoiceWebSocket(new VoiceAdapter(SttConfig.DEFAULT));
        app = Javalin.create(cfg -> cfg.routes.ws("/voice", ws)).start("127.0.0.1", 0);
        var closed = new CompletableFuture<Integer>();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + app.port() + "/voice" + query), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                    closed.complete(code);
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);
        try {
            return closed.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException stillOpen) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            return -1;
        }
    }

    @Test
    void withoutALoginTheSocketIsClosed() throws Exception {
        assertThat(closeCodeFor("?session=s1")).isEqualTo(4001);
    }

    @Test
    void aWrongTokenIsClosedToo() throws Exception {
        assertThat(closeCodeFor("?session=s1&token=bad")).isEqualTo(4001);
    }

    @Test
    void aLoginKeepsItOpen() throws Exception {
        assertThat(closeCodeFor("?session=s1&token=good")).isEqualTo(-1);
    }
}
