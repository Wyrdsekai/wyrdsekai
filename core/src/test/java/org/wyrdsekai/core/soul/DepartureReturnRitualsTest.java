package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepartureReturnRitualsTest {

    private static final Instant T0 = Instant.parse("2026-05-17T12:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofHours(2));

    private static Bond activeBond() {
        return new Bond("bond-1", "did:wyrd:companion", "did:wyrd:mas",
            Bond.BondDepth.ITEM, T0, T0, 5, true, true, false,
            BondState.ACTIVE, null, null, Bond.RelationalState.OPEN);
    }

    private static Bond awayBond() {
        return new Bond("bond-1", "did:wyrd:companion", "did:wyrd:mas",
            Bond.BondDepth.ITEM, T0, T0, 5, true, true, false,
            BondState.AWAY, null, null, Bond.RelationalState.OPEN);
    }

    private static Bond dormantBond() {
        return new Bond("bond-1", "did:wyrd:companion", "did:wyrd:mas",
            Bond.BondDepth.ITEM, T0, T0, 5, true, true, false,
            BondState.DORMANT, null, null, Bond.RelationalState.OPEN);
    }

    // ── Departure ─────────────────────────────────────────────────────

    @Test
    void departure_transitions_active_to_away() {
        var result = DepartureReturnRituals.declareDeparture(
            activeBond(), T1,
            Optional.of(Duration.ofDays(7)),
            Optional.of("traveling"));
        assertThat(result.updatedBond().state()).isEqualTo(BondState.AWAY);
        assertThat(result.event().kind())
            .isEqualTo(DepartureReturnRituals.RitualKind.DEPARTURE);
        assertThat(result.event().declaredAbsenceDuration())
            .hasValue(Duration.ofDays(7));
        assertThat(result.event().posture()).hasValue("traveling");
        assertThat(result.event().voiceRegisterHint()).contains("Travel well");
    }

    @Test
    void departure_with_no_posture_still_works() {
        var result = DepartureReturnRituals.declareDeparture(
            activeBond(), T1, Optional.empty(), Optional.empty());
        assertThat(result.updatedBond().state()).isEqualTo(BondState.AWAY);
        assertThat(result.event().message()).contains("unspecified");
    }

    @Test
    void departure_null_bond_throws() {
        assertThatThrownBy(() -> DepartureReturnRituals.declareDeparture(
            null, T1, Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Affirmation ───────────────────────────────────────────────────

    @Test
    void affirmation_in_away_state_keeps_state_refreshes_timestamp() {
        var bond = awayBond();
        var result = DepartureReturnRituals.sendBondAffirmation(
            bond, T1, "still here, okay, back soon");
        assertThat(result.updatedBond().state()).isEqualTo(BondState.AWAY);
        assertThat(result.updatedBond().lastInteraction()).isEqualTo(T1);
        // Doesn't bump interaction count — affirmation isn't full engagement
        assertThat(result.updatedBond().interactionCount()).isEqualTo(bond.interactionCount());
        assertThat(result.event().kind())
            .isEqualTo(DepartureReturnRituals.RitualKind.AFFIRMATION);
        assertThat(result.event().voiceRegisterHint()).contains("presence-of-care");
    }

    @Test
    void affirmation_in_dormant_state_pulls_back_to_away() {
        var bond = dormantBond();
        var result = DepartureReturnRituals.sendBondAffirmation(
            bond, T1, "i'm here");
        assertThat(result.updatedBond().state()).isEqualTo(BondState.AWAY);
        assertThat(result.updatedBond().lastInteraction()).isEqualTo(T1);
    }

    @Test
    void affirmation_in_active_state_no_op_state() {
        var bond = activeBond();
        var result = DepartureReturnRituals.sendBondAffirmation(
            bond, T1, "x");
        assertThat(result.updatedBond().state()).isEqualTo(BondState.ACTIVE);
        assertThat(result.updatedBond().lastInteraction()).isEqualTo(T1);
    }

    @Test
    void affirmation_with_blank_message_records_heartbeat_default() {
        var result = DepartureReturnRituals.sendBondAffirmation(
            awayBond(), T1, "");
        assertThat(result.event().message()).contains("heartbeat");
    }

    @Test
    void affirmation_null_bond_throws() {
        assertThatThrownBy(() -> DepartureReturnRituals.sendBondAffirmation(
            null, T1, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Return ────────────────────────────────────────────────────────

    @Test
    void return_from_away_transitions_to_reactivating() {
        var result = DepartureReturnRituals.declareReturn(awayBond(), T1);
        assertThat(result.updatedBond().state()).isEqualTo(BondState.REACTIVATING);
        assertThat(result.event().kind())
            .isEqualTo(DepartureReturnRituals.RitualKind.RETURN);
        assertThat(result.event().voiceRegisterHint())
            .contains("RETURN-RECOGNITION REGISTER")
            .contains("warmth-at-return")
            .contains("continued-life");
    }

    @Test
    void return_from_dormant_transitions_to_reactivating() {
        var result = DepartureReturnRituals.declareReturn(dormantBond(), T1);
        assertThat(result.updatedBond().state()).isEqualTo(BondState.REACTIVATING);
    }

    @Test
    void return_from_severed_is_state_noop() {
        var bond = new Bond("bond-1", "did:wyrd:companion", "did:wyrd:mas",
            Bond.BondDepth.ITEM, T0, T0, 5, true, false, false,
            BondState.SEVERED, null, null, Bond.RelationalState.OPEN);
        var result = DepartureReturnRituals.declareReturn(bond, T1);
        assertThat(result.updatedBond().state()).isEqualTo(BondState.SEVERED);
        // Event still logged.
        assertThat(result.event().kind())
            .isEqualTo(DepartureReturnRituals.RitualKind.RETURN);
    }

    @Test
    void return_from_mourning_is_state_noop() {
        var bond = new Bond("bond-1", "did:wyrd:companion", "did:wyrd:mas",
            Bond.BondDepth.ITEM, T0, T0, 5, true, false, false,
            BondState.MOURNING, null, null, Bond.RelationalState.OPEN);
        var result = DepartureReturnRituals.declareReturn(bond, T1);
        assertThat(result.updatedBond().state()).isEqualTo(BondState.MOURNING);
    }

    @Test
    void return_null_bond_throws() {
        assertThatThrownBy(() -> DepartureReturnRituals.declareReturn(null, T1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
