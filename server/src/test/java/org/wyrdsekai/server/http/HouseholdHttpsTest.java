package org.wyrdsekai.server.http;

import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.HouseholdTls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2 / D2: HTTPS on the household port answers with this machine's leaf; a client
 * pinning the household CA (by certificate or by its fingerprint from an invite) is accepted, a client
 * pinning another household's CA is refused, and so is the JVM's ordinary trust.
 */
class HouseholdHttpsTest {

    private Javalin app;

    @AfterEach
    void stop() {
        if (app != null) app.stop();
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private int start(Path data) throws IOException {
        int tlsPort = freePort();
        var config = ConfigFactory.parseMap(Map.of("wyrdsekai.tls.enabled", true, "wyrdsekai.tls.port", tlsPort));
        app = Javalin.create(cfg -> {
            TlsConfig.configure(cfg, config, data);
            cfg.routes.get("/scheme", ctx -> ctx.result(ctx.scheme()));
        });
        app.start("127.0.0.1", 0);
        return tlsPort;
    }

    private static HttpResponse<String> get(SSLContext ctx, String url) throws Exception {
        var client = HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void answersWithTheHouseholdLeafAndPinnedClientsAcceptIt(@TempDir Path data) throws Exception {
        int port = start(data);
        var ca = HouseholdTls.readCa(data);
        var fp = HouseholdTls.fingerprint(ca);

        var byCa = get(HouseholdTls.clientContext(ca), "https://127.0.0.1:" + port + "/scheme");
        assertThat(byCa.statusCode()).isEqualTo(200);
        // Routes that insist on an encrypted channel (e.g. /api/seed/*) see https.
        assertThat(byCa.body()).isEqualTo("https");

        var byFingerprint = get(HouseholdTls.pinnedContext(fp), "https://localhost:" + port + "/scheme");
        assertThat(byFingerprint.statusCode()).isEqualTo(200);

        // An address the certificate does not name: the pin is the identity, the server does not
        // refuse the Host either (a phone may reach the home by any of its addresses).
        var otherAddress = get(HouseholdTls.pinnedContext(fp), "https://127.0.0.2:" + port + "/scheme");
        assertThat(otherAddress.statusCode()).isEqualTo(200);
    }

    @Test
    void aClientPinningAnotherHouseholdIsRefused(@TempDir Path data, @TempDir Path elsewhere) throws Exception {
        int port = start(data);
        var otherCa = HouseholdTls.ensure(elsewhere).ca();

        assertThatThrownBy(() -> get(HouseholdTls.clientContext(otherCa), "https://127.0.0.1:" + port + "/scheme"))
            .isInstanceOf(SSLHandshakeException.class);
        assertThatThrownBy(() -> get(HouseholdTls.pinnedContext(HouseholdTls.fingerprint(otherCa)),
            "https://127.0.0.1:" + port + "/scheme"))
            .isInstanceOf(SSLHandshakeException.class);
        // Ordinary trust knows nothing of a household CA.
        assertThatThrownBy(() -> get(SSLContext.getDefault(), "https://127.0.0.1:" + port + "/scheme"))
            .isInstanceOf(SSLHandshakeException.class);
    }

    @Test
    void thePlainPortStaysSeparate(@TempDir Path data) throws Exception {
        int tlsPort = freePort();
        int plainPort = freePort();
        var config = ConfigFactory.parseMap(Map.of("wyrdsekai.tls.port", tlsPort));
        app = Javalin.create(cfg -> {
            TlsConfig.configure(cfg, config, data);
            cfg.routes.get("/scheme", ctx -> ctx.result(ctx.scheme()));
        });
        app.start("127.0.0.1", plainPort);
        // Javalin drops its own plain connector once another is added; the plain port must survive.
        assertThat(app.port()).isEqualTo(plainPort);
        var plain = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + plainPort + "/scheme")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(plain.body()).isEqualTo("http");
        var tls = get(HouseholdTls.clientContext(HouseholdTls.readCa(data)), "https://127.0.0.1:" + tlsPort + "/scheme");
        assertThat(tls.body()).isEqualTo("https");
    }

    @Test
    void websocketsWorkOverTls(@TempDir Path data) throws Exception {
        int tlsPort = freePort();
        var config = ConfigFactory.parseMap(Map.of("wyrdsekai.tls.port", tlsPort));
        app = Javalin.create(cfg -> {
            TlsConfig.configure(cfg, config, data);
            cfg.routes.ws("/ws", ws -> ws.onMessage(ctx -> ctx.send("echo:" + ctx.message())));
        });
        app.start("127.0.0.1", freePort());
        var got = new CompletableFuture<String>();
        var client = HttpClient.newBuilder().sslContext(HouseholdTls.clientContext(HouseholdTls.readCa(data))).build();
        var ws = client.newWebSocketBuilder().buildAsync(URI.create("wss://127.0.0.1:" + tlsPort + "/ws"),
            new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket w, CharSequence text, boolean last) {
                    got.complete(text.toString());
                    return null;
                }
            }).get(5, TimeUnit.SECONDS);
        ws.sendText("hello", true);
        assertThat(got.get(5, TimeUnit.SECONDS)).isEqualTo("echo:hello");
        ws.abort();
    }

    @Test
    void aTakenPortDoesNotStopTheServer(@TempDir Path data) throws Exception {
        int plainPort = freePort();
        try (var squatter = new ServerSocket(0)) {
            var config = ConfigFactory.parseMap(Map.of("wyrdsekai.tls.port", squatter.getLocalPort()));
            app = Javalin.create(cfg -> {
                TlsConfig.configure(cfg, config, data);
                cfg.routes.get("/scheme", ctx -> ctx.result(ctx.scheme()));
            });
            app.start("127.0.0.1", plainPort);
            var plain = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + plainPort + "/scheme")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(plain.statusCode()).isEqualTo(200);
        }
    }
}
