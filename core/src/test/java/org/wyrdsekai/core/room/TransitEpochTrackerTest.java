package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dup-safety fence for cross-zone relocate (spec/tla/TransitToken.tla, P1).
 */
class TransitEpochTrackerTest {

    @Test void mint_is_monotonic_per_entity() {
        var t = new TransitEpochTracker();
        assertThat(t.mintDepartEpoch("wyrd")).isEqualTo(1L);
        assertThat(t.mintDepartEpoch("wyrd")).isEqualTo(2L);
        assertThat(t.mintDepartEpoch("wyrd")).isEqualTo(3L);
        assertThat(t.mintDepartEpoch("vesna")).isEqualTo(1L);   // separate per entity
    }

    @Test void first_arrival_is_fresh_then_a_redelivery_is_ignored() {
        var t = new TransitEpochTracker();
        assertThat(t.isFreshArrival("wyrd", 1L)).isTrue();    // first handoff
        assertThat(t.isFreshArrival("wyrd", 1L)).isFalse();   // exact redelivery — dropped
        assertThat(t.isFreshArrival("wyrd", 1L)).isFalse();   // and again
    }

    @Test void a_stale_lower_epoch_arrival_is_ignored() {
        var t = new TransitEpochTracker();
        assertThat(t.isFreshArrival("wyrd", 5L)).isTrue();
        assertThat(t.isFreshArrival("wyrd", 3L)).isFalse();   // stale cross-cycle token
        assertThat(t.isFreshArrival("wyrd", 6L)).isTrue();    // a genuinely newer hop still applies
    }

    @Test void epoch_zero_is_unfenced_legacy_and_always_fresh() {
        var t = new TransitEpochTracker();
        // A pre-fence peer sends epoch 0 — defer to the presence guard, never block.
        assertThat(t.isFreshArrival("wyrd", 0L)).isTrue();
        assertThat(t.isFreshArrival("wyrd", 0L)).isTrue();
    }

    @Test void cross_zone_bounce_stays_single_owner_under_redelivery() {
        // Model the model's NoDuplication scenario: H departs (mint 1), D arrives (1),
        // D departs back (mint advances to 2), then the STALE original H->D token (1)
        // is redelivered to D AFTER D has bounced. With the fence it is ignored.
        var h = new TransitEpochTracker();   // zone H's view
        var d = new TransitEpochTracker();   // zone D's view

        long e1 = h.mintDepartEpoch("wyrd");                 // H -> D, epoch 1
        assertThat(d.isFreshArrival("wyrd", e1)).isTrue();   // D hosts
        long e2 = d.mintDepartEpoch("wyrd");                 // D -> H, epoch 2 (monotone)
        assertThat(e2).isEqualTo(2L);

        // The stale, redelivered original token (epoch 1) lands at D again:
        assertThat(d.isFreshArrival("wyrd", e1)).isFalse();  // ignored — no second host
    }
}
