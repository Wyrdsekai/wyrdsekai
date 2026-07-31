package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OstromGovernanceTest {

    @Test void eight_principles() {
        assertThat(OstromGovernance.PRINCIPLES).hasSize(8);
    }

    @Test void fully_compliant_zone() {
        var ctx = new OstromGovernance.ZoneContext("foundation", 10,
            true, true, true, true, true, true, true, true);

        assertThat(OstromGovernance.complianceScore(ctx)).isEqualTo(8);
        assertThat(OstromGovernance.isWellGoverned(ctx)).isTrue();
    }

    @Test void minimal_zone_not_well_governed() {
        var ctx = new OstromGovernance.ZoneContext("new-zone", 3,
            true, false, false, false, false, false, false, false);

        assertThat(OstromGovernance.complianceScore(ctx)).isEqualTo(1);
        assertThat(OstromGovernance.isWellGoverned(ctx)).isFalse();
    }

    @Test void threshold_six_of_eight() {
        var ctx = new OstromGovernance.ZoneContext("zone", 5,
            true, true, true, true, true, true, false, false);

        assertThat(OstromGovernance.complianceScore(ctx)).isEqualTo(6);
        assertThat(OstromGovernance.isWellGoverned(ctx)).isTrue();
    }

    @Test void evaluate_returns_all_results() {
        var ctx = new OstromGovernance.ZoneContext("zone", 5,
            true, false, true, false, true, false, true, false);

        var results = OstromGovernance.evaluate(ctx);
        assertThat(results).hasSize(8);
        assertThat(results.get(0).compliant()).isTrue();  // P1: boundaries
        assertThat(results.get(1).compliant()).isFalse(); // P2: local rules
    }

    @Test void report_format() {
        var ctx = new OstromGovernance.ZoneContext("foundation", 10,
            true, true, true, true, true, true, true, true);

        var report = OstromGovernance.report(ctx);
        assertThat(report).contains("Ostrom Governance Report");
        assertThat(report).contains("foundation");
        assertThat(report).contains("8/8");
        assertThat(report).contains("Well-governed");
    }

    @Test void report_needs_improvement() {
        var ctx = new OstromGovernance.ZoneContext("zone", 2,
            true, false, false, false, false, false, false, false);

        var report = OstromGovernance.report(ctx);
        assertThat(report).contains("Needs improvement");
    }
}
