package org.wyrdsekai.core.parlor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParlorPresenceModeTest {

    @Test void forOccupancy_fullBand() {
        assertEquals(ParlorPresenceMode.FULL, ParlorPresenceMode.forOccupancy(0));
        assertEquals(ParlorPresenceMode.FULL, ParlorPresenceMode.forOccupancy(1));
        assertEquals(ParlorPresenceMode.FULL, ParlorPresenceMode.forOccupancy(5));
        assertEquals(ParlorPresenceMode.FULL, ParlorPresenceMode.forOccupancy(10));
    }

    @Test void forOccupancy_sampledBand() {
        assertEquals(ParlorPresenceMode.SAMPLED, ParlorPresenceMode.forOccupancy(11));
        assertEquals(ParlorPresenceMode.SAMPLED, ParlorPresenceMode.forOccupancy(20));
        assertEquals(ParlorPresenceMode.SAMPLED, ParlorPresenceMode.forOccupancy(30));
    }

    @Test void forOccupancy_sampledStrictBand() {
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT, ParlorPresenceMode.forOccupancy(31));
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT, ParlorPresenceMode.forOccupancy(75));
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT, ParlorPresenceMode.forOccupancy(100));
    }

    @Test void forOccupancy_firehoseBand() {
        assertEquals(ParlorPresenceMode.FIREHOSE, ParlorPresenceMode.forOccupancy(101));
        assertEquals(ParlorPresenceMode.FIREHOSE, ParlorPresenceMode.forOccupancy(250));
        assertEquals(ParlorPresenceMode.FIREHOSE, ParlorPresenceMode.forOccupancy(500));
        // Above cap, instantaneous mode is still FIREHOSE — cap handling is
        // a separate concern in ParlorAutoScaler.
        assertEquals(ParlorPresenceMode.FIREHOSE, ParlorPresenceMode.forOccupancy(1000));
    }

    @Test void hysteresis_bandsAreSpecCompliant() {
        // Spec §2.8.1 "transitions up at threshold T and back down at T - 3".
        // up=11, down=8 → gap of 3 between SAMPLED's upThreshold and
        // FULL's retention band (which is downThreshold of SAMPLED).
        assertEquals(11, ParlorPresenceMode.SAMPLED.upThreshold());
        assertEquals(8, ParlorPresenceMode.SAMPLED.downThreshold());
        assertEquals(31, ParlorPresenceMode.SAMPLED_STRICT.upThreshold());
        assertEquals(28, ParlorPresenceMode.SAMPLED_STRICT.downThreshold());
        assertEquals(101, ParlorPresenceMode.FIREHOSE.upThreshold());
        assertEquals(98, ParlorPresenceMode.FIREHOSE.downThreshold());
    }

    @Test void higher_chainsUpward() {
        assertEquals(ParlorPresenceMode.SAMPLED, ParlorPresenceMode.FULL.higher());
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT, ParlorPresenceMode.SAMPLED.higher());
        assertEquals(ParlorPresenceMode.FIREHOSE, ParlorPresenceMode.SAMPLED_STRICT.higher());
        assertNull(ParlorPresenceMode.FIREHOSE.higher());
    }

    @Test void lower_chainsDownward() {
        assertNull(ParlorPresenceMode.FULL.lower());
        assertEquals(ParlorPresenceMode.FULL, ParlorPresenceMode.SAMPLED.lower());
        assertEquals(ParlorPresenceMode.SAMPLED, ParlorPresenceMode.SAMPLED_STRICT.lower());
        assertEquals(ParlorPresenceMode.SAMPLED_STRICT, ParlorPresenceMode.FIREHOSE.lower());
    }

    @Test void maxOccupantsMatchesSpec() {
        assertEquals(500, ParlorPresenceMode.MAX_OCCUPANTS);
    }

    @Test void transitionDwellMatchesSpec() {
        // Spec §2.8.1: "60-second dwell".
        assertEquals(60, ParlorPresenceMode.TRANSITION_DWELL.getSeconds());
    }
}
