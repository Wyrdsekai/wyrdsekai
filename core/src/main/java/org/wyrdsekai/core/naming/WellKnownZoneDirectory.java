package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * URL-addressed {@link ZoneDirectory} backend.
 *
 * <p>This is the "authoritative fetch" path: given a URL
 * ({@code https://alice.example.com}) or a WebFinger handle
 * ({@code acct:kitchen@alice.example.com}), resolve to the zone's
 * canonical manifest at {@code /.well-known/wyrd-zone}.</p>
 *
 * <p>Unlike rendezvous (which caches manifests from many zones) or
 * federated-walk (which pulls partners' caches), this backend goes
 * straight to the zone's own server for its own manifest. There's no
 * fresher source of truth — it's the wyrdsekai analog of an
 * authoritative DNS query.</p>
 *
 * <h2>Scope</h2>
 *
 * <p>Does NOT index all zones — it only knows what's been looked up
 * through it. Callers resolve a URL/handle once via
 * {@link #lookupUrl(String)}, and the result is cached by DID so
 * subsequent {@link #lookup(String)} calls succeed without a second
 * network hop (until the TTL expires).</p>
 *
 * <p>{@link #recent(int)} and {@link #discoverByTag(String)} return only
 * entries this backend has previously fetched. For global browsing,
 * compose with a rendezvous backend.</p>
 *
 * <h2>WebFinger</h2>
 *
 * <p>Given {@code acct:kitchen@alice.example.com}, fetches
 * {@code https://alice.example.com/.well-known/webfinger?resource=…}
 * first to discover the {@code self} link, then fetches that link.
 * RFC 7033 compliant.</p>
 */
public final class WellKnownZoneDirectory implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(WellKnownZoneDirectory.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final ConcurrentMap<String, ZoneManifestV1> byDid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> fetchedAt = new ConcurrentHashMap<>();
    /** URL that each DID was resolved from, so we can re-fetch on lookup. */
    private final ConcurrentMap<String, String> urlByDid = new ConcurrentHashMap<>();

    public WellKnownZoneDirectory() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    /** Test-friendly constructor — inject a custom HttpClient. */
    public WellKnownZoneDirectory(HttpClient http) {
        this.http = http;
    }

    /**
     * Fetch and cache the manifest for an HTTPS URL
     * ({@code https://host[:port]}; we append {@code /.well-known/wyrd-zone}).
     *
     * @return the manifest, or empty if the remote didn't respond or
     *         returned a malformed response
     */
    public Optional<ZoneManifestV1> lookupUrl(String baseUrl) {
        var url = normalizeBaseUrl(baseUrl) + "/.well-known/wyrd-zone";
        return fetchManifest(url);
    }

    /**
     * Resolve a WebFinger {@code acct:label@host} handle to the zone's
     * manifest via RFC 7033 two-step lookup.
     */
    public Optional<ZoneManifestV1> lookupAcct(String acctHandle) {
        var handle = acctHandle;
        if (!handle.startsWith("acct:")) handle = "acct:" + handle;
        var at = handle.indexOf('@');
        if (at < 0) {
            log.warn("WebFinger: malformed handle (no @): {}", acctHandle);
            return Optional.empty();
        }
        var host = handle.substring(at + 1);
        var wfUrl = "https://" + host + "/.well-known/webfinger?resource="
            + URLEncoder.encode(handle, StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create(wfUrl))
            .timeout(REQUEST_TIMEOUT)
            .header("accept", "application/jrd+json,application/json")
            .GET().build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.debug("WebFinger: {} returned HTTP {}", wfUrl, resp.statusCode());
                return Optional.empty();
            }
            var node = MAPPER.readTree(resp.body());
            var links = node.path("links");
            for (var link : links) {
                if ("self".equals(link.path("rel").asText())
                        && link.has("href")) {
                    return fetchManifest(link.get("href").asText());
                }
            }
            log.debug("WebFinger: no self link in response for {}", acctHandle);
            return Optional.empty();
        } catch (Exception e) {
            log.debug("WebFinger fetch failed for {}: {} ({})",
                acctHandle, e.getMessage(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<ZoneManifestV1> fetchManifest(String url) {
        var req = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("accept", "application/json")
            .GET().build();
        try {
            var resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                log.debug(".well-known fetch {}: HTTP {}", url, resp.statusCode());
                return Optional.empty();
            }
            var manifest = ZoneManifestV1.fromJsonBytes(resp.body());
            byDid.put(manifest.did(), manifest);
            urlByDid.put(manifest.did(), url);
            fetchedAt.put(manifest.did(), System.currentTimeMillis());
            log.debug(".well-known: cached {} from {}", manifest.did(), url);
            return Optional.of(manifest);
        } catch (IllegalStateException e) {
            log.warn(".well-known: malformed manifest from {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.debug(".well-known fetch {} failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static String normalizeBaseUrl(String url) {
        var s = url.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        // Strip trailing slash + any /.well-known/... suffix the user already typed.
        if (s.endsWith("/.well-known/wyrd-zone")) {
            s = s.substring(0, s.length() - "/.well-known/wyrd-zone".length());
        } else if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── ZoneDirectory interface ────────────────────────────────────────

    /**
     * Publishing is a no-op — each zone self-publishes by serving
     * {@code /.well-known/wyrd-zone}. This backend is read-only for
     * other zones' manifests.
     */
    @Override
    public void publish(ZoneManifestV1 manifest) {
        // Intentional no-op. See Javadoc on the class.
    }

    /**
     * Tombstoning is a no-op for the same reason. Remove local cache
     * entry only.
     */
    @Override
    public void unpublish(String did) {
        byDid.remove(did);
        urlByDid.remove(did);
        fetchedAt.remove(did);
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        // Serve from cache. Caller who wants a fresh fetch should use
        // lookupUrl / lookupAcct directly.
        return Optional.ofNullable(byDid.get(did));
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var out = new ArrayList<String>();
        for (var m : byDid.values()) {
            if (m.tags() != null && m.tags().contains(tag)) {
                out.add(m.did());
            }
        }
        return out;
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        var all = new ArrayList<>(byDid.values());
        all.sort((a, b) -> {
            var ta = fetchedAt.getOrDefault(a.did(), 0L);
            var tb = fetchedAt.getOrDefault(b.did(), 0L);
            return Long.compare(tb, ta);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** Test/diagnostic — current cache size. */
    public int cacheSize() {
        return byDid.size();
    }
}
