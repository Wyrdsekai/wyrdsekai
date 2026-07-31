package org.wyrdsekai.core.external.p;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Phase P shared scaffolding for HTTP-backed adapters.
 *
 * <p>Centralises:</p>
 * <ul>
 *   <li>30s request timeout + 10MB response cap (per spec §3.8).</li>
 *   <li>Credential lookup via {@link CredentialResolver}; on miss returns
 *       a {@code credentials_missing} fail-shape.</li>
 *   <li>JSON parse + structured error normalisation.</li>
 *   <li>Test escape: {@link #setBaseUrlOverride(String)} lets tests redirect
 *       all outbound calls to a local {@code com.sun.net.httpserver.HttpServer}.
 *   </li>
 *   <li>Test escape: {@link #setHttpClientOverride(HttpClient)} for full
 *       client substitution.</li>
 * </ul>
 *
 * <p>Subclasses implement {@link #invoke(AdapterRequest)} but typically
 * delegate to {@link #execute(HttpRequest.Builder, java.util.function.Function)}
 * for the boilerplate.</p>
 */
public abstract class BaseHttpAdapter implements ExternalAdapter {

    protected static final Logger log = LoggerFactory.getLogger(BaseHttpAdapter.class);
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final Duration TIMEOUT = Duration.ofSeconds(30);
    protected static final long MAX_BYTES = 10L * 1024 * 1024; // 10MB
    protected static final HttpClient DEFAULT_HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile String baseUrlOverride;
    private volatile HttpClient httpClientOverride;

    /** Default base URL — subclasses use this when {@link #baseUrl()} is not overridden. */
    protected abstract String defaultBaseUrl();

    /** Effective base URL — test override wins. */
    protected final String baseUrl() {
        var override = baseUrlOverride;
        return override != null && !override.isBlank() ? override : defaultBaseUrl();
    }

    /** Test-only escape — redirect all calls to a local mock server. */
    public final void setBaseUrlOverride(String url) {
        this.baseUrlOverride = url;
    }

    /** Test-only escape — substitute an HttpClient (e.g. one with a fake transport). */
    public final void setHttpClientOverride(HttpClient client) {
        this.httpClientOverride = client;
    }

    protected final HttpClient http() {
        var override = httpClientOverride;
        return override != null ? override : DEFAULT_HTTP;
    }

    /** Resolve the credential or return the {@code credentials_missing} fail-shape. */
    protected final Optional<String> resolveCredential() {
        return CredentialResolver.get().resolve(credentialSlot());
    }

    /** Build a {@code credentials_missing} response — non-retryable. */
    protected final AdapterResponse credentialsMissing() {
        return AdapterResponse.fail("credentials_missing",
            "credential slot not populated: " + credentialSlot(), false);
    }

    /** Required-arg validation helper. */
    protected static String requireString(Map<String, Object> args, String key) {
        var v = args.get(key);
        if (v == null) return null;
        var s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /** Optional-arg helper that returns the default on null/blank. */
    protected static String optString(Map<String, Object> args, String key, String fallback) {
        var v = args.get(key);
        if (v == null) return fallback;
        var s = String.valueOf(v);
        return s.isBlank() ? fallback : s;
    }

    /**
     * Standard execute: send the request, enforce size cap, parse JSON, and
     * route the parsed body through {@code onSuccess}. Non-2xx responses are
     * normalised into a fail-shape with {@code http_<code>}.
     */
    protected final AdapterResponse execute(HttpRequest.Builder reqBuilder,
                                              Function<JsonNode, AdapterResponse> onSuccess) {
        var req = reqBuilder.timeout(TIMEOUT).build();
        try {
            var resp = http().send(req, HttpResponse.BodyHandlers.ofByteArray());
            var bytes = resp.body();
            if (bytes != null && bytes.length > MAX_BYTES) {
                return AdapterResponse.fail("response_too_large",
                    "response exceeded 10MB cap: " + bytes.length, false);
            }
            var status = resp.statusCode();
            if (status < 200 || status >= 300) {
                var bodySnippet = bytes == null ? "" :
                    new String(bytes, 0, Math.min(bytes.length, 1024));
                return AdapterResponse.fail("http_" + status, bodySnippet, status >= 500 || status == 429);
            }
            JsonNode tree;
            try {
                tree = bytes == null || bytes.length == 0
                    ? MAPPER.nullNode()
                    : MAPPER.readTree(bytes);
            } catch (Exception parse) {
                return AdapterResponse.fail("parse_error", parse.getMessage(), false);
            }
            return onSuccess.apply(tree);
        } catch (HttpTimeoutException te) {
            return AdapterResponse.fail("timeout", "request timed out after 30s", true);
        } catch (IOException io) {
            return AdapterResponse.fail("network_error", io.getMessage(), true);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return AdapterResponse.fail("interrupted", ie.getMessage(), true);
        } catch (Exception e) {
            return AdapterResponse.fail("adapter_threw", e.getMessage(), true);
        }
    }

    /** Build a JSON body publisher for an arbitrary value. */
    protected static HttpRequest.BodyPublisher jsonBody(Object value) {
        try {
            return HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new RuntimeException("failed to serialise body", e);
        }
    }

    /** URL-encode a string for query/path use. */
    protected static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Walk a JSON array node into a list of maps; non-array returns empty list. */
    protected static List<Map<String, Object>> nodeToList(JsonNode node) {
        var out = new ArrayList<Map<String, Object>>();
        if (node == null || !node.isArray()) return out;
        for (var el : node) {
            out.add(nodeToMap(el));
        }
        return out;
    }

    /** Convert a JsonNode to a Map<String, Object>; non-object returns empty. */
    protected static Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (node.isObject()) {
            var out = new LinkedHashMap<String, Object>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), nodeToValue(e.getValue())));
            return out;
        }
        return Map.of("value", nodeToValue(node));
    }

    /** Recursively unwrap a JsonNode into a plain JS-friendly value. */
    protected static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isInt() || node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) {
            var out = new ArrayList<Object>();
            for (var el : node) out.add(nodeToValue(el));
            return out;
        }
        if (node.isObject()) return nodeToMap(node);
        return node.toString();
    }

    /** Convenience: build a URI from baseUrl + path. */
    protected final URI uri(String pathAndQuery) {
        var base = baseUrl();
        if (base.endsWith("/") && pathAndQuery.startsWith("/")) {
            return URI.create(base + pathAndQuery.substring(1));
        }
        if (!base.endsWith("/") && !pathAndQuery.startsWith("/")) {
            return URI.create(base + "/" + pathAndQuery);
        }
        return URI.create(base + pathAndQuery);
    }
}
