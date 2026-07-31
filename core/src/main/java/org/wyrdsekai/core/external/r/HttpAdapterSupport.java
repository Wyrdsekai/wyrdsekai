package org.wyrdsekai.core.external.r;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared HTTP plumbing for Phase R AI/ML, smart-home, and media adapters.
 *
 * <p>Centralises the Phase-R guarantees called out in the task brief and
 * 30s timeout per request, a 10MB response
 * cap, JSON {@link ObjectMapper}, and credential resolution via
 * {@link CredentialResolver}. Adapters compose their own request bodies and
 * URL paths but use the shared {@link #execute(HttpRequest, java.util.function.Function)}
 * helper so the failure shape is uniform.</p>
 *
 * <p>Test seam — adapters that need an injected {@link HttpClient} (e.g.
 * to point at a mock server) can pass one through the constructor; default
 * adapters take a lazy {@link Supplier} so the JVM boot path doesn't pay
 * the {@code HttpClient} cost when no item ever invokes an external
 * service.</p>
 */
public final class HttpAdapterSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpAdapterSupport.class);

    /** 10 MB cap per §3.8 — adapters must reject responses larger than this. */
    public static final long MAX_RESPONSE_BYTES = 10L * 1024 * 1024;

    /** 30s default per request. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Supplier<HttpClient> clientSupplier;
    private volatile HttpClient cached;

    public HttpAdapterSupport() {
        this(() -> HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    public HttpAdapterSupport(Supplier<HttpClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    public HttpClient client() {
        var c = cached;
        if (c == null) {
            synchronized (this) {
                if (cached == null) cached = clientSupplier.get();
                c = cached;
            }
        }
        return c;
    }

    public ObjectMapper mapper() { return MAPPER; }

    /** Resolve a credential slot or fail with a structured response. */
    public Optional<String> resolveCredential(String slot) {
        return CredentialResolver.get().resolve(slot);
    }

    public AdapterResponse missingCredential(String slot) {
        return AdapterResponse.fail("credential_missing",
            "credential slot '" + slot + "' not populated", false);
    }

    public AdapterResponse missingArg(String name) {
        return AdapterResponse.fail("missing_arg", "required argument '" + name + "' is missing", false);
    }

    public AdapterResponse unknownMethod(String namespace, String method) {
        return AdapterResponse.fail("unknown_method",
            namespace + "." + method + " is not supported", false);
    }

    public AdapterResponse notYetWired(String namespace, String reason) {
        return AdapterResponse.fail("not_yet_wired", namespace + ": " + reason, false);
    }

    /** Build a JSON body from a map. */
    public String jsonBody(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("json encode failed: " + e.getMessage(), e);
        }
    }

    /**
     * Execute an HTTP request, enforce the 10MB cap, and pass the body
     * through {@code transform} for parsing. Network/parse errors collapse
     * into {@link AdapterResponse} fail responses; HTTP &gt;= 400 yield
     * {@code upstream_error} with the upstream body trimmed.
     */
    public AdapterResponse execute(HttpRequest request,
                                     Function<String, Object> transform) {
        try {
            var resp = client().send(request, HttpResponse.BodyHandlers.ofString());
            var body = resp.body() == null ? "" : resp.body();
            if (body.length() > MAX_RESPONSE_BYTES) {
                return AdapterResponse.fail("response_too_large",
                    "response exceeded 10MB cap", false);
            }
            if (resp.statusCode() >= 400) {
                var trimmed = body.length() > 1024 ? body.substring(0, 1024) : body;
                return AdapterResponse.fail("upstream_error",
                    "HTTP " + resp.statusCode() + ": " + trimmed,
                    resp.statusCode() >= 500);
            }
            try {
                var data = transform.apply(body);
                return AdapterResponse.ok(data);
            } catch (Exception e) {
                log.debug("transform failed: {}", e.getMessage());
                return AdapterResponse.fail("parse_error", e.getMessage(), false);
            }
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", e.getMessage(), true);
        } catch (Exception e) {
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    /** Convenience — JSON-parse the response body into a Map/List. */
    public Object parseJson(String body) {
        try {
            return MAPPER.readValue(body, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("json parse failed: " + e.getMessage(), e);
        }
    }

    public HttpRequest.Builder reqBuilder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(DEFAULT_TIMEOUT);
    }
}
