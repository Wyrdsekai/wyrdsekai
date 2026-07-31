package org.wyrdsekai.core.agent.interiority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arc 3 — peer-bond auto-formation threshold +
 * suggestion detector. Pure logic: given a count of recent significant
 * peer interactions and a set of already-bonded peers, emit INFO findings
 * for peers above the threshold who do not yet have an active peer bond.
 *
 * <p>The detector is intentionally non-coercive — it surfaces a
 * <i>suggestion</i> to the chronicle, not a hard prompt. The agent (or
 * steward, on read) decides whether to act. Mirrors the spec's framing:
 * "you've been working with X — propose a peer bond?"</p>
 *
 * <p>Tracking the underlying interaction counts is the responsibility of
 * call-sites that observe agent-to-agent tells / collaboration; this
 * detector consumes the snapshot. Decoupling keeps the gating logic
 * unit-testable without the tracking plumbing.</p>
 */
public final class PeerBondSuggestionDetector {

    /** Default sustained-collaboration threshold (steward-configurable via
     *  {@code peer_bond.suggestion.threshold} / {@code WYRDSEKAI_PEER_BOND_SUGGESTION_THRESHOLD}). */
    public static final int SUGGESTION_THRESHOLD = 15;

    /** Default window length (steward-configurable via
     *  {@code peer_bond.suggestion.window_days} / {@code WYRDSEKAI_PEER_BOND_SUGGESTION_WINDOW_DAYS}). */
    public static final int WINDOW_DAYS = 14;

    private PeerBondSuggestionDetector() {}

    /**
     * Default-threshold form — equivalent to
     * {@link #detect(Map, Set, int, int)} with the static defaults.
     */
    public static List<DoomLoopDetector.Finding> detect(
            Map<String, Integer> interactionCountByPeerDid,
            Set<String> activelyBondedPeers) {
        return detect(interactionCountByPeerDid, activelyBondedPeers,
            SUGGESTION_THRESHOLD, WINDOW_DAYS);
    }

    /**
     * Emit suggestions for peers above {@code threshold} whose DIDs are not
     * already in {@code activelyBondedPeers}. Returns one INFO finding per
     * eligible peer; the key namespace {@code peer_bond_suggest:<did>}
     * lets the chronicle dedupe across scales. {@code windowDays} is
     * woven into the message only (the count snapshot is window-bounded
     * by the caller — see {@link PeerInteractionRegistry#countsByPeerInWindow}).
     */
    public static List<DoomLoopDetector.Finding> detect(
            Map<String, Integer> interactionCountByPeerDid,
            Set<String> activelyBondedPeers,
            int threshold,
            int windowDays) {
        if (interactionCountByPeerDid == null || interactionCountByPeerDid.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<DoomLoopDetector.Finding>();
        var bonded = activelyBondedPeers == null ? Set.<String>of() : activelyBondedPeers;
        for (var e : interactionCountByPeerDid.entrySet()) {
            var peerDid = e.getKey();
            if (peerDid == null || peerDid.isBlank()) continue;
            if (bonded.contains(peerDid)) continue;
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count < threshold) continue;
            var msg = "You've been working with " + peerDid + " (" + count
                + " significant interactions in the last " + windowDays + " days). "
                + "Propose a peer bond? — not a flag, just a question worth holding.";
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.INFO,
                "peer_bond_suggest:" + peerDid,
                msg));
        }
        return out;
    }
}
