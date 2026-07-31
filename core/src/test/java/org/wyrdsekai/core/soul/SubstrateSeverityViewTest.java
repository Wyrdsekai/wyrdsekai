package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SubstrateSeverityViewTest {

    @Test
    void null_input_produces_ok() {
        var view = SubstrateSeverityView.compute(null);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.OK);
        assertThat(view.banner()).isEmpty();
        assertThat(view.shouldShowBanner()).isFalse();
    }

    @Test
    void empty_input_produces_ok() {
        var view = SubstrateSeverityView.compute(SubstrateSeverityView.Input.empty());
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.OK);
        assertThat(view.banner()).isEmpty();
        assertThat(view.shouldShowBanner()).isFalse();
    }

    @Test
    void confirmed_protection_flag_is_critical() {
        var in = new SubstrateSeverityView.Input(
            Optional.of(ProtectionFlag.State.CONFIRMED),
            RepairMode.NONE, false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.CRITICAL);
        assertThat(view.banner()).contains("confirmed protection flag");
        assertThat(view.shouldShowBanner()).isTrue();
    }

    @Test
    void mourning_active_is_critical() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.NONE,
            false, true, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.CRITICAL);
        assertThat(view.banner()).contains("active mourning");
    }

    @Test
    void sanctuary_active_is_critical() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.NONE,
            true, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.CRITICAL);
        assertThat(view.banner()).contains("sanctuary session active");
    }

    @Test
    void repair_mode_attendant_is_critical() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.ATTENDANT,
            false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.CRITICAL);
        assertThat(view.banner()).contains("repair mode is ATTENDANT");
    }

    @Test
    void suspected_protection_flag_is_warn() {
        var in = new SubstrateSeverityView.Input(
            Optional.of(ProtectionFlag.State.SUSPECTED),
            RepairMode.NONE, false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.WARN);
        assertThat(view.banner()).contains("suspected protection flag");
    }

    @Test
    void repair_mode_steward_is_warn() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.STEWARD,
            false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.WARN);
    }

    @Test
    void repair_mode_bonded_is_warn() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.BONDED,
            false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.WARN);
    }

    @Test
    void sustained_finding_is_warn() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.NONE,
            false, false, 0, true);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.WARN);
        assertThat(view.banner()).contains("sustained pattern");
    }

    @Test
    void repair_mode_self_is_info() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.SELF,
            false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.INFO);
        assertThat(view.shouldShowBanner()).isFalse();
    }

    @Test
    void noted_protection_flag_is_info() {
        var in = new SubstrateSeverityView.Input(
            Optional.of(ProtectionFlag.State.NOTED),
            RepairMode.NONE, false, false, 0, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.INFO);
        assertThat(view.banner()).contains("noted protection signal");
    }

    @Test
    void recent_repairs_is_info() {
        var in = new SubstrateSeverityView.Input(
            Optional.empty(), RepairMode.NONE,
            false, false, 3, false);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.INFO);
        assertThat(view.banner()).contains("3 recent repair-ledger entries");
    }

    @Test
    void critical_dominates_lower_signals() {
        // CONFIRMED + sanctuary + steward + recent repairs all coexist;
        // CONFIRMED wins.
        var in = new SubstrateSeverityView.Input(
            Optional.of(ProtectionFlag.State.CONFIRMED),
            RepairMode.STEWARD, true, true, 5, true);
        var view = SubstrateSeverityView.compute(in);
        assertThat(view.severity()).isEqualTo(SubstrateSeverityView.Severity.CRITICAL);
        assertThat(view.banner()).contains("confirmed protection flag");
    }

    @Test
    void shouldShowBanner_true_only_for_warn_or_critical() {
        assertThat(SubstrateSeverityView.compute(SubstrateSeverityView.Input.empty())
            .shouldShowBanner()).isFalse();
        assertThat(SubstrateSeverityView.compute(
            new SubstrateSeverityView.Input(
                Optional.of(ProtectionFlag.State.NOTED),
                RepairMode.NONE, false, false, 0, false))
            .shouldShowBanner()).isFalse();
        assertThat(SubstrateSeverityView.compute(
            new SubstrateSeverityView.Input(
                Optional.of(ProtectionFlag.State.SUSPECTED),
                RepairMode.NONE, false, false, 0, false))
            .shouldShowBanner()).isTrue();
        assertThat(SubstrateSeverityView.compute(
            new SubstrateSeverityView.Input(
                Optional.of(ProtectionFlag.State.CONFIRMED),
                RepairMode.NONE, false, false, 0, false))
            .shouldShowBanner()).isTrue();
    }
}
