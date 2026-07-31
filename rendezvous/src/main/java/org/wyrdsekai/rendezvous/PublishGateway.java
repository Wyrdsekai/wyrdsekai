package org.wyrdsekai.rendezvous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Admission control for manifest publishes to the rendezvous.
 *
 * <p>Three defensive checks, in order:</p>
 *
 * <ol>
 *   <li><b>Validation.</b> Manifest must parse + validate per
 *       {@link ZoneManifestV1#validate()} (schema version, DID form,
 *       zoneLabel charset, size caps on tagline/description).</li>
 *   <li><b>Rate limit.</b> Per-DID minimum interval between publishes
 *       (default 1/min). A zone that republishes faster is silently
 *       rejected — not a hard error for transient over-eager clients,
 *       but it throttles mis-configured loops and abuse.</li>
 *   <li><b>Capacity.</b> Delegated to {@link DirectoryStore#publish}
 *       which evicts LRU when full.</li>
 * </ol>
 *
 * <p><b>Signatures deliberately not verified here.</b> Directory
 * trust is at the federation-handshake layer, not at publish. A lying
 * rendezvous (or publisher) can only cause peers to attempt federation
 * with a wrong endpoint — which fails at handshake because the
 * attacker lacks the target's keypair.</p>
 */
public final class PublishGateway {

    private static final Logger log = LoggerFactory.getLogger(PublishGateway.class);

    public enum Result { ACCEPTED, RATE_LIMITED, REJECTED_INVALID, REJECTED_FULL }

    private final DirectoryStore store;
    private final long publishMinIntervalMs;
    private final ConcurrentMap<String, Long> lastPublishAt = new ConcurrentHashMap<>();

    public PublishGateway(DirectoryStore store, long publishMinIntervalMs) {
        this.store = store;
        this.publishMinIntervalMs = publishMinIntervalMs;
    }

    /**
     * Attempt to publish {@code manifest}.
     *
     * @param sourceIp caller IP for logging; not used for rate limiting
     *                 (we rate-limit by DID because an attacker could
     *                 rotate IPs trivially, whereas DID rotation is costly)
     */
    public Result publish(ZoneManifestV1 manifest, String sourceIp) {
        try {
            manifest.validate();
        } catch (IllegalStateException e) {
            log.debug("publish rejected (invalid) from {}: {}", sourceIp, e.getMessage());
            return Result.REJECTED_INVALID;
        }

        long now = System.currentTimeMillis();
        var prior = lastPublishAt.get(manifest.did());
        if (prior != null && (now - prior) < publishMinIntervalMs) {
            log.debug("publish rejected (rate limit) for {} from {}: {}ms since last",
                manifest.did(), sourceIp, now - prior);
            return Result.RATE_LIMITED;
        }

        try {
            store.publish(manifest);
        } catch (IllegalStateException e) {
            log.warn("store rejected publish for {}: {}", manifest.did(), e.getMessage());
            return Result.REJECTED_FULL;
        }

        lastPublishAt.put(manifest.did(), now);
        log.debug("published manifest for {} from {} (total={})",
            manifest.did(), sourceIp, store.size());
        return Result.ACCEPTED;
    }

    /** Test/diagnostic — clear rate-limit state. */
    public void resetRateLimits() {
        lastPublishAt.clear();
    }

    /** Test/diagnostic — count of DIDs tracked for rate limiting. */
    public int trackedDids() {
        return lastPublishAt.size();
    }
}
