package org.wyrdsekai.cli;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.crypto.HouseholdTls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `wyrd connect <host> --ca-fp <fp>` ( W2): the terminal client reaches another
 * machine over HTTPS pinned to that home's CA, and refuses a server that is not that home.
 */
class AuthClientHouseholdTlsTest {

    private HttpsServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private int serve(Path data) throws Exception {
        var m = HouseholdTls.ensure(data);
        var ks = KeyStore.getInstance(m.keystore().toFile(), m.keystorePassword());
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, m.keystorePassword());
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/api/auth/login", ex -> {
            var body = "{\"token\":\"t-1\",\"userId\":\"u-1\",\"username\":\"alice\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void logsInOverHttpsPinnedToTheHouseholdCa(@TempDir Path data) throws Exception {
        int port = serve(data);
        var fp = HouseholdTls.fingerprint(HouseholdTls.readCa(data));
        var r = new AuthClient("127.0.0.1", port, HouseholdTls.pinnedContext(fp)).login("alice", "pw");
        assertThat(r.success()).isTrue();
        assertThat(r.token()).isEqualTo("t-1");
    }

    @Test
    void refusesAServerThatIsNotThatHome(@TempDir Path data, @TempDir Path other) throws Exception {
        int port = serve(data);
        var wrong = HouseholdTls.fingerprint(HouseholdTls.ensure(other).ca());
        var r = new AuthClient("127.0.0.1", port, HouseholdTls.pinnedContext(wrong)).login("alice", "pw");
        assertThat(r.success()).isFalse();
        assertThat(r.error()).startsWith("Connection failed");
    }
}
