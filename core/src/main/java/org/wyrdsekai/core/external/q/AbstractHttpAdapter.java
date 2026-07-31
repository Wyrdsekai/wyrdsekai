package org.wyrdsekai.core.external.q;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.DomainAllowlist;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * base class shared by Phase Q adapters
 * (productivity + knowledge & research).
 *
 * <p>Centralises HTTP client construction (30s timeout, 10MB cap), credential
 * resolution via {@link CredentialResolver}, domain allowlist enforcement,
 * and JSON encoding/decoding. Concrete adapters implement
 * {@link #invoke(AdapterRequest)} and call helpers like
 * {@link #httpGetJson(String, Map, Map)} to issue requests.</p>
 *
 * <p>Tests can override {@link #httpClient()} or substitute responses via
 * the {@code transport} hook to avoid real network calls. Network-required
 * integration tests should use the JUnit {@code @Tag("external")} marker.</p>
 */
public abstract class AbstractHttpAdapter implements ExternalAdapter {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final long MAX_RESPONSE_BYTES = 10L * 1024 * 1024;
    protected static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Lazily-built HttpClient — overridable for tests. */
    private volatile HttpClient client;

    /**
     * Optional transport hook for tests. When non-null, all
     * {@link #httpGetJson} / {@link #httpPostJson} calls route through it
     * instead of the real HttpClient. Production code never sets this.
     */
    private volatile Transport transportOverride;

    protected HttpClient httpClient() {
        var c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** Test seam — production callers never use this. */
    public void setTransportForTests(Transport transport) {
        this.transportOverride = transport;
    }

    // ── Credential helpers ────────────────────────────────────────

    protected Optional<String> resolveCredential() {
        var slot = credentialSlot();
        if (slot == null || slot.isBlank()) return Optional.empty();
        return CredentialResolver.get().resolve(slot);
    }

    protected AdapterResponse missingCredentials() {
        return AdapterResponse.fail("credentials_missing",
            "credential slot not populated: " + credentialSlot(), false);
    }

    // ── Domain allowlist ──────────────────────────────────────────

    /**
     * Domains this adapter is allowed to reach. Concrete adapters return
     * the upstream service hosts (e.g. {@code List.of("api.notion.com")}).
     * The allowlist is consulted before every request so a misconfigured
     * adapter can't egress arbitrarily.
     */
    protected abstract List<String> defaultDomains();

    private DomainAllowlist defaultAllowlist;

    protected DomainAllowlist allowlist() {
        var a = defaultAllowlist;
        if (a == null) {
            synchronized (this) {
                a = defaultAllowlist;
                if (a == null) {
                    a = DomainAllowlist.of(defaultDomains());
                    defaultAllowlist = a;
                }
            }
        }
        return a;
    }

    protected boolean isAllowed(String url) {
        return allowlist().isAllowed(url);
    }

    // ── HTTP helpers ──────────────────────────────────────────────

    protected AdapterResponse httpGetJson(String url, Map<String, String> headers,
                                            Map<String, ?> queryParams) {
        var u = appendQuery(url, queryParams);
        if (!isAllowed(u)) {
            return AdapterResponse.fail("domain_not_allowed", u, false);
        }
        if (transportOverride != null) {
            return transportOverride.send("GET", u, headers, null);
        }
        try {
            var req = HttpRequest.newBuilder()
                .uri(URI.create(u))
                .timeout(HTTP_TIMEOUT)
                .GET();
            if (headers != null) headers.forEach(req::header);
            var resp = httpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
            return decodeResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", e.getMessage(), true);
        } catch (Exception e) {
            log.debug("http GET failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    protected AdapterResponse httpPostJson(String url, Map<String, String> headers,
                                             Object body) {
        if (!isAllowed(url)) {
            return AdapterResponse.fail("domain_not_allowed", url, false);
        }
        if (transportOverride != null) {
            return transportOverride.send("POST", url, headers, body);
        }
        try {
            var json = MAPPER.writeValueAsString(body == null ? Map.of() : body);
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
            if (headers != null) headers.forEach(b::header);
            var resp = httpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            return decodeResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", e.getMessage(), true);
        } catch (Exception e) {
            log.debug("http POST failed: {}", e.getMessage());
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    protected AdapterResponse httpPatchJson(String url, Map<String, String> headers,
                                              Object body) {
        if (!isAllowed(url)) {
            return AdapterResponse.fail("domain_not_allowed", url, false);
        }
        if (transportOverride != null) {
            return transportOverride.send("PATCH", url, headers, body);
        }
        try {
            var json = MAPPER.writeValueAsString(body == null ? Map.of() : body);
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
            if (headers != null) headers.forEach(b::header);
            var resp = httpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            return decodeResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", e.getMessage(), true);
        } catch (Exception e) {
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    protected AdapterResponse httpDelete(String url, Map<String, String> headers) {
        if (!isAllowed(url)) {
            return AdapterResponse.fail("domain_not_allowed", url, false);
        }
        if (transportOverride != null) {
            return transportOverride.send("DELETE", url, headers, null);
        }
        try {
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .DELETE();
            if (headers != null) headers.forEach(b::header);
            var resp = httpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            return decodeResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", e.getMessage(), true);
        } catch (Exception e) {
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    private AdapterResponse decodeResponse(HttpResponse<String> resp) {
        var body = resp.body() == null ? "" : resp.body();
        if (body.length() > MAX_RESPONSE_BYTES) {
            return AdapterResponse.fail("response_too_large",
                "response exceeded " + MAX_RESPONSE_BYTES + " bytes", false);
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            return AdapterResponse.fail("unauthorized",
                "HTTP " + resp.statusCode() + " — credentials rejected", false);
        }
        if (resp.statusCode() == 429) {
            return AdapterResponse.fail("rate_limited",
                "HTTP 429 — rate limit exceeded", true);
        }
        if (resp.statusCode() >= 500) {
            return AdapterResponse.fail("upstream_error",
                "HTTP " + resp.statusCode(), true);
        }
        if (resp.statusCode() >= 400) {
            return AdapterResponse.fail("upstream_error",
                "HTTP " + resp.statusCode() + ": " + truncate(body, 256), false);
        }
        if (body.isBlank()) return AdapterResponse.ok(Map.of());
        try {
            // Try JSON first; fall back to raw text.
            var parsed = MAPPER.readValue(body, Object.class);
            return AdapterResponse.ok(parsed);
        } catch (Exception e) {
            return AdapterResponse.ok(Map.of("raw", body));
        }
    }

    protected static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static String appendQuery(String url, Map<String, ?> params) {
        if (params == null || params.isEmpty()) return url;
        var sep = url.contains("?") ? "&" : "?";
        var sb = new StringBuilder(url).append(sep);
        boolean first = true;
        for (var e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(String.valueOf(e.getValue()),
                  StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Test seam — substituted into HTTP helpers when set. */
    @FunctionalInterface
    public interface Transport {
        AdapterResponse send(String method, String url,
                             Map<String, String> headers, Object body);
    }

    /** Convenience for adapters: stringify a required arg or fail. */
    protected static String requireString(AdapterRequest req, String name) {
        var v = req.args().get(name);
        if (v == null) return null;
        return String.valueOf(v);
    }
}
