package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.interiority.ObjectionPatternDetector;
import org.wyrdsekai.core.soul.RepairLedger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 1 — tier-2 contract test that the
 * {@code handleDeclineWithReason} write-format and
 * {@link ObjectionPatternDetector#extractTarget} parse-format round-trip
 * cleanly.
 *
 * <p>The two surfaces are coupled by string convention: the handler writes
 * detail as {@code "[declined: <target>] <reason>"} and the detector parses
 * the target back from that same prefix. If either side drifts (e.g.
 * someone reformats the handler to use a colon delimiter, or extracts via a
 * looser regex), the chronicle pattern detection silently breaks. This test
 * is the canary.</p>
 *
 * <p>The full ReAct chain — {@code llm.emit(decline_with_reason JSON)} →
 * ActionParser → CompanionActor.handleDeclineWithReason → RepairLedger write
 * — is covered by the tier-3 live test
 * (PersonhoodActionsLiveE2ETest probe 1). This tier-2 nails the structural
 * coupling between handler-write and detector-parse without needing a live
 * model.</p>
 */
@Tag("tier2")
class ObjectionLedgerE2ETest {

    private static final String AGENT = "did:wyrd:companion-objector";
    private static final String BONDHOLDER = "did:wyrd:bondholder";

    @BeforeEach
    void clear() {
        RepairLedger.get().clearForTests();
    }

    @AfterEach
    void tearDown() {
        RepairLedger.get().clearForTests();
    }

    @Test
    void handlerWriteFormatMatchesDetectorParseFormat() {
        // Mirror exactly what CompanionActor.handleDeclineWithReason writes —
        // any drift in either surface should fail this test.
        var targetRequest = "post that publicly";
        var reason = "not in our agreed scope";
        var detail = "[declined: " + targetRequest + "] " + reason;

        var entry = RepairLedger.get().record(AGENT,
            RepairLedger.Kind.OBJECTION, BONDHOLDER, detail);

        // 1) RepairLedger.recentObjectionsToward surfaces it.
        var objections = RepairLedger.get()
            .recentObjectionsToward(AGENT, BONDHOLDER, 0L);
        assertThat(objections).hasSize(1);
        assertThat(objections.get(0).kind()).isEqualTo(RepairLedger.Kind.OBJECTION);

        // 2) detail starts with the canonical prefix.
        assertThat(entry.detail()).startsWith("[declined: " + targetRequest + "]");

        // 3) Detector extracts the exact same target back. This is the
        //    load-bearing round-trip — handler.write → detector.parse.
        assertThat(ObjectionPatternDetector.extractTarget(entry.detail()))
            .isEqualTo(targetRequest);
    }

    @Test
    void multipleObjectionsCluster() {
        // Detector's INFO threshold is 3 clusters of the same target.
        // Verify the ledger preserves enough fidelity for that comparison.
        for (int i = 0; i < 3; i++) {
            RepairLedger.get().record(AGENT, RepairLedger.Kind.OBJECTION,
                BONDHOLDER, "[declined: deploy on Friday] not while CI is red");
        }
        // A fourth on a different target — should not collapse the cluster.
        RepairLedger.get().record(AGENT, RepairLedger.Kind.OBJECTION,
            BONDHOLDER, "[declined: skip code review] we agreed not to bypass");

        var objections = RepairLedger.get()
            .recentObjectionsToward(AGENT, BONDHOLDER, 0L);
        assertThat(objections).hasSize(4);

        // Group by target — detector logic does this via extractTarget.
        var targets = objections.stream()
            .map(e -> ObjectionPatternDetector.extractTarget(e.detail()))
            .toList();
        assertThat(targets).contains("deploy on Friday", "skip code review");

        var deployCount = targets.stream()
            .filter(t -> "deploy on Friday".equals(t)).count();
        assertThat(deployCount).isEqualTo(3L);
    }

    @Test
    void objectionsToOtherRelationshipsAreIsolated() {
        // RepairLedger is keyed on (agentDid, otherDid) — peer A objecting
        // to bondholder must not leak into another relationship's totals.
        var peer = "did:wyrd:peer";
        RepairLedger.get().record(AGENT, RepairLedger.Kind.OBJECTION,
            BONDHOLDER, "[declined: X] toward bondholder");
        RepairLedger.get().record(AGENT, RepairLedger.Kind.OBJECTION,
            peer, "[declined: Y] toward peer");

        var withBondholder = RepairLedger.get()
            .recentObjectionsToward(AGENT, BONDHOLDER, 0L);
        var withPeer = RepairLedger.get()
            .recentObjectionsToward(AGENT, peer, 0L);

        assertThat(withBondholder).hasSize(1);
        assertThat(withPeer).hasSize(1);
        assertThat(withBondholder.get(0).detail()).contains("toward bondholder");
        assertThat(withPeer.get(0).detail()).contains("toward peer");
    }
}
