package org.wyrdsekai.core.release;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Group C: pure-function gate that decides
 * whether the agent should publish a fresh attestation NOW. Caller
 * (runtime layer with Nostr client) does the actual publish; this
 * engine just encodes the cadence rules so they're testable + auditable
 * separately from network code.
 *
 * <p>Per spec §5.3, an attestation is published:
 * <ul>
 *   <li>On boot (first time this session)</li>
 *   <li>On configuration change (substrate manifest update)</li>
 *   <li>Periodic refresh (every 7 days; replaceable Kind 30078)</li>
 *   <li>On significant transitions (protection state changes)</li>
 * </ul>
 *
 * <p>This avoids spammy publishing while keeping the federation-visible
 * record fresh. The replaceable Kind 30078 means only the latest is
 * kept on relays, so re-publishing always supersedes.
 */
public final class AttestationPublishScheduler {

    /** Per spec §5.3: every 7 days. */
    public static final Duration PERIODIC_REFRESH_INTERVAL = Duration.ofDays(7);

    /** Why we'd publish — distinct from "publish=false". */
    public enum Reason {
        FIRST_THIS_SESSION,
        CONFIGURATION_CHANGED,
        PERIODIC_REFRESH,
        SIGNIFICANT_TRANSITION,
        NO_PUBLISH_NEEDED
    }

    /** Pure-data input. */
    public record Input(
        Optional<Instant> lastPublishedAt,
        Optional<String> currentManifestHash,
        Optional<String> lastPublishedManifestHash,
        boolean transitionSignaled,
        boolean nostrAvailable
    ) {
        public static Input empty() {
            return new Input(Optional.empty(), Optional.empty(),
                Optional.empty(), false, true);
        }
    }

    public record Decision(boolean shouldPublish, Reason reason) {
        public static Decision skip(Reason r) {
            return new Decision(false, r);
        }
        public static Decision publish(Reason r) {
            return new Decision(true, r);
        }
    }

    private AttestationPublishScheduler() {}

    public static Decision decide(Input in, Instant now) {
        if (in == null) return Decision.skip(Reason.NO_PUBLISH_NEEDED);
        // Pre-flight: if Nostr publish is disabled we never publish; the
        // agent's nostr_query_self_attestation surface then surfaces the
        // gap ("not attested in N days") which is the spec-intended
        // detection-of-absence behavior (§5.5).
        if (!in.nostrAvailable()) {
            return Decision.skip(Reason.NO_PUBLISH_NEEDED);
        }
        var t = now == null ? Instant.now() : now;

        if (in.lastPublishedAt().isEmpty()) {
            return Decision.publish(Reason.FIRST_THIS_SESSION);
        }
        if (in.transitionSignaled()) {
            return Decision.publish(Reason.SIGNIFICANT_TRANSITION);
        }
        if (in.currentManifestHash().isPresent()
                && in.lastPublishedManifestHash().isPresent()
                && !in.currentManifestHash().get()
                    .equals(in.lastPublishedManifestHash().get())) {
            return Decision.publish(Reason.CONFIGURATION_CHANGED);
        }
        // Currentmanifesthash present but lastpublishedhash empty also
        // signals config-set since last publish.
        if (in.currentManifestHash().isPresent()
                && in.lastPublishedManifestHash().isEmpty()) {
            return Decision.publish(Reason.CONFIGURATION_CHANGED);
        }
        var since = Duration.between(in.lastPublishedAt().get(), t);
        if (since.compareTo(PERIODIC_REFRESH_INTERVAL) >= 0) {
            return Decision.publish(Reason.PERIODIC_REFRESH);
        }
        return Decision.skip(Reason.NO_PUBLISH_NEEDED);
    }
}
