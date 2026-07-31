package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PeerBondSuggestionDetectorTest {

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(PeerBondSuggestionDetector.detect(null, null)).isEmpty();
        assertThat(PeerBondSuggestionDetector.detect(Map.of(), Set.of())).isEmpty();
    }

    @Test
    void subThresholdReturnsEmpty() {
        var counts = new HashMap<String, Integer>();
        counts.put("did:wyrd:peer-a", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD - 1);
        assertThat(PeerBondSuggestionDetector.detect(counts, Set.of())).isEmpty();
    }

    @Test
    void atThresholdEmitsInfo() {
        var counts = new HashMap<String, Integer>();
        counts.put("did:wyrd:peer-a", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD);
        var findings = PeerBondSuggestionDetector.detect(counts, Set.of());
        assertThat(findings).hasSize(1);
        var f = findings.get(0);
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.INFO);
        assertThat(f.key()).isEqualTo("peer_bond_suggest:did:wyrd:peer-a");
        // Non-coercive register — must be a question, not a directive.
        assertThat(f.message().toLowerCase()).contains("propose a peer bond?");
        assertThat(f.message().toLowerCase()).contains("not a flag");
    }

    @Test
    void alreadyBondedSkipped() {
        var counts = new HashMap<String, Integer>();
        counts.put("did:wyrd:peer-a", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD + 10);
        var bonded = Set.of("did:wyrd:peer-a");
        assertThat(PeerBondSuggestionDetector.detect(counts, bonded)).isEmpty();
    }

    @Test
    void multiplePeersOnlyEligibleFire() {
        var counts = new HashMap<String, Integer>();
        counts.put("did:wyrd:peer-a", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD);
        counts.put("did:wyrd:peer-b", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD - 5);
        counts.put("did:wyrd:peer-c", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD + 20);
        var bonded = Set.of("did:wyrd:peer-c"); // already bonded — skip
        var findings = PeerBondSuggestionDetector.detect(counts, bonded);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).key()).isEqualTo("peer_bond_suggest:did:wyrd:peer-a");
    }

    @Test
    void nullPeerDidSkipped() {
        var counts = new HashMap<String, Integer>();
        counts.put(null, PeerBondSuggestionDetector.SUGGESTION_THRESHOLD);
        counts.put("", PeerBondSuggestionDetector.SUGGESTION_THRESHOLD);
        assertThat(PeerBondSuggestionDetector.detect(counts, Set.of())).isEmpty();
    }
}
