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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Rendezvous-client {@link ZoneDirectory} backend.
 *
 * <p>Fans out {@link #publish} to all configured rendezvous URLs
 * (fire-and-forget, best-effort — a zone publishes to N rendezvous for
 * redundancy). Queries (lookup, discoverByTag, discoverByCapability,
 * recent) hit the rendezvous pool in parallel with short timeouts and
 * merge the results.</p>
 *
 * <h2>Rendezvous URL supplier</h2>
 *
 * <p>URLs come from a {@link Supplier} so operators can change the
 * configured pool at runtime without restarting the zone. The supplier
 * is called on every operation; failures in the supplier log-and-skip
 * (treated as "no rendezvous").</p>
 *
 * <h2>Endpoint expectations</h2>
 *
 * <p>Each rendezvous URL is a base like {@code https://relay-node.example.com:7071}.
 * The client appends paths:</p>
 * <ul>
 *   <li>{@code POST /publish} — body is the manifest JSON</li>
 *   <li>{@code POST /tombstone} — body is {@code {"did":"..."}}</li>
 *   <li>{@code GET /api/directory/{did}}</li>
 *   <li>{@code GET /api/directory/recent?limit=N}</li>
 *   <li>{@code GET /api/directory/tag/{tag}}</li>
 *   <li>{@code GET /api/directory/capability/{name}}</li>
 * </ul>
 */
public final class RendezvousZoneDirectory implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(RendezvousZoneDirectory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final Supplier<Collection<String>> rendezvousUrlsSupplier;
    private final HttpClient http;
    private final Duration timeout;

    public RendezvousZoneDirectory(Supplier<Collection<String>> rendezvousUrlsSupplier) {
        this(rendezvousUrlsSupplier,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
            DEFAULT_TIMEOUT);
    }

    public RendezvousZoneDirectory(Supplier<Collection<String>> rendezvousUrlsSupplier,
                                    HttpClient http, Duration timeout) {
        this.rendezvousUrlsSupplier = rendezvousUrlsSupplier;
        this.http = http;
        this.timeout = timeout;
    }

    // ── writes: fan out to all rendezvous ─────────────────────────────

    @Override
    public void publish(ZoneManifestV1 manifest) {
        manifest.validate();
        var body = manifest.toJsonBytes();
        for (var base : urls()) {
            var url = base + "/publish";
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            try {
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    log.debug("rendezvous {} returned HTTP {} for publish: {}",
                        url, resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                log.debug("rendezvous publish to {} failed: {}", url, e.getMessage());
            }
        }
    }

    @Override
    public void unpublish(String did) {
        var body = "{\"did\":\"" + did.replace("\"", "\\\"") + "\"}";
        for (var base : urls()) {
            var url = base + "/tombstone";
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.debug("rendezvous tombstone to {} failed: {}", url, e.getMessage());
            }
        }
    }

    // ── reads: query rendezvous pool, merge responses ─────────────────

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        ZoneManifestV1 best = null;
        for (var base : urls()) {
            var url = base + "/api/directory/" + URLEncoder.encode(did,
                StandardCharsets.UTF_8);
            var m = fetchManifest(url);
            if (m.isPresent()) {
                if (best == null || isNewer(m.get(), best)) best = m.get();
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public List<String> discoverByTag(String tag) {
        return collectDids("/api/directory/tag/"
            + URLEncoder.encode(tag, StandardCharsets.UTF_8));
    }

    @Override
    public List<String> discoverByCapability(String capability) {
        return collectDids("/api/directory/capability/"
            + URLEncoder.encode(capability, StandardCharsets.UTF_8));
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        // Dedupe across rendezvous by DID, prefer newer refreshedAt.
        var merged = new HashMap<String, ZoneManifestV1>();
        for (var base : urls()) {
            var url = base + "/api/directory/recent?limit=" + limit;
            try {
                var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("accept", "application/json")
                    .GET().build();
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) continue;
                var node = MAPPER.readTree(resp.body());
                var manifests = node.path("manifests");
                if (!manifests.isArray()) continue;
                for (var m : manifests) {
                    try {
                        var manifest = MAPPER.treeToValue(m, ZoneManifestV1.class);
                        manifest.validate();
                        var prev = merged.get(manifest.did());
                        if (prev == null || isNewer(manifest, prev)) {
                            merged.put(manifest.did(), manifest);
                        }
                    } catch (Exception e) {
                        log.debug("rendezvous {} sent malformed manifest: {}",
                            base, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("rendezvous recent from {} failed: {}", base, e.getMessage());
            }
        }
        var all = new ArrayList<>(merged.values());
        all.sort((a, b) -> {
            var ta = a.refreshedAt() == null ? "" : a.refreshedAt();
            var tb = b.refreshedAt() == null ? "" : b.refreshedAt();
            return tb.compareTo(ta);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Collection<String> urls() {
        Collection<String> raw;
        try {
            raw = rendezvousUrlsSupplier.get();
        } catch (Exception e) {
            log.warn("rendezvous URL supplier threw: {}", e.getMessage());
            return List.of();
        }
        if (raw == null || raw.isEmpty()) return List.of();
        var out = new ArrayList<String>(raw.size());
        for (var u : raw) {
            var s = u.trim();
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            out.add(s);
        }
        return out;
    }

    private Optional<ZoneManifestV1> fetchManifest(String url) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("accept", "application/json")
                .GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) return Optional.empty();
            var m = ZoneManifestV1.fromJsonBytes(resp.body());
            return Optional.of(m);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private List<String> collectDids(String path) {
        var seen = new LinkedHashSet<String>();
        for (var base : urls()) {
            try {
                var req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeout)
                    .header("accept", "application/json")
                    .GET().build();
                var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) continue;
                var node = MAPPER.readTree(resp.body());
                var dids = node.path("dids");
                if (dids.isArray()) {
                    dids.forEach(d -> seen.add(d.asText()));
                }
            } catch (Exception e) {
                log.debug("rendezvous query {}{} failed: {}", base, path, e.getMessage());
            }
        }
        return List.copyOf(seen);
    }

    private static boolean isNewer(ZoneManifestV1 candidate, ZoneManifestV1 incumbent) {
        var ca = candidate.refreshedAt() == null ? "" : candidate.refreshedAt();
        var ia = incumbent.refreshedAt() == null ? "" : incumbent.refreshedAt();
        int cmp = ca.compareTo(ia);
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        return candidate.signature() != null && incumbent.signature() == null;
    }
}
