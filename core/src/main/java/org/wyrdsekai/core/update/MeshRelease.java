package org.wyrdsekai.core.update;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nodes update each other through the mesh: when one machine in the household runs a newer release,
 * the others follow it. This is the decision, with no network in it.
 *
 * <p>The heartbeats already carried every peer's version; nothing read them (audit, 2026-10-03;
 * operator: "shouldn't this be happening? this saves on all kinds of things"). A peer's version becomes
 * a candidate once it is a release (never a dev build), newer than what runs here, and the peer has
 * been seen at it, connected, for the stability delay. The steward's node ({@code primary}) goes last:
 * it follows only when every connected peer that reports a version is already there. A peer that
 * leaves takes its candidacy with it. The installer is then fetched from that peer, and checked
 * against the release's own checksums, by {@code wyrd update now --from}.
 */
public final class MeshRelease {

    /** A household peer as the Between sees it. */
    public record Peer(String nodeId, String appVersion, boolean connected, String httpUrl) {}

    /** The release this node should follow, and the peer to take it from. */
    public record Candidate(String version, String nodeId, String httpUrl, Instant stableSince) {}

    private record Seen(String nodeId, String httpUrl, Instant firstSeen) {}

    private final Map<String, Seen> seen = new LinkedHashMap<>();   // version → when a peer was first seen at it
    private final java.util.Set<String> failed = new java.util.HashSet<>();
    private List<Peer> peers = List.of();

    /** The latest view of the household, from the heartbeats. */
    public synchronized void observe(Collection<Peer> current, Instant now) {
        peers = List.copyOf(current);
        var live = new java.util.HashSet<String>();
        for (var p : current) {
            if (!p.connected() || p.appVersion() == null || !ReleaseCheck.isRelease(p.appVersion())) continue;
            live.add(p.appVersion());
            seen.merge(p.appVersion(), new Seen(p.nodeId(), p.httpUrl(), now), (old, fresh) ->
                // The first peer seen at a version keeps the clock; if that peer is gone, the next one starts it over.
                current.stream().anyMatch(q -> q.connected() && q.nodeId().equals(old.nodeId()) && p.appVersion().equals(q.appVersion()))
                    ? old : fresh);
        }
        seen.keySet().retainAll(live);
    }

    /** A version that failed to install here is not followed again this run. */
    public synchronized void markFailed(String version) {
        if (version != null) failed.add(version);
    }

    /**
     * The release to follow now, if any: the highest stable release among the peers that is newer
     * than {@code installed}; for a primary node, only when every connected peer with a version is
     * at it or beyond.
     */
    public synchronized Optional<Candidate> candidate(String installed, boolean primary, Duration stabilityDelay, Instant now) {
        Candidate best = null;
        for (var e : seen.entrySet()) {
            var version = e.getKey();
            var s = e.getValue();
            if (failed.contains(version)) continue;
            if (!ReleaseCheck.isNewer(installed, version)) continue;
            if (Duration.between(s.firstSeen(), now).compareTo(stabilityDelay) < 0) continue;
            if (primary && !everyPeerAtOrBeyond(version)) continue;
            if (best == null || ReleaseManifest.compareVersions(version, best.version()) > 0) {
                best = new Candidate(version, s.nodeId(), s.httpUrl(), s.firstSeen());
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean everyPeerAtOrBeyond(String version) {
        return peers.stream()
            .filter(Peer::connected)
            .filter(p -> p.appVersion() != null && !p.appVersion().isBlank())
            .allMatch(p -> ReleaseCheck.isRelease(p.appVersion()) && ReleaseManifest.compareVersions(p.appVersion(), version) >= 0);
    }

    /** For the status line: the peers and what each runs. */
    public synchronized List<Peer> peers() { return peers; }
}
