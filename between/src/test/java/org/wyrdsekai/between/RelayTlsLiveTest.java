package org.wyrdsekai.between;

import io.nats.client.Options;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RelayTls against real NATS servers: one with TLS and allow_non_tls (a relay mid-transition), one
 * without TLS (a relay not yet updated). Gates: RELAY_TLS_LIVE_TLS_URL, RELAY_TLS_LIVE_TLS_MONITOR,
 * RELAY_TLS_LIVE_FP (the TLS server's certificate fingerprint), RELAY_TLS_LIVE_PLAIN_URL.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "RELAY_TLS_LIVE_TLS_URL", matches = ".+")
class RelayTlsLiveTest {

    @AfterEach
    void reset() {
        RelayTls.forgetForTests();
        System.clearProperty("wyrdsekai.relay.require.tls");
        System.clearProperty("wyrdsekai.relay.allow.plaintext.link");
    }

    private static Options.Builder opts(String url) {
        return new Options.Builder().server(url).connectionName("relay-tls-live").maxReconnects(0);
    }

    private static String connz() throws Exception {
        var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(System.getenv("RELAY_TLS_LIVE_TLS_MONITOR") + "/connz?subs=0")).build(),
            HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    @Test
    void aPinnedRelayLinkIsEncrypted() throws Exception {
        var url = System.getenv("RELAY_TLS_LIVE_TLS_URL");
        try (var conn = RelayTls.connect(opts(url), url, System.getenv("RELAY_TLS_LIVE_FP"))) {
            conn.flush(java.time.Duration.ofSeconds(5));
            assertThat(connz()).contains("\"name\": \"relay-tls-live\"").contains("\"tls_version\": \"1.3\"");
        }
        assertThat(RelayTls.seenBefore(RelayTls.hostPort(url))).isTrue();
    }

    @Test
    void aWrongPinIsRefused() {
        var url = System.getenv("RELAY_TLS_LIVE_TLS_URL");
        var wrong = "00".repeat(32);
        assertThatThrownBy(() -> RelayTls.connect(opts(url), url, wrong).close()).isInstanceOf(Exception.class);
    }

    @Test
    void aFirstContactIsTrustedPinnedAndHeldToThatPin() throws Exception {
        var url = System.getenv("RELAY_TLS_LIVE_TLS_URL");
        try (var conn = RelayTls.connect(opts(url), url, java.util.List.of())) {
            conn.flush(java.time.Duration.ofSeconds(5));
            assertThat(connz()).contains("\"tls_version\": \"1.3\"");
        }
        var pins = RelayTls.storedPins(RelayTls.hostPort(url));
        assertThat(pins).isNotEmpty();
        try (var conn = RelayTls.connect(opts(url), url, pins)) {                  // the pin taken is the relay's
            assertThat(conn.getStatus()).hasToString("CONNECTED");
        }
        var other = System.getenv("RELAY_TLS_LIVE_OTHER_FP");                        // another relay's authority
        if (other != null) {
            assertThatThrownBy(() -> RelayTls.connect(opts(url), url, java.util.List.of(other)).close())
                .isInstanceOf(Exception.class);
        }
    }

    @Test
    void aPinnedRelayWithoutTlsIsRefusedUnlessAllowed() throws Exception {
        var plain = System.getenv("RELAY_TLS_LIVE_PLAIN_URL");
        assertThatThrownBy(() -> RelayTls.connect(opts(plain), plain, "11".repeat(32)).close())
            .hasMessageContaining("pinned but offers no TLS");
        try (var conn = RelayTls.connect(opts(plain), plain, java.util.List.of())) {
            assertThat(conn.getStatus()).hasToString("CONNECTED");      // never pinned, never seen: warned, in the clear
        }
    }

    @Test
    void aRelayWithoutTlsIsReachedInTheClearOnlyUntilItHasBeenSeenEncrypted() throws Exception {
        var plain = System.getenv("RELAY_TLS_LIVE_PLAIN_URL");
        System.setProperty("wyrdsekai.relay.allow.plaintext.link", "true");
        try (var conn = RelayTls.connect(opts(plain), plain, "11".repeat(32))) {
            assertThat(conn.getStatus()).hasToString("CONNECTED");      // transition: warned, in the clear
        }
        RelayTls.remember(RelayTls.hostPort(plain));                       // it was once reached encrypted
        assertThatThrownBy(() -> RelayTls.connect(opts(plain), plain, "11".repeat(32)).close())
            .hasMessageContaining("SSL connection wanted by client");     // now a downgrade: refused
    }

    @Test
    void requireTlsRefusesARelayWithoutIt() {
        System.setProperty("wyrdsekai.relay.require.tls", "true");
        var plain = System.getenv("RELAY_TLS_LIVE_PLAIN_URL");
        assertThatThrownBy(() -> RelayTls.connect(opts(plain), plain, "11".repeat(32)).close()).isInstanceOf(Exception.class);
    }
}
