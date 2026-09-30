package org.wyrdsekai.scripting.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Can use, cannot read": an item refers to a Safe slot by name and the HTTP layer puts the
 * secret into the request. Until 2026-09-28 {@code world.safe.get} was specified to return the
 * plaintext (any provider that implemented it would have handed the secret to the script).
 */
class SafeReferenceTest {

    private static final String SECRET = "s3cr3t-token-value";

    /** Records what the provider was asked to send, and echoes the Authorization header back. */
    private static class SendingProvider extends ArtifactApiTest.StubProvider {
        final AtomicReference<Map<String, Object>> sentOpts = new AtomicReference<>();

        @Override
        public Optional<String> safeSecretForRequest(String slot) {
            return "github.token".equals(slot) ? Optional.of(SECRET) : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> webPost(String url, Object body, Map<String, Object> opts) {
            sentOpts.set(opts);
            var headers = (Map<String, String>) opts.get("headers");
            return Map.of("status", 200, "body", "you sent " + headers.get("Authorization"));
        }
    }

    private static ItemCapabilitySet caps(String... slots) {
        var manifest = new ItemManifest("poster", "1.0.0", "d", "did:wyrd:test",
            List.of("web.post", "safe.get"), Map.of(), "low", List.of(),
            List.of("api.example.com"), List.of(), List.of(slots), null, null, null, "1.0", null);
        return ItemCapabilitySet.from(manifest);
    }

    @Test
    void safe_get_hands_the_script_a_reference_not_the_secret() {
        var api = new ItemWorldApi(new SendingProvider(), caps("github.token"));
        var ref = api.safe.get("github.token");
        assertThat(ref).isEqualTo("{{safe:github.token}}").doesNotContain(SECRET);
        assertThat(api.safe.get("other.slot")).isNull();
    }

    @Test
    void the_secret_goes_into_the_header_and_never_comes_back() {
        var provider = new SendingProvider();
        var api = new ItemWorldApi(provider, caps("github.token"));
        var result = api.web.post("https://api.example.com/issues", "{}",
            Map.of("headers", Map.of("Authorization", "Bearer " + api.safe.get("github.token"))));

        @SuppressWarnings("unchecked")
        var sentHeaders = (Map<String, String>) provider.sentOpts.get().get("headers");
        assertThat(sentHeaders.get("Authorization")).isEqualTo("Bearer " + SECRET);
        assertThat(result.get("body").toString()).isEqualTo("you sent Bearer [secret]")
            .doesNotContain(SECRET);
    }

    @Test
    void a_slot_the_manifest_does_not_list_is_refused() {
        var provider = new SendingProvider();
        var api = new ItemWorldApi(provider, caps("some.other.slot"));
        assertThatThrownBy(() -> api.web.post("https://api.example.com/x", "{}",
                Map.of("headers", Map.of("Authorization", "Bearer {{safe:github.token}}"))))
            .isInstanceOf(CapabilityDeniedError.class)
            .hasMessageContaining("github.token");
        assertThat(provider.sentOpts.get()).isNull();
    }

    @Test
    void an_unset_credential_is_reported_not_sent() {
        var provider = new SendingProvider() {
            @Override
            public Optional<String> safeSecretForRequest(String slot) { return Optional.empty(); }
        };
        var api = new ItemWorldApi(provider, caps("github.token"));
        var result = api.web.post("https://api.example.com/x", "{}",
            Map.of("headers", Map.of("Authorization", "Bearer {{safe:github.token}}")));
        assertThat(result.get("error")).isEqualTo("credential_missing");
        assertThat(result.get("slot")).isEqualTo("github.token");
        assertThat(provider.sentOpts.get()).isNull();
    }

    @Test
    void a_reference_in_the_url_or_body_is_sent_as_written() {
        var provider = new SendingProvider();
        var api = new ItemWorldApi(provider, caps("github.token"));
        api.web.post("https://api.example.com/x", "{{safe:github.token}}",
            Map.of("headers", Map.of("X-Note", "plain")));
        @SuppressWarnings("unchecked")
        var sentHeaders = (Map<String, String>) provider.sentOpts.get().get("headers");
        assertThat(sentHeaders).containsEntry("X-Note", "plain");
    }

    @Test
    void the_http_global_resolves_header_references_and_scrubs_the_echo() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        server.createContext("/echo", exchange -> {
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            seen.set(auth);
            var body = ("header was " + auth).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            // Unrestricted (an item built into the server) so the loopback test server is reachable.
            var client = new ScriptHttpClient(ItemCapabilitySet.UNRESTRICTED,
                slot -> "github.token".equals(slot) ? Optional.of(SECRET) : Optional.empty());
            var body = client.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/echo",
                Map.of("method", "GET",
                    "headers", Map.of("Authorization", "Bearer {{safe:github.token}}")));
            assertThat(seen.get()).isEqualTo("Bearer " + SECRET);
            assertThat(body).isEqualTo("header was Bearer [secret]");
        } finally {
            server.stop(0);
        }
    }
}
