package org.wyrdsekai.core.external.o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.DomainAllowlist;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * small HTTP utilities shared by every
 * Phase O adapter.
 *
 * <p>Centralises the per-adapter conventions:</p>
 * <ul>
 *   <li>30s default timeout, 10MB response cap.</li>
 *   <li>{@link DomainAllowlist} pre-flight when the request originates from
 *       a manifest-gated item.</li>
 *   <li>Normalised error mapping into the
 *       {@link AdapterResponse#fail(String, String, boolean)} shape.</li>
 * </ul>
 *
 * <p>The class is package-private — adapters compose it; scripts never see it.</p>
 */
final class AdapterHttp {

    private static final Logger log = LoggerFactory.getLogger(AdapterHttp.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final long MAX_RESPONSE_BYTES = 10L * 1024 * 1024;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private AdapterHttp() {}

    static HttpClient client() { return HTTP; }

    /**
     * Honour the request-bound {@link AdapterRequest#capabilities()} domain
     * allowlist when set; UNRESTRICTED bypasses. Returns null when allowed,
     * a fail-shaped response when blocked.
     */
    static AdapterResponse domainCheck(AdapterRequest req, String url) {
        if (req == null || req.capabilities() == null) return null;
        if (req.capabilities().isUnrestricted()) return null;
        var domains = req.capabilities().externalDomains();
        if (domains == null || domains.isEmpty()) return null; // empty == not enforced here
        var allow = DomainAllowlist.of(domains);
        if (allow.isAllowed(url)) return null;
        return AdapterResponse.fail("domain_blocked",
            "external domain not in allowlist: " + url, false);
    }

    /** Send a POST with JSON body. Returns the raw {@link HttpResponse}. */
    static HttpResponse<String> postJson(String url, String body, Map<String, String> headers)
            throws Exception {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DEFAULT_TIMEOUT)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Send a GET. */
    static HttpResponse<String> get(String url, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DEFAULT_TIMEOUT)
            .GET();
        if (headers != null) headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Send a form-encoded POST (Slack web API style). */
    static HttpResponse<String> postForm(String url, Map<String, String> form,
                                          Map<String, String> headers) throws Exception {
        var sb = new StringBuilder();
        if (form != null) {
            for (var e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(URLEncoder.encode(
                    e.getValue() == null ? "" : e.getValue(),
                    StandardCharsets.UTF_8));
            }
        }
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DEFAULT_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
        if (headers != null) headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Map an HTTP {@link HttpResponse} into a normalised
     * {@link AdapterResponse}. The body is expected to be JSON; on parse
     * failure the raw text is bubbled through.
     */
    static AdapterResponse fromHttp(HttpResponse<String> resp) {
        if (resp == null) {
            return AdapterResponse.fail("no_response", "no HTTP response", true);
        }
        if (resp.body() != null && resp.body().length() > MAX_RESPONSE_BYTES) {
            return AdapterResponse.fail("response_too_large",
                "response exceeds 10MB cap", false);
        }
        var status = resp.statusCode();
        try {
            JsonNode json = (resp.body() == null || resp.body().isBlank())
                ? null : MAPPER.readTree(resp.body());
            if (status >= 200 && status < 300) {
                return AdapterResponse.ok(jsonToMap(json, resp.body()));
            }
            var retryable = status == 429 || status >= 500;
            var code = "http_" + status;
            var msg = json != null && json.has("error")
                ? json.path("error").asText()
                : (resp.body() == null ? ("HTTP " + status) : truncate(resp.body(), 500));
            return AdapterResponse.fail(code, msg, retryable);
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error",
                "could not parse response: " + e.getMessage(), false);
        }
    }

    private static Object jsonToMap(JsonNode json, String raw) {
        if (json == null) return Map.of("ok", true);
        if (json.isObject()) return MAPPER.convertValue(json, Map.class);
        if (json.isArray()) return MAPPER.convertValue(json, List.class);
        return Map.of("value", json.asText());
    }

    /** Convert an arbitrary value to a Map<String,Object> with safe fallback. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    static String str(Map<String, Object> args, String key) {
        if (args == null) return null;
        var v = args.get(key);
        return v == null ? null : v.toString();
    }

    static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * Run a sub-process with a hard timeout. Used by adapters that wrap
     * sidecar CLIs (signal-cli, etc). Returns
     * {@code {exitCode, stdout, stderr}} or a fail response on timeout.
     */
    static AdapterResponse runProcess(List<String> command, Duration timeout) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            var proc = pb.start();
            var ok = proc.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!ok) {
                proc.destroyForcibly();
                return AdapterResponse.fail("process_timeout",
                    "process did not exit within " + timeout.toSeconds() + "s", true);
            }
            var stdout = new String(proc.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            var stderr = new String(proc.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
            if (proc.exitValue() != 0) {
                return AdapterResponse.fail("process_failed",
                    "exit=" + proc.exitValue() + ": " + truncate(stderr.trim(), 500), false);
            }
            return AdapterResponse.ok(Map.of(
                "exitCode", proc.exitValue(),
                "stdout", stdout.trim()));
        } catch (Exception e) {
            return AdapterResponse.fail("process_error", e.getMessage(), true);
        }
    }
}
