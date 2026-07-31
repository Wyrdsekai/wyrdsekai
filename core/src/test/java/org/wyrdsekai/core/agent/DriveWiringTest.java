package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.affordance.AffordanceSeed;
import org.wyrdsekai.core.agent.interiority.DriveWantMapper;

import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard against the silent producer/consumer mis-wire class.
 *
 * <p>The drive system has one PRODUCER ({@code CompanionActor.collectDriveLevels()},
 * key set mirrored in {@link CompanionActor#DRIVE_LEVEL_KEYS}) feeding two
 * CONSUMERS that look drives up by string name: the want layer
 * ({@link DriveWantMapper#CONSUMED_KEYS}) and the affordance ranker
 * ({@link AffordanceSeed#allNeedNames()}). If a consumer references a name the
 * producer never populates, the lookup silently returns null and that want /
 * tool-need coupling is DEAD with no error — exactly how SEEKING→"Curiosity" and
 * frustration→"Frustration" were orphaned (2026-06-02), and a sibling of the
 * applyTankFeedbackArray mis-wire before it.
 *
 * <p>These assertions fail the build the moment a new want or need references an
 * unproduced drive, so the gap can never ship silently again.
 */
class DriveWiringTest {

    @Test
    void everyWantDriveKeyIsProduced() {
        var missing = new TreeSet<>(DriveWantMapper.CONSUMED_KEYS);
        missing.removeAll(CompanionActor.DRIVE_LEVEL_KEYS);
        assertThat(missing)
            .as("DriveWantMapper looks up drive keys that collectDriveLevels() never "
                + "produces — those wants are dead. Add them to collectDriveLevels() "
                + "(+ DRIVE_LEVEL_KEYS) or remove the want.")
            .isEmpty();
    }

    @Test
    void everyAffordanceNeedNameIsProduced() {
        var missing = new TreeSet<>(AffordanceSeed.allNeedNames());
        missing.removeAll(CompanionActor.DRIVE_LEVEL_KEYS);
        assertThat(missing)
            .as("AffordanceSeed references need-names the affordance ranker can't find "
                + "in the live drive map — those tool→need couplings are silently dead. "
                + "Add them to collectDriveLevels() (+ DRIVE_LEVEL_KEYS).")
            .isEmpty();
    }

    @Test
    void curiosityAndFrustrationAreNowProduced() {
        // Regression pin for the specific mis-wire this work fixed.
        assertThat(CompanionActor.DRIVE_LEVEL_KEYS).contains("Curiosity", "Frustration");
        assertThat(DriveWantMapper.CONSUMED_KEYS).contains("Curiosity", "Frustration");
    }

    @Test
    void affiliationAndCareReachTheWantLayer() {
        // Proactive-social wire (2026-06-02): the approach/affiliative panksepp
        // drives must be produced so they reach the felt-state the generative Orient
        // reads when the agent names its own wants (and DriveWantMapper's fallback).
        // Before this, only seeking+frustration of DriveState's ten drives were
        // surfaced, so a co-located agent never felt the pull toward a present peer
        // (the 0-peer-want co-presence result). Read out of the live drive map, not
        // DriveWantMapper.CONSUMED_KEYS, so the guard only needs them PRODUCED.
        assertThat(CompanionActor.DRIVE_LEVEL_KEYS).contains("Affiliation", "Care");
    }
}
