package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.RepairLedger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 1 — pattern-detection coverage for
 * conscientious objection. Validates that the chronicle surfacing
 * fires only when the pattern is informationally interesting (cluster
 * threshold met OR high overall volume) and stays quiet otherwise.
 */
class ObjectionPatternDetectorTest {

    private static final String AGENT = "did:wyrd:obj-agent";
    private static final String BONDHOLDER = "did:wyrd:obj-bondholder";

    @BeforeEach
    @AfterEach
    void resetLedger() {
        RepairLedger.get().clearForTests();
    }

    @Test
    void empty_ledger_yields_no_findings() {
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        assertThat(findings).isEmpty();
    }

    @Test
    void sparse_diverse_objections_below_cluster_threshold_yield_no_findings() {
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
            "[declined: weekend work] not on saturdays");
        ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
            "[declined: public post] not in scope");
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        // Two objections, two distinct targets, well below volume threshold.
        // This is normal individual judgment.
        assertThat(findings).isEmpty();
    }

    @Test
    void cluster_of_same_target_surfaces_info_finding() {
        var ledger = RepairLedger.get();
        for (int i = 0; i < ObjectionPatternDetector.CLUSTER_THRESHOLD; i++) {
            ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
                "[declined: weekend work] attempt " + i);
        }
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        assertThat(findings).hasSize(1);
        var f = findings.get(0);
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.INFO);
        assertThat(f.key()).isEqualTo("objection_cluster:weekend work");
        assertThat(f.message()).contains("welfare floor working");
    }

    @Test
    void high_volume_diverse_targets_surfaces_warn_finding() {
        var ledger = RepairLedger.get();
        // Each target unique → no cluster, but total volume crosses high.
        for (int i = 0; i < ObjectionPatternDetector.HIGH_VOLUME_THRESHOLD; i++) {
            ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
                "[declined: topic_" + i + "] reason " + i);
        }
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        // Expect: 1 WARN for volume; NO cluster (all targets distinct).
        assertThat(findings).hasSize(1);
        var f = findings.get(0);
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.WARN);
        assertThat(f.key()).isEqualTo("objection_volume_high");
    }

    @Test
    void cluster_and_volume_can_both_fire() {
        var ledger = RepairLedger.get();
        // Cluster of 4 on one target + 3 unique others = 7 total ≥ HIGH_VOLUME.
        for (int i = 0; i < 4; i++) {
            ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
                "[declined: same] attempt " + i);
        }
        for (int i = 0; i < 3; i++) {
            ledger.record(AGENT, RepairLedger.Kind.OBJECTION, BONDHOLDER,
                "[declined: unique_" + i + "] one-off");
        }
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        // Expect: 1 WARN (volume) + 1 INFO (cluster).
        assertThat(findings).hasSize(2);
        assertThat(findings).anyMatch(
            f -> f.severity() == DoomLoopDetector.Severity.WARN);
        assertThat(findings).anyMatch(
            f -> f.severity() == DoomLoopDetector.Severity.INFO
                && f.key().contains("same"));
    }

    @Test
    void blank_bondholder_yields_no_findings() {
        var ledger = RepairLedger.get();
        for (int i = 0; i < ObjectionPatternDetector.CLUSTER_THRESHOLD; i++) {
            ledger.record(AGENT, RepairLedger.Kind.OBJECTION, "",
                "[declined: same] self-only objection");
        }
        // Self-only objections (no bondholder) have no relational signal to
        // surface; detector returns empty rather than chronicle-spamming.
        assertThat(ObjectionPatternDetector.detect(AGENT, "")).isEmpty();
        assertThat(ObjectionPatternDetector.detect(AGENT, null)).isEmpty();
    }

    @Test
    void non_objection_ledger_entries_are_ignored() {
        var ledger = RepairLedger.get();
        // Acknowledge-harm and amends accumulating between agent + bondholder
        // must not be misclassified as objections.
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, BONDHOLDER, "owned it 1");
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, BONDHOLDER, "owned it 2");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, BONDHOLDER, "amends 1");
        ledger.record(AGENT, RepairLedger.Kind.BEAR_THE_WOUND, BONDHOLDER, "carrying");
        var findings = ObjectionPatternDetector.detect(AGENT, BONDHOLDER);
        assertThat(findings).isEmpty();
    }

    @Test
    void extractTarget_parses_handler_format() {
        assertThat(ObjectionPatternDetector.extractTarget(
            "[declined: weekend work] full reason text"))
            .isEqualTo("weekend work");
        assertThat(ObjectionPatternDetector.extractTarget(
            "[declined: a long target with spaces and: colons] reason"))
            .isEqualTo("a long target with spaces and: colons");
    }

    @Test
    void extractTarget_returns_null_on_non_handler_format() {
        assertThat(ObjectionPatternDetector.extractTarget(null)).isNull();
        assertThat(ObjectionPatternDetector.extractTarget("")).isNull();
        assertThat(ObjectionPatternDetector.extractTarget(
            "legacy plain-text objection from a hand-authored entry")).isNull();
        assertThat(ObjectionPatternDetector.extractTarget(
            "[declined: never closed")).isNull();
        assertThat(ObjectionPatternDetector.extractTarget(
            "[declined: ] empty target")).isNull();
    }
}
