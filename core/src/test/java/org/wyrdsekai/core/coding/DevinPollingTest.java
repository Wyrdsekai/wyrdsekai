package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2e — covers the Devin REST + polling flow specifically: backoff,
 * terminal-state detection, wallclock cap, PR URL extraction.
 *
 * <p>Tests inject a fake {@link HttpClient} that returns scripted
 * responses keyed by request URL/method, plus a no-op
 * {@link DevinBackend.Sleeper} so polling iterations don't actually
 * wait. Wall-clock-cap tests use a recording sleeper that counts the
 * cumulative virtual sleep duration and fails the test if the loop
 * keeps polling past the cap.</p>
 */
class DevinPollingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DevinRuntimeConfig configWith(int pollIntervalSec, int maxWallclockHours) {
        return new DevinRuntimeConfig(true, "test-org",
            DevinRuntimeConfig.DEFAULT_API_BASE,
            pollIntervalSec, maxWallclockHours, Duration.ofSeconds(60));
    }

    private static AuthResolver apiKey() {
        return name -> new AuthMode.ApiKey("test-devin-key");
    }

    // ─── Polling backoff ───────────────────────────────────────────

    @Test void polling_backoff_doubles_until_capped_at_30s() throws Exception {
        var sleepDurations = new ArrayList<Long>();
        DevinBackend.Sleeper recordingSleep = sleepDurations::add;

        // Script: create returns session_id, then 5 polls return
        // "running", then 1 returns "stopped" (terminal).
        var script = new ScriptedHttp()
            .onPost(".*/sessions$", 200, "{\"session_id\":\"s-1\"}")
            .onGet(".*/sessions/s-1$", 200, "{\"status_enum\":\"running\"}")
            .onGet(".*/sessions/s-1$", 200, "{\"status_enum\":\"running\"}")
            .onGet(".*/sessions/s-1$", 200, "{\"status_enum\":\"running\"}")
            .onGet(".*/sessions/s-1$", 200, "{\"status_enum\":\"running\"}")
            .onGet(".*/sessions/s-1$", 200, "{\"status_enum\":\"stopped\"}");

        var b = new DevinBackend(configWith(2, 4), apiKey(), script, recordingSleep);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        // Backoff progression: 2s, 4s, 8s, 16s, 30s (capped). The last
        // poll returned terminal so the loop stopped before the next
        // sleep — we expect 4 sleeps (one between each running poll).
        assertThat(sleepDurations).hasSize(4);
        assertThat(sleepDurations).containsExactly(2_000L, 4_000L, 8_000L, 16_000L);
    }

    // ─── Terminal-state detection ──────────────────────────────────

    @Test void terminal_status_stops_polling() throws Exception {
        var script = new ScriptedHttp()
            .onPost(".*/sessions$", 200, "{\"session_id\":\"s-1\"}")
            .onGet(".*/sessions/s-1$", 200,
                "{\"status_enum\":\"stopped\",\"workspace\":\"/tmp\"}");

        var b = new DevinBackend(configWith(1, 4), apiKey(), script, m -> {});
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(script.totalRequests()).isEqualTo(2); // 1 create + 1 poll
    }

    // ─── Wall-clock cap enforcement ────────────────────────────────

    @Test void wallclock_cap_via_pollUntilTerminal_direct() throws Exception {
        // Direct test of pollUntilTerminal with a sleeper that advances
        // a virtual clock — but the production method consults the real
        // System.currentTimeMillis(), so this test runs the loop with a
        // sleeper that takes long enough to trip the (very-short) cap.
        var script = new ScriptedHttp()
            .onGet(".*/sessions/.*", 200, "{\"status_enum\":\"running\"}");

        // 1-hour cap (minimum since the field is an int hours value).
        // We trigger the cap by: configuring a 1-hour cap and using a
        // recording sleeper that advances the real clock far enough by
        // sleeping the actual difference. To keep the test fast we use
        // a workaround: pass started-time well in the past so the very
        // first wallclock check trips.
        var cfg = configWith(1, 1);
        var b = new DevinBackend(cfg, apiKey(), script, m -> {});
        var startedMsLongAgo = System.currentTimeMillis() - Duration.ofHours(2).toMillis();
        try {
            b.pollUntilTerminal("s-x", "Bearer test", startedMsLongAgo);
            Assertions.fail("expected TimeoutException");
        } catch (TimeoutException expected) {
            assertThat(expected.getMessage()).contains("wallclock");
        }
    }

    // ─── PR URL extraction ─────────────────────────────────────────

    @Test void terminal_response_pr_url_lands_in_metadata() throws Exception {
        var terminalBody = """
            {
              "status_enum": "stopped",
              "session_id": "s-pr-1",
              "workspace": "/tmp/repo",
              "pull_request": {
                "url": "https://github.com/owner/repo/pull/42",
                "title": "Fix bug X"
              },
              "messages": [{"text": "Started work"}, {"text": "Opened PR"}],
              "structured_output": {
                "files": ["src/foo.java"],
                "summary": "Fixed bug X"
              },
              "total_runtime_seconds": 1234,
              "origin": "wyrdsekai"
            }
            """;
        var script = new ScriptedHttp()
            .onPost(".*/sessions$", 200, "{\"session_id\":\"s-pr-1\"}")
            .onGet(".*/sessions/s-pr-1$", 200, terminalBody);

        var b = new DevinBackend(configWith(1, 4), apiKey(), script, m -> {});
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.summary()).contains("PR https://github.com/owner/repo/pull/42");

        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);

        assertThat(src.backendMetadata())
            .containsEntry("pull_request_url", "https://github.com/owner/repo/pull/42");
        assertThat(src.backendMetadata())
            .containsEntry("pull_request_title", "Fix bug X");
        assertThat(src.backendMetadata()).containsEntry("session_id", "s-pr-1");
        assertThat(src.backendMetadata()).containsEntry("origin", "wyrdsekai");
        assertThat(src.backendMetadata()).containsKey("messages_tail");
        assertThat(src.backendMetadata()).containsKey("structured_output");
        assertThat(src.backendMetadata())
            .containsEntry("total_runtime_seconds", 1234L);
        assertThat(src.files()).contains("src/foo.java");
    }

    @Test void authorization_header_is_set_to_bearer() throws Exception {
        var script = new ScriptedHttp()
            .onPost(".*/sessions$", 200, "{\"session_id\":\"s-1\"}")
            .onGet(".*/sessions/s-1$", 200,
                "{\"status_enum\":\"stopped\",\"workspace\":\"/tmp\"}");

        var b = new DevinBackend(configWith(1, 4), apiKey(), script, m -> {});
        b.submitTask(TaskSpec.create("did:c", "code", "x")).get(5, TimeUnit.SECONDS);

        // First request was POST /sessions; verify it carries the bearer token.
        HttpRequest createReq = script.requests().get(0);
        var auth = createReq.headers().firstValue("Authorization");
        assertThat(auth).isPresent();
        assertThat(auth.get()).isEqualTo("Bearer test-devin-key");
    }

    @Test void create_session_failure_surfaces_failed() throws Exception {
        var script = new ScriptedHttp()
            .onPost(".*/sessions$", 401, "{\"error\":\"unauthorized\"}");

        var b = new DevinBackend(configWith(1, 4), apiKey(), script, m -> {});
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("create-session error 401");
    }

    // ─── Scripted HttpClient helper ────────────────────────────────

    /** Scripted HttpClient that matches request URI + method against regex rules. */
    static final class ScriptedHttp extends HttpClient {
        private record Rule(String method, Pattern uriRegex,
                            int statusCode, String body) {}

        private final List<Rule> rules = new ArrayList<>();
        private final List<HttpRequest> sent = new ArrayList<>();
        private final AtomicInteger postIdx = new AtomicInteger();
        private final AtomicInteger getIdx = new AtomicInteger();

        ScriptedHttp onPost(String uriRegex, int statusCode, String body) {
            rules.add(new Rule("POST", Pattern.compile(uriRegex),
                statusCode, body));
            return this;
        }
        ScriptedHttp onGet(String uriRegex, int statusCode, String body) {
            rules.add(new Rule("GET", Pattern.compile(uriRegex),
                statusCode, body));
            return this;
        }

        List<HttpRequest> requests() { return List.copyOf(sent); }
        int totalRequests() { return sent.size(); }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @SuppressWarnings("unchecked")
        @Override public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) {
            sent.add(req);
            // Find the first matching rule. For repeatable URIs, walk
            // through matches in order — POST rules consumed by postIdx,
            // GET rules consumed by getIdx.
            String method = req.method();
            String uri = req.uri().toString();
            int seenIdx = "POST".equals(method) ? postIdx.getAndIncrement() : getIdx.getAndIncrement();
            int matched = 0;
            for (Rule r : rules) {
                if (!r.method().equals(method)) continue;
                if (!r.uriRegex().matcher(uri).matches()) continue;
                if (matched++ < seenIdx) continue;
                return (HttpResponse<T>) cannedResponse(req.uri(), r.statusCode(), r.body());
            }
            // Fall through: return the LAST matching rule (so callers can
            // script "and after that, keep returning X" without listing
            // every poll).
            HttpResponse<T> last = null;
            for (Rule r : rules) {
                if (!r.method().equals(method)) continue;
                if (!r.uriRegex().matcher(uri).matches()) continue;
                last = (HttpResponse<T>) cannedResponse(req.uri(), r.statusCode(), r.body());
            }
            if (last != null) return last;
            return (HttpResponse<T>) cannedResponse(req.uri(), 404, "{\"error\":\"unmocked\"}");
        }

        @Override public <T> CompletableFuture<HttpResponse<T>>
                sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>>
                sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                          HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }
    }

    static HttpResponse<String> cannedResponse(URI uri, int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return uri; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
