package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2e — unit tests for {@link DevinBackend} (REST/polling shape). */
class DevinBackendTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_devin() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(DevinBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_devin() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        assertThat(b.name()).isEqualTo("devin");
        assertThat(b.name()).isEqualTo(DevinBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_default_is_5000() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        var spec = TaskSpec.create("did:c", "code", "x");
        assertThat(b.estimatedCu(spec))
            .as("Devin's conservative HIGH default — must NOT drop below 5000")
            .isGreaterThanOrEqualTo(5000L);
    }

    @Test void estimated_cu_grows_with_long_descriptions() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        var shortSpec = TaskSpec.create("did:c", "code", "x");
        var longSpec = TaskSpec.create("did:c", "code", "x".repeat(4000));
        assertThat(b.estimatedCu(longSpec)).isGreaterThan(b.estimatedCu(shortSpec));
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = DevinRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.apiBase()).isEqualTo(DevinRuntimeConfig.DEFAULT_API_BASE);
        assertThat(cfg.pollIntervalSec()).isEqualTo(10);
        assertThat(cfg.maxWallclockHours()).isEqualTo(4);
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.devin {\n"
            + "  enabled = true\n"
            + "  org-id = \"org-123\"\n"
            + "  api-base = \"https://api.devin.test\"\n"
            + "  poll-interval-sec = 5\n"
            + "  max-wallclock-hours = 2\n"
            + "  request-timeout-sec = 30\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = DevinRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.orgId()).isEqualTo("org-123");
        assertThat(cfg.apiBase()).isEqualTo("https://api.devin.test");
        assertThat(cfg.pollIntervalSec()).isEqualTo(5);
        assertThat(cfg.maxWallclockHours()).isEqualTo(2);
        assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.devin {\n"
            + "  enabled = true\n"
            + "  org_id = \"org-456\"\n"
            + "  poll_interval_sec = 7\n"
            + "}";
        var cfg = DevinRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.orgId()).isEqualTo("org-456");
        assertThat(cfg.pollIntervalSec()).isEqualTo(7);
    }

    // ─── REST URL construction ─────────────────────────────────────

    @Test void create_session_url_uses_v3_org_path() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        assertThat(b.createSessionUrl())
            .isEqualTo("https://api.devin.ai/v3/organizations/test-org/sessions");
    }

    @Test void poll_session_url_uses_v3_org_session_path() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        assertThat(b.pollSessionUrl("sess-1"))
            .isEqualTo("https://api.devin.ai/v3/organizations/test-org/sessions/sess-1");
    }

    // ─── REST body construction ────────────────────────────────────

    @Test void create_session_body_includes_prompt_and_idempotency_key() {
        var b = new DevinBackend(enabledDefaults(), apiKeyResolver(),
            stubClient(List.of()), noSleep());
        var taskId = UUID.randomUUID();
        var spec = new TaskSpec(taskId, "did:c", "code", "fix bug X",
            "/tmp/repo", List.of(), 0L, null);

        Map<String, Object> body = b.buildCreateSessionBody(spec, taskId);
        // The prompt is wrapped with ITEMS_AS_TOOLS_PREAMBLE so the
        // agent produces a -shaped artifact.
        var prompt = (String) body.get("prompt");
        assertThat(prompt).contains("fix bug X");
        assertThat(prompt).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(prompt).contains("--- TASK ---");
        assertThat(body).containsEntry("idempotency_key", taskId.toString());
        assertThat(body).containsEntry("task_type", "code");
        assertThat(body).containsEntry("workspace_hint", "/tmp/repo");
    }

    @Test void create_session_body_does_not_include_api_key() {
        // The api-key must travel in the Authorization header, never the body.
        var b = new DevinBackend(enabledDefaults(),
            name -> new AuthMode.ApiKey("super-secret-devin-key"),
            stubClient(List.of()), noSleep());
        var body = b.buildCreateSessionBody(TaskSpec.create("did:c", "code", "x"),
            UUID.randomUUID());
        assertThat(body.toString()).doesNotContain("super-secret-devin-key");
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_http() throws Exception {
        var sentRequests = new ArrayList<HttpRequest>();
        HttpClient noopHttp = recordingHttpClient(sentRequests, "");
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "set DEVIN_API_KEY in your Key Chest", "no auth");

        var b = new DevinBackend(enabledDefaults(), missing, noopHttp, noSleep());
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(sentRequests).isEmpty();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
    }

    @Test void missing_org_id_returns_failed_immediately() throws Exception {
        var cfg = new DevinRuntimeConfig(true, null, "https://api.devin.ai",
            10, 4, Duration.ofSeconds(60));
        var b = new DevinBackend(cfg, apiKeyResolver(),
            stubClient(List.of()), noSleep());
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("org_id");
    }

    @Test void disabled_returns_failed_immediately() throws Exception {
        var disabled = new DevinRuntimeConfig(false, "x", null, 0, 0, null);
        var b = new DevinBackend(disabled, apiKeyResolver(),
            stubClient(List.of()), noSleep());
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── Terminal-status detection ─────────────────────────────────

    @Test void isTerminalStatus_running_states_are_not_terminal() {
        assertThat(DevinBackend.isTerminalStatus("running")).isFalse();
        assertThat(DevinBackend.isTerminalStatus("pending")).isFalse();
        assertThat(DevinBackend.isTerminalStatus("queued")).isFalse();
        assertThat(DevinBackend.isTerminalStatus("in_progress")).isFalse();
    }

    @Test void isTerminalStatus_settled_states_are_terminal() {
        assertThat(DevinBackend.isTerminalStatus("stopped")).isTrue();
        assertThat(DevinBackend.isTerminalStatus("blocked")).isTrue();
        assertThat(DevinBackend.isTerminalStatus("finished")).isTrue();
        assertThat(DevinBackend.isTerminalStatus("completed")).isTrue();
        assertThat(DevinBackend.isTerminalStatus("failed")).isTrue();
    }

    @Test void nextInterval_doubles_until_capped_at_30s() {
        Duration d = Duration.ofSeconds(5);
        d = DevinBackend.nextInterval(d);
        assertThat(d).isEqualTo(Duration.ofSeconds(10));
        d = DevinBackend.nextInterval(d);
        assertThat(d).isEqualTo(Duration.ofSeconds(20));
        d = DevinBackend.nextInterval(d);
        assertThat(d).isEqualTo(Duration.ofSeconds(30));
        // Cap holds.
        d = DevinBackend.nextInterval(d);
        assertThat(d).isEqualTo(Duration.ofSeconds(30));
    }

    // ─── Helpers ──────────────────────────────────────────────────

    static DevinRuntimeConfig enabledDefaults() {
        return new DevinRuntimeConfig(true, "test-org",
            DevinRuntimeConfig.DEFAULT_API_BASE,
            10, 4, Duration.ofSeconds(60));
    }

    static AuthResolver apiKeyResolver() {
        return name -> new AuthMode.ApiKey("test-devin-key");
    }

    /** A "do nothing" sleeper so polling tests don't actually wait. */
    static DevinBackend.Sleeper noSleep() {
        return millis -> { /* no-op */ };
    }

    /** Build a stub HttpClient that returns a canned create-session response. */
    static HttpClient stubClient(List<JsonNode> ignored) {
        return new HttpClient() {
            @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
            @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
            @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
            @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public Optional<Executor> executor() { return Optional.empty(); }
            @Override public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException("stubClient: no calls expected");
            }
            @Override public <T> CompletableFuture<HttpResponse<T>>
                    sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> CompletableFuture<HttpResponse<T>>
                    sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h,
                              HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Build an HttpClient that records requests it sees and returns a canned 200 body. */
    static HttpClient recordingHttpClient(List<HttpRequest> sink, String responseBody) {
        return new HttpClient() {
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
                sink.add(req);
                return (HttpResponse<T>) cannedResponse(req.uri(), responseBody);
            }
            @Override public <T> CompletableFuture<HttpResponse<T>>
                    sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> CompletableFuture<HttpResponse<T>>
                    sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h,
                              HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
    }

    static HttpResponse<String> cannedResponse(URI uri, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
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
