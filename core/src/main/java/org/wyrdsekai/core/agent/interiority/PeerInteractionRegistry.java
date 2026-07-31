package org.wyrdsekai.core.agent.interiority;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arc 3 — in-memory registry of significant agent-to-
 * agent interactions, plus a snapshot of which peers the agent has active
 * peer bonds with. Read by {@link PeerBondSuggestionDetector} from
 * {@link ChronicleService#detectAll}.
 *
 * <p>v1 scope: in-process, fail-soft, no persistence. Restart drops the
 * window — acceptable because the threshold (15 interactions in 14 days)
 * is gentle and replenishes quickly under normal collaboration. A future
 * migration can back this with the {@code conversation_turns} SQL table
 * once the agent-to-agent column lands. For now the registry is a hook
 * for call-sites that observe peer interactions to feed counts in.</p>
 *
 * <p>Singleton via {@link #get()} to keep wiring light from the chronicle
 * read path; the registry has no dependencies of its own.</p>
 */
public final class PeerInteractionRegistry {

    private static final PeerInteractionRegistry INSTANCE = new PeerInteractionRegistry();

    /** selfDid → (peerDid → arrival timestamps, monotonic). */
    private final Map<String, Map<String, Deque<Instant>>> interactions = new ConcurrentHashMap<>();

    /** selfDid → set of peer DIDs the agent already has an active peer bond with. */
    private final Map<String, Set<String>> activelyBonded = new ConcurrentHashMap<>();

    private PeerInteractionRegistry() {}

    public static PeerInteractionRegistry get() {
        return INSTANCE;
    }

    /**
     * Record a significant interaction. "Significant" is defined by the
     * caller (e.g. tell exchange of meaningful length, joint task, scene
     * with focal=self + participant=peer). Implementation just appends a
     * timestamp; the windowed count drops naturally as time advances.
     */
    public void noteInteraction(String selfDid, String peerDid, Instant at) {
        if (selfDid == null || peerDid == null || selfDid.equals(peerDid)) return;
        var ts = at == null ? Instant.now() : at;
        interactions
            .computeIfAbsent(selfDid, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(peerDid, k -> new ArrayDeque<>())
            .addLast(ts);
    }

    /**
     * Mark a peer-bond as active for the (self, peer) pair, so the
     * suggestion detector skips it. The bond layer ({@code BondStore})
     * should also drive this — keeping a flat in-memory mirror avoids
     * an SQL roundtrip per chronicle synthesis.
     */
    public void markBonded(String selfDid, String peerDid) {
        if (selfDid == null || peerDid == null) return;
        activelyBonded
            .computeIfAbsent(selfDid, k -> ConcurrentHashMap.newKeySet())
            .add(peerDid);
    }

    /** Drop the bonded marker (e.g. severance + mourning end). */
    public void clearBonded(String selfDid, String peerDid) {
        if (selfDid == null || peerDid == null) return;
        var s = activelyBonded.get(selfDid);
        if (s != null) s.remove(peerDid);
    }

    /**
     * Return interaction counts per peer over the trailing window.
     * Counts are computed lazily and the deques pruned of entries older
     * than the window — keeps memory bounded under long-running zones.
     */
    public Map<String, Integer> countsByPeerInWindow(String selfDid, int windowDays) {
        if (selfDid == null) return Map.of();
        var perPeer = interactions.get(selfDid);
        if (perPeer == null || perPeer.isEmpty()) return Map.of();
        var cutoff = Instant.now().minus(Duration.ofDays(windowDays));
        var out = new HashMap<String, Integer>();
        for (var e : perPeer.entrySet()) {
            var dq = e.getValue();
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) {
                dq.pollFirst();
            }
            if (!dq.isEmpty()) out.put(e.getKey(), dq.size());
        }
        return out;
    }

    /** Snapshot of peer DIDs the agent already has an active bond with. */
    public Set<String> activelyBondedPeers(String selfDid) {
        if (selfDid == null) return Set.of();
        var s = activelyBonded.get(selfDid);
        return s == null ? Set.of() : new HashSet<>(s);
    }

    /** Test-only reset. */
    public void clearAllForTests() {
        interactions.clear();
        activelyBonded.clear();
    }

    /** Test-only: bulk snapshot (defensive copy). */
    Collection<String> peers(String selfDid) {
        var p = interactions.get(selfDid);
        return p == null ? Set.of() : new HashSet<>(p.keySet());
    }
}
