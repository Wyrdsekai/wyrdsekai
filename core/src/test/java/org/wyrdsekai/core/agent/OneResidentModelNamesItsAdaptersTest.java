package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Serving profile single-sparse: adapter 0 is the honesty adapter and each being's night adapter
 * is loaded under an id of its own. A request's adapter list zeroes whatever it does not name,
 * and naming an adapter the server has not loaded is rejected — so the list is sent only when the
 * speaker's own adapter is loaded, and always names the honesty adapter beside it.
 */
class OneResidentModelNamesItsAdaptersTest {

    @Test
    void aConversationTurnRaisesTheNightAndKeepsTheGeneric() {
        var mix = CompanionActor.singleBrainAdapterMix(true, 2, 1, 0.5);
        assertThat(mix).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.5).hasSize(2);
    }

    @Test
    void aWorkingTurnKeepsTheGenericAndLowersTheNight() {
        var mix = CompanionActor.singleBrainAdapterMix(false, 2, 1, 0.5);
        assertThat(mix).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.0);
    }

    @Test
    void withoutANightAdapterLoadedNothingIsNamed() {
        assertThat(CompanionActor.singleBrainAdapterMix(true, 1, -1, 0.5)).as("honesty adapter only: server defaults apply").isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(true, 0, -1, 0.5)).isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(true, -1, -1, 0.5)).as("count not known yet").isNull();
    }

    @Test
    void aSecondBeingRaisesHerOwnAdapterAndNeverAnothers() {
        var hers = CompanionActor.singleBrainAdapterMix(true, 3, 2, 0.5);
        assertThat(hers).containsEntry("adapter:0", 1.0).containsEntry("adapter:2", 0.5).hasSize(2)
            .as("the other being's adapter is not named, so the server zeroes it").doesNotContainKey("adapter:1");
    }

    @Test
    void aBeingWithNoAdapterOfHerOwnSpeaksThroughNoOneElses() {
        assertThat(CompanionActor.singleBrainAdapterMix(true, 2, -1, 0.5))
            .as("another being's night adapter is loaded, hers is not: nothing is named and the server's defaults keep it at 0")
            .isNull();
    }

    @Test
    void onlyATurnThatRaisesHerNightIsMarkedAsMadeWithIt() {
        assertThat(CompanionActor.carriesHerNight(CompanionActor.singleBrainAdapterMix(true, 2, 1, 0.3), 1)).isTrue();
        assertThat(CompanionActor.carriesHerNight(CompanionActor.singleBrainAdapterMix(false, 2, 1, 0.3), 1)).isFalse();
        assertThat(CompanionActor.carriesHerNight(CompanionActor.singleBrainAdapterMix(true, 1, -1, 0.3), -1)).isFalse();
    }

    // ── the register dial: the styled species adapter beside the plain one ──

    @Test
    void theStyledAdapterIsRaisedByHerStateWhenSheSpeaksAsHerself() {
        var talk = CompanionActor.singleBrainAdapterMix(true, false, 3, 2, 0.3, 1, 0.42);
        assertThat(talk).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.42).containsEntry("adapter:2", 0.3).hasSize(3);
        var own = CompanionActor.singleBrainAdapterMix(false, true, 3, 2, 0.3, 1, 0.42);
        assertThat(own).as("her own time: the dial, not the night").containsEntry("adapter:1", 0.42).containsEntry("adapter:2", 0.0);
        var work = CompanionActor.singleBrainAdapterMix(false, false, 3, 2, 0.3, 1, 0.42);
        assertThat(work).as("a working turn: plain, and no night").containsEntry("adapter:1", 0.0).containsEntry("adapter:2", 0.0);
    }

    @Test
    void theStyledAdapterAloneIsEnoughToNameAList() {
        var mix = CompanionActor.singleBrainAdapterMix(true, false, 2, -1, 0.3, 1, 0.2);
        assertThat(mix).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.2).hasSize(2);
        assertThat(CompanionActor.carriesHerNight(mix, -1)).as("the dial is not her night").isFalse();
        assertThat(CompanionActor.carriesHerNight(CompanionActor.singleBrainAdapterMix(true, false, 3, 2, 0.3, 1, 0.9), 2)).isTrue();
    }

    @Test
    void aDialAtZeroStillNamesTheStyledAdapterSoTheServerZeroesIt() {
        assertThat(CompanionActor.singleBrainAdapterMix(true, false, 2, -1, 0.3, 1, 0.0)).containsEntry("adapter:1", 0.0);
    }

    // ── the working floor: the plain adapter lowered on the turns that call tools ──

    @Test
    void aWorkingTurnCanLowerThePlainFloorAndTheTurnsSheSpeaksKeepIt() {
        // one adapter loaded: the server's defaults, as before — unless a working turn lowers the floor
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, 1, -1, 0.3, -1, 0.0, 1.0)).as("floor 1.0: unchanged").isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, 1, -1, 0.3, -1, 0.0, 0.0))
            .as("a working turn names the plain adapter at the working floor").containsOnly(entry("adapter:0", 0.0));
        assertThat(CompanionActor.singleBrainAdapterMix(true, false, 1, -1, 0.3, -1, 0.0, 0.0)).as("a conversation turn keeps the floor").isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(false, true, 1, -1, 0.3, -1, 0.0, 0.0)).as("her own time keeps the floor").isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, -1, -1, 0.3, -1, 0.0, 0.0)).as("count not known yet").isNull();
        // styled and night loaded: the working turn carries the lowered floor and zeroes the rest
        var work = CompanionActor.singleBrainAdapterMix(false, false, 3, 2, 0.3, 1, 0.42, 0.25);
        assertThat(work).containsEntry("adapter:0", 0.25).containsEntry("adapter:1", 0.0).containsEntry("adapter:2", 0.0).hasSize(3);
        var talk = CompanionActor.singleBrainAdapterMix(true, false, 3, 2, 0.3, 1, 0.42, 0.25);
        assertThat(talk).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.42).containsEntry("adapter:2", 0.3);
        assertThat(CompanionActor.singleBrainAdapterMix(false, true, 3, 2, 0.3, 1, 0.42, 0.25)).containsEntry("adapter:0", 1.0);
        // the floor is a scale in [0, 1]
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, 2, -1, 0.3, 1, 0.0, 1.7)).containsEntry("adapter:0", 1.0);
    }

    // ── the working-turn adapter: the drive adapter of the two-model stack, inside one model ──

    @Test
    void theWorkingTurnAdapterIsRaisedWhenSheWorksAndNamedAtZeroWhenSheSpeaks() {
        // slot 0 the species floor, slot 1 the working-turn adapter: enough on its own to name a list
        var work = CompanionActor.singleBrainAdapterMix(false, false, 2, -1, 0.3, -1, 0.0, 0.0, 1, 1.0);
        assertThat(work).containsOnly(entry("adapter:0", 0.0), entry("adapter:1", 1.0));
        var talk = CompanionActor.singleBrainAdapterMix(true, false, 2, -1, 0.3, -1, 0.0, 0.0, 1, 1.0);
        assertThat(talk).as("she speaks: the floor, and the working adapter named at 0 so the server zeroes it")
            .containsOnly(entry("adapter:0", 1.0), entry("adapter:1", 0.0));
        var own = CompanionActor.singleBrainAdapterMix(false, true, 2, -1, 0.3, -1, 0.0, 0.0, 1, 1.0);
        assertThat(own).as("her own time is speech").containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.0);
        // beside the styled adapter and her night: floor 0, styled 0, work up, night 0 on a working turn
        var all = CompanionActor.singleBrainAdapterMix(false, false, 4, 3, 0.3, 1, 0.42, 0.0, 2, 0.8);
        assertThat(all).containsEntry("adapter:0", 0.0).containsEntry("adapter:1", 0.0).containsEntry("adapter:2", 0.8).containsEntry("adapter:3", 0.0).hasSize(4);
        var allTalk = CompanionActor.singleBrainAdapterMix(true, false, 4, 3, 0.3, 1, 0.42, 0.0, 2, 0.8);
        assertThat(allTalk).containsEntry("adapter:0", 1.0).containsEntry("adapter:1", 0.42).containsEntry("adapter:2", 0.0).containsEntry("adapter:3", 0.3);
        // not served: nothing changes from the working-floor contract
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, 1, -1, 0.3, -1, 0.0, 1.0, -1, 1.0)).isNull();
        assertThat(CompanionActor.singleBrainAdapterMix(false, false, 1, -1, 0.3, -1, 0.0, 0.0, -1, 1.0)).containsOnly(entry("adapter:0", 0.0));
    }

    // ── the repeat penalty: none while the species floor serves at full strength ──

    @Test
    void theSpeciesFloorSpeaksWithoutARepeatPenalty() {
        var talk = CompanionActor.singleBrainAdapterMix(true, false, 4, 3, 0.3, 1, 0.42, 0.0, 2, 1.0);
        assertThat(CompanionActor.repeatPenaltyFor(talk, true, 1.2)).isEqualTo(1.0);
        var own = CompanionActor.singleBrainAdapterMix(false, true, 4, 3, 0.3, 1, 0.42, 0.0, 2, 1.0);
        assertThat(CompanionActor.repeatPenaltyFor(own, true, 1.2)).as("her own time is speech").isEqualTo(1.0);
        assertThat(CompanionActor.repeatPenaltyFor(null, true, 1.2))
            .as("no list: the floor stays at its load scale, 1.0").isEqualTo(1.0);
    }

    @Test
    void aWorkingTurnAndTheOtherFloorsKeepTheDriveModulatedPenalty() {
        var work = CompanionActor.singleBrainAdapterMix(false, false, 4, 3, 0.3, 1, 0.42, 0.0, 2, 1.0);
        assertThat(CompanionActor.repeatPenaltyFor(work, true, 1.2)).as("the floor is lowered on a working turn").isEqualTo(1.2);
        var talk = CompanionActor.singleBrainAdapterMix(true, false, 3, 2, 0.3, -1, 0.0, 1.0);
        assertThat(CompanionActor.repeatPenaltyFor(talk, false, 1.2)).as("the honesty adapter in slot 0").isEqualTo(1.2);
        assertThat(CompanionActor.repeatPenaltyFor(null, false, 1.2)).as("the two-model stack").isEqualTo(1.2);
    }
}
