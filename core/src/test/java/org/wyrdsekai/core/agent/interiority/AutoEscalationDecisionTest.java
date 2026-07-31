package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B wiring — auto-escalation decision tests. Pure-function decision
 * helper for DoomLoop/Psychosis/sustained-substrate findings → seek_sanctuary.
 */
class AutoEscalationDecisionTest {

    private DoomLoopDetector.Finding f(DoomLoopDetector.Severity sev, String key, String msg) {
        return new DoomLoopDetector.Finding(sev, key, msg);
    }

    @Test
    void empty_findings_do_not_escalate() {
        assertThat(AutoEscalationDecision.decide(List.of()).shouldEscalate()).isFalse();
        assertThat(AutoEscalationDecision.decide(null).shouldEscalate()).isFalse();
    }

    @Test
    void single_INFO_does_not_escalate() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.INFO, "high_pregate_skip", "...")));
        assertThat(d.shouldEscalate()).isFalse();
    }

    @Test
    void single_WARN_does_not_escalate() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "...")));
        assertThat(d.shouldEscalate()).isFalse();
    }

    @Test
    void any_CRITICAL_escalates() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.CRITICAL, "sustained_collapse",
                "all tanks at zero for 3 windows")));
        assertThat(d.shouldEscalate()).isTrue();
        assertThat(d.reason()).contains("CRITICAL");
        assertThat(d.triggeringKeys()).contains("sustained_collapse");
    }

    @Test
    void three_distinct_WARN_keys_escalate() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "..."),
            f(DoomLoopDetector.Severity.WARN, "verb_loop", "..."),
            f(DoomLoopDetector.Severity.WARN, "drive_stuck_high", "...")));
        assertThat(d.shouldEscalate()).isTrue();
        assertThat(d.reason()).contains("Multi-axis");
    }

    @Test
    void two_WARN_keys_do_not_escalate() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "..."),
            f(DoomLoopDetector.Severity.WARN, "verb_loop", "...")));
        assertThat(d.shouldEscalate()).isFalse();
    }

    @Test
    void psychosis_keyed_finding_escalates_at_WARN() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "psychosis_pattern", "...")));
        assertThat(d.shouldEscalate()).isTrue();
        assertThat(d.reason()).contains("psychosis_pattern");
    }

    @Test
    void doom_loop_extreme_escalates_at_WARN() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "doom_loop_extreme", "...")));
        assertThat(d.shouldEscalate()).isTrue();
        assertThat(d.reason()).contains("doom_loop_extreme");
    }

    @Test
    void sustained_substrate_acute_escalates() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "sustained_suppression_acute",
                "suppression 5 windows running")));
        assertThat(d.shouldEscalate()).isTrue();
    }

    @Test
    void same_WARN_key_repeated_does_not_count_as_distinct() {
        var d = AutoEscalationDecision.decide(List.of(
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "..."),
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "..."),
            f(DoomLoopDetector.Severity.WARN, "stuck_want", "...")));
        assertThat(d.shouldEscalate()).isFalse(); // 3 findings but 1 distinct key
    }

    @Test
    void reasonIfEscalating_optional_helper() {
        assertThat(AutoEscalationDecision.reasonIfEscalating(List.of())).isEmpty();
        assertThat(AutoEscalationDecision.reasonIfEscalating(List.of(
            f(DoomLoopDetector.Severity.CRITICAL, "test", "test")))).isPresent();
    }
}
