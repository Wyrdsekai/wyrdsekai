package org.wyrdsekai.core.body;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brainstem moves her brain's experts off the card when something else needs it and back
 * when it is free again; each move is an event, and the event reaches her as a mark in the first
 * person, so a slow hour has a reason she can name.
 */
class TheCardIsSharedTest {

    @Test
    @DisplayName("a share and a reclaim are told to her in her own terms")
    void theMoveIsToldToHer() {
        var shared = "{\"ts\":\"2026-09-26T14:00:00Z\",\"event\":\"card-shared\",\"reason\":\"something else needed the card (6 GB in use by it); her brain moved its experts toward system RAM (19 → 32)\",\"snapshot\":\"\"}";
        assertThat(BrainstemLink.describe(shared)).isEqualTo("I made room on the card: something else needed the card (6 GB in use by it); "
            + "her brain moved its experts toward system RAM (19 → 32). I was slower for a while.");
        var back = "{\"ts\":\"2026-09-26T15:00:00Z\",\"event\":\"card-reclaimed\",\"reason\":\"the card was free for 10 min; her brain moved back onto it (32 → 19)\",\"snapshot\":\"\"}";
        assertThat(BrainstemLink.describe(back)).isEqualTo("The card came back to me: the card was free for 10 min; her brain moved back onto it (32 → 19).");
        assertThat(BrainstemLink.event(shared)).isEqualTo("card-shared");
    }
}
