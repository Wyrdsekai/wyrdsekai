package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;

import java.util.Map;
import java.util.Optional;

/**
 * Cross-zone Home visibility ( — the son-at-college scenario).
 *
 * <p>When Alice (zone alpha) says {@code visit bob}, the proxy must find
 * which zone bob's Home lives in and route the knock there. Locally-owned
 * Homes are handled by the normal in-process HomeClient; remote Homes go
 * through the Between layer (federation agreements, NATS RPC or HTTP).</p>
 *
 * <p>This file carries the abstractions. A default local-only impl is
 * wired at startup; Between-enabled deployments replace it with
 * {@code FederatedHomeProxy} which calls the target zone's REST API.</p>
 */
public interface HomeProxy {

    /**
     * Which zone hosts this DID's Home? Returns empty when the resolver
     * cannot answer (unknown DID). The caller should treat that as
     * "assume local" only with extreme caution.
     */
    Optional<String> resolveHomeZone(String did);

    /**
     * Create a grant-request for {@code use} on {@code home://owner/home-room}.
     * When the owner is local, issues against the local HomeClient. When
     * remote, dispatches via the Between layer; returns the request id.
     */
    Result knock(String requester, String ownerDid, String reason);

    /** Process-wide singleton installed at startup. Nullable until set. */
    final class Holder {
        private static volatile HomeProxy instance;
        private Holder() {}
        public static void set(HomeProxy proxy) { instance = proxy; }
        public static HomeProxy get() { return instance; }
    }

    /** Outcome of a {@link #knock} call. Keep shape stable across local/remote. */
    record Result(String requestId, String homeZone, boolean remote, String note) {
        public boolean ok() { return requestId != null; }
        public static Result local(String requestId, String zone) {
            return new Result(requestId, zone, false, null);
        }
        public static Result remote(String requestId, String zone, String note) {
            return new Result(requestId, zone, true, note);
        }
        public static Result unknown(String did) {
            return new Result(null, null, false,
                "no zone on record for " + did);
        }
        public static Result error(String reason) {
            return new Result(null, null, false, reason);
        }
    }

    /**
     * Local-only default: treats every DID as living in {@code localZoneId}.
     * Suitable for single-zone installations + tests.
     */
    final class Local implements HomeProxy {
        private static final Logger log = LoggerFactory.getLogger(Local.class);
        private final HomeClient homeClient;
        private final String localZoneId;

        public Local(HomeClient homeClient, String localZoneId) {
            this.homeClient = homeClient;
            this.localZoneId = localZoneId;
        }

        @Override public Optional<String> resolveHomeZone(String did) {
            return Optional.ofNullable(localZoneId);
        }

        @Override public Result knock(String requester, String ownerDid, String reason) {
            if (homeClient == null || ownerDid == null) {
                return Result.error("home client unavailable");
            }
            try {
                var resource = ResourceUri.of(ownerDid, ResourceTypeRegistry.HOME_ROOM);
                var req = GrantRequest.create(requester, ownerDid, resource,
                    Capability.use, Map.of(), reason);
                var stored = homeClient.createRequest(req);
                return Result.local(stored.id(), localZoneId);
            } catch (Exception e) {
                log.warn("HomeProxy.Local.knock({}→{}): {}", requester, ownerDid, e.getMessage());
                return Result.error(e.getMessage());
            }
        }
    }
}
