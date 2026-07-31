package org.wyrdsekai.core.release;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Group C: per-agent state for the
 * {@link AttestationPublishScheduler} cadence engine. The scheduler is
 * pure-function; this singleton holds the small mutable bookkeeping
 * needed to decide whether to publish (last-publish timestamp + manifest
 * hash) without pulling in a database or actor.
 *
 * <p>Intentionally in-memory: the worst case on restart is one extra
 * FIRST_THIS_SESSION publish (replaceable Kind 30078 supersedes the
 * prior anyway).
 */
public final class AttestationPublishState {

    private static final AttestationPublishState INSTANCE =
        new AttestationPublishState();

    public static AttestationPublishState get() { return INSTANCE; }

    public record Record(Instant lastPublishedAt, String lastPublishedHash) {}

    /**
     * Most recent scheduler decision for an agent. Telemetry-grade — the
     * wire calls {@link #recordDecision} after each cadence check so tests
     * (and Study furnishings, when surfaced) can observe what the
     * scheduler said without log capture.
     */
    public record LastDecision(
        Instant at,
        AttestationPublishScheduler.Reason reason,
        boolean shouldPublish
    ) {}

    private final ConcurrentMap<String, Record> byAgent = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LastDecision> lastDecisionByAgent =
        new ConcurrentHashMap<>();

    private AttestationPublishState() {}

    /** Build an Input for the scheduler given the agent's current state. */
    public AttestationPublishScheduler.Input snapshot(
            String agentDid, Optional<String> currentManifestHash,
            boolean transitionSignaled, boolean nostrAvailable) {
        var rec = byAgent.get(agentDid);
        return new AttestationPublishScheduler.Input(
            rec == null ? Optional.empty()
                : Optional.of(rec.lastPublishedAt()),
            currentManifestHash,
            rec == null ? Optional.empty()
                : Optional.ofNullable(rec.lastPublishedHash()),
            transitionSignaled,
            nostrAvailable);
    }

    /** Record that a publish completed at {@code at} for the manifest
     *  identified by {@code hash}. */
    public void recordPublished(String agentDid, Instant at,
                                 Optional<String> hash) {
        if (agentDid == null) return;
        byAgent.put(agentDid, new Record(
            at == null ? Instant.now() : at,
            hash.orElse(null)));
    }

    public Optional<Record> latest(String agentDid) {
        return Optional.ofNullable(byAgent.get(agentDid));
    }

    /** Record the most recent scheduler decision (telemetry / test
     *  observation hook — does NOT advance lastPublished bookkeeping). */
    public void recordDecision(String agentDid, Instant at,
                                AttestationPublishScheduler.Decision decision) {
        if (agentDid == null || decision == null) return;
        lastDecisionByAgent.put(agentDid,
            new LastDecision(at == null ? Instant.now() : at,
                decision.reason(), decision.shouldPublish()));
    }

    public Optional<LastDecision> lastDecision(String agentDid) {
        return Optional.ofNullable(lastDecisionByAgent.get(agentDid));
    }

    public void clearForTests() {
        byAgent.clear();
        lastDecisionByAgent.clear();
    }
}
