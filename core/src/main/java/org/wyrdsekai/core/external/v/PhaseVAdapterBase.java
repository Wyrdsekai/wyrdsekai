package org.wyrdsekai.core.external.v;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;
import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * -§4.43 — shared scaffolding for the
 * Phase V travel + commerce adapters.
 *
 * <p>Each Phase V adapter is mostly read-only: search calls against a public
 * (or cred-gated) HTTP endpoint, JSON response, mapped onto a small typed
 * shape. The boilerplate that's common across all of them lives here:</p>
 *
 * <ul>
 *   <li>30-second total timeout (10s connect + 30s read).</li>
 *   <li>10MB body cap (response truncated past that).</li>
 *   <li>{@link CredentialResolver} lookup with structured
 *       {@code credential_missing} fallback. Phase V's "stub if not
 *       credentialed" rule lives in {@link #stubIfNoCred(String, String)}.</li>
 *   <li>Common error mapping — IO, timeout, malformed JSON.</li>
 * </ul>
 *
 * <p>Concrete adapters override {@link #namespace()},
 * {@link #capabilities()}, {@link #credentialSlot()}, and
 * {@link #invoke(AdapterRequest)}.</p>
 */
abstract class PhaseVAdapterBase implements ExternalAdapter {
    /**
     * Every Phase-V adapter is scaffolding: each one answers {@code ok({stub:true, ...})}
     * with empty data rather than an error, so an item built on it reports "nothing found"
     * forever and no branch can detect it. Nothing here is advertised to an item author
     * until it declares what it actually reaches.
     */
    @Override public Set<String> wiredCapabilities() { return Set.of(); }


    protected static final Logger log = LoggerFactory.getLogger(PhaseVAdapterBase.class);

    protected static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    protected static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    protected static final long MAX_BODY_BYTES = 10L * 1024L * 1024L;

    protected final ObjectMapper mapper = Json.mapper();
    protected final HttpClient http;

    protected PhaseVAdapterBase() {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    protected PhaseVAdapterBase(HttpClient http) {
        this.http = http;
    }

    @Override public abstract String namespace();
    @Override public abstract Set<String> capabilities();
    @Override public abstract String credentialSlot();
    @Override public abstract AdapterResponse invoke(AdapterRequest request);

    /**
     * Read the credential slot. If absent (and required), returns a stub
     * empty-result {@link AdapterResponse} so authors get a working surface
     * before configuring credentials. Stubs are documented in the spec —
     * Phase V default: read-only stubs for missing creds return
     * {@code {success:true, data:{stub:true, results:[]}}}.
     */
    protected Optional<String> credential() {
        var slot = credentialSlot();
        if (slot == null || slot.isBlank()) return Optional.empty();
        return CredentialResolver.get().resolve(slot);
    }

    /** Synthetic empty-result for adapters that prefer a graceful stub. */
    protected AdapterResponse stubIfNoCred(String resultsKey, String reason) {
        var data = new LinkedHashMap<String, Object>();
        data.put("stub", true);
        data.put("reason", reason);
        data.put(resultsKey, List.of());
        return AdapterResponse.ok(data);
    }

    /** Synthetic empty list result. */
    protected AdapterResponse stubEmptyList(String reason) {
        return stubIfNoCred("results", reason);
    }

    /**
     * Issue a GET against {@code url} (with optional headers) and decode the
     * response as JSON. Errors are normalized into a fail-shaped response.
     */
    protected AdapterResponse httpGetJson(String url, Map<String, String> headers) {
        try {
            var rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "wyrdsekai-phase-v/1.0");
            if (headers != null) headers.forEach(rb::header);
            var req = rb.GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return decodeJsonResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", "request timed out", true);
        } catch (ConnectException | UnknownHostException e) {
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        } catch (IOException e) {
            return AdapterResponse.fail("io_error", e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AdapterResponse.fail("interrupted", e.getMessage(), false);
        } catch (Exception e) {
            return AdapterResponse.fail("adapter_error", e.getMessage(), false);
        }
    }

    /**
     * Issue a POST with a JSON-encoded body and read JSON back.
     */
    protected AdapterResponse httpPostJson(String url, Object body, Map<String, String> headers) {
        try {
            var bodyBytes = mapper.writeValueAsBytes(body == null ? Map.of() : body);
            var rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "wyrdsekai-phase-v/1.0");
            if (headers != null) headers.forEach(rb::header);
            var req = rb.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes)).build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return decodeJsonResponse(resp);
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", "request timed out", true);
        } catch (IOException e) {
            return AdapterResponse.fail("io_error", e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AdapterResponse.fail("interrupted", e.getMessage(), false);
        } catch (Exception e) {
            return AdapterResponse.fail("adapter_error", e.getMessage(), false);
        }
    }

    private AdapterResponse decodeJsonResponse(HttpResponse<byte[]> resp) {
        var bytes = resp.body();
        if (bytes != null && bytes.length > MAX_BODY_BYTES) {
            return AdapterResponse.fail("body_too_large",
                "response exceeded 10MB cap", false);
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            return AdapterResponse.fail("auth_failed",
                "upstream returned " + resp.statusCode(), false);
        }
        if (resp.statusCode() == 429) {
            return AdapterResponse.fail("rate_limited",
                "upstream rate-limited the request", true);
        }
        if (resp.statusCode() >= 500) {
            return AdapterResponse.fail("upstream_error",
                "upstream returned " + resp.statusCode(), true);
        }
        if (resp.statusCode() >= 400) {
            return AdapterResponse.fail("client_error",
                "upstream returned " + resp.statusCode(), false);
        }
        try {
            var node = mapper.readTree(bytes == null ? new byte[0] : bytes);
            var asMap = mapper.convertValue(node, Object.class);
            return AdapterResponse.ok(asMap);
        } catch (Exception e) {
            return AdapterResponse.fail("malformed_json", e.getMessage(), false);
        }
    }

    /** Convenience — extract a string arg or default to empty. */
    protected static String str(Map<String, Object> args, String key) {
        var v = args.get(key);
        return v == null ? "" : v.toString();
    }

    protected static String str(Map<String, Object> args, String key, String defaultValue) {
        var v = args.get(key);
        return v == null ? defaultValue : v.toString();
    }
}
