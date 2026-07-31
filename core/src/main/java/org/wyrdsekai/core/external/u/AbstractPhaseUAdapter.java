package org.wyrdsekai.core.external.u;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * -§4.41 (Phase U) — base class shared
 * by every health, government, maps, and weather adapter.
 *
 * <p>Provides common plumbing: 30-second per-call timeout, 10MB response
 * cap, namespace + credential-slot accessors, capability-set declaration,
 * and a uniform {@code stub(method)} helper that returns
 * {@code AdapterResponse.fail("not_yet_wired", ...)} for surfaces that need
 * later integration work (vendor SDKs, OAuth dance, etc).</p>
 *
 * <p>Concrete adapters override only {@link #invoke(AdapterRequest)} and the
 * declarative bits ({@link #namespace()}, {@link #capabilities()},
 * {@link #credentialSlot()}). The auth-free adapters (Nominatim, Open-Meteo,
 * USDA, WHO) declare an empty credential slot — the resolver short-circuits
 * to {@link Optional#empty()} on a blank slot and the adapter proceeds.</p>
 */
abstract class AbstractPhaseUAdapter implements ExternalAdapter {

    /** Per-call HTTP timeout — see §3.8 {@code 30s default}. */
    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Maximum bytes any adapter may pull from a single upstream response. */
    protected static final long MAX_RESPONSE_BYTES = 10L * 1024L * 1024L;

    /** Lazy shared HTTP client — JDK native, follows normal redirects. */
    protected static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Override public String providerApiVersion() { return "1.0"; }

    /**
     * Convenience for adapters that need a credential before they can do any
     * real work. Returns the resolved value or fires the fail-shaped envelope
     * with {@code credential_missing} for the script to handle.
     */
    protected Optional<String> requireCredential() {
        var slot = credentialSlot();
        if (slot == null || slot.isBlank()) return Optional.empty();
        return CredentialResolver.get().resolve(slot);
    }

    /**
     * Convenience response for stubbed methods we declare in
     * {@link #capabilities()} but don't yet wire to live infrastructure.
     * Returning a structured fail keeps scripts deterministic — they can
     * branch on {@code error.code === "not_yet_wired"} the same way they
     * branch on {@code "credential_missing"}.
     */
    protected AdapterResponse stub(String method) {
        return AdapterResponse.fail("not_yet_wired",
            namespace() + "." + method + " adapter is registered but not "
            + "yet wired to a live backend (Phase U scaffolding)", false);
    }

    /** Shared no-credential-supplied envelope. */
    protected AdapterResponse credentialMissing() {
        return AdapterResponse.fail("credential_missing",
            "credential slot '" + credentialSlot() + "' is not populated", false);
    }

    /**
     * Shared HTTP GET with timeout + size cap. Returns the raw body string on
     * 2xx; otherwise a fail envelope. Adapters parse JSON themselves.
     */
    protected AdapterResponse httpGet(String url, Map<String, String> headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(TIMEOUT);
            if (headers != null) headers.forEach(b::header);
            var resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return AdapterResponse.fail("upstream_" + resp.statusCode(),
                    truncate(resp.body()), resp.statusCode() >= 500);
            }
            var body = resp.body();
            if (body != null && body.length() > MAX_RESPONSE_BYTES) {
                return AdapterResponse.fail("response_too_large",
                    "upstream returned more than 10MB", false);
            }
            return AdapterResponse.ok(Map.of("status", resp.statusCode(), "body", body));
        } catch (HttpTimeoutException e) {
            return AdapterResponse.fail("timeout", "upstream timed out after 30s", true);
        } catch (Exception e) {
            return AdapterResponse.fail("transport_error", e.getMessage(), true);
        }
    }

    /** Helper for the abstract Set.of() pattern at adapter-declaration sites. */
    protected static Set<String> caps(String... names) {
        return Set.of(names);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 512 ? s.substring(0, 512) + "...[truncated]" : s;
    }
}
