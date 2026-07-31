package org.wyrdsekai.core.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Federation-aware HomeProxy. Resolves the target DID's home
 * zone via a {@link ZoneDirectory}; if it's local, delegates to a local proxy;
 * if remote, POSTs the grant-request to the remote zone's
 * {@code /api/home/grant-requests} endpoint.
 *
 * <p>Intentionally synchronous + blocking — knocks are initiated from user
 * commands that expect an immediate acknowledgement. HTTP timeouts are short
 * by design; on failure we return a structured {@link Result.error}.</p>
 */
public final class FederatedHomeProxy implements HomeProxy {

    private static final Logger log = LoggerFactory.getLogger(FederatedHomeProxy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HomeProxy local;
    private final String localZoneId;
    private final ZoneDirectory directory;
    private final HttpClient http;
    private final Duration timeout;

    public FederatedHomeProxy(HomeProxy local, String localZoneId, ZoneDirectory directory) {
        this(local, localZoneId, directory,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            Duration.ofSeconds(8));
    }

    public FederatedHomeProxy(HomeProxy local, String localZoneId, ZoneDirectory directory,
                               HttpClient http, Duration timeout) {
        this.local = local;
        this.localZoneId = localZoneId;
        this.directory = directory;
        this.http = http;
        this.timeout = timeout;
    }

    @Override public Optional<String> resolveHomeZone(String did) {
        return directory.zoneOf(did);
    }

    @Override public Result knock(String requester, String ownerDid, String reason) {
        var targetZoneOpt = directory.zoneOf(ownerDid);
        if (targetZoneOpt.isEmpty()) {
            return Result.unknown(ownerDid);
        }
        var targetZone = targetZoneOpt.get();
        if (targetZone.equals(localZoneId)) {
            return local.knock(requester, ownerDid, reason);
        }
        // Remote knock.
        var httpBase = directory.httpBaseOf(targetZone).orElse(null);
        if (httpBase == null) {
            return Result.error("no http base for zone '" + targetZone + "'");
        }
        try {
            var body = MAPPER.writeValueAsString(Map.of(
                "requester", requester,
                "owner", ownerDid,
                "resource", "home://" + ownerDid + "/home-room",
                "capability", "use",
                "reason", reason == null ? "" : reason));
            var req = HttpRequest.newBuilder()
                .uri(URI.create(httpBase + "/api/home/grant-requests"))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                JsonNode node = MAPPER.readTree(resp.body());
                var id = node.path("id").asText(null);
                if (id == null) {
                    return Result.error("remote response missing id");
                }
                return Result.remote(id, targetZone,
                    "knock forwarded to zone " + targetZone);
            }
            return Result.error("remote " + targetZone + " returned HTTP " + resp.statusCode()
                + ": " + truncate(resp.body(), 200));
        } catch (Exception e) {
            log.warn("FederatedHomeProxy.knock {}→{} failed: {}",
                requester, ownerDid, e.getMessage());
            return Result.error("remote knock failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
