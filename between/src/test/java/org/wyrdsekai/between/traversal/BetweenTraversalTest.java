package org.wyrdsekai.between.traversal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BetweenTraversalTest {

    private BetweenTraversal traversal;

    @BeforeEach
    void setUp() {
        traversal = new BetweenTraversal();
    }

    @Test void min_travel_time_enforced() {
        var time = traversal.calculateTravelTime(1.0); // very low RTT
        assertThat(time).isGreaterThanOrEqualTo(BetweenTraversal.MIN_TRAVEL_TIME);
    }

    @Test void max_travel_time_capped() {
        var time = traversal.calculateTravelTime(50000.0); // extremely high RTT
        assertThat(time).isLessThanOrEqualTo(BetweenTraversal.MAX_TRAVEL_TIME);
    }

    @Test void travel_time_scales_with_rtt() {
        var fast = traversal.calculateTravelTime(50.0);
        var slow = traversal.calculateTravelTime(500.0);
        assertThat(slow).isGreaterThanOrEqualTo(fast);
    }

    @Test void depart_creates_journey() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(50.0, 2.0, 1_000_000, 2, Instant.now());
        var journey = traversal.depart("agent-1", "zone-a", "zone-b", telemetry);

        assertThat(journey.journeyId()).startsWith("journey-");
        assertThat(journey.agentId()).isEqualTo("agent-1");
        assertThat(journey.sourceZoneId()).isEqualTo("zone-a");
        assertThat(journey.targetZoneId()).isEqualTo("zone-b");
        assertThat(journey.status()).isEqualTo(BetweenTraversal.JourneyStatus.IN_TRANSIT);
        assertThat(journey.narrative()).isNotEmpty();
    }

    @Test void journey_has_narrative() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(100.0, 10.0, 50_000_000, 3, Instant.now());
        var journey = traversal.depart("agent-1", "zone-a", "zone-b", telemetry);

        assertThat(journey.narrative()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(journey.narrative().getFirst()).contains("zone-a");
        assertThat(journey.narrative().getLast()).contains("zone-b");
    }

    @Test void cancel_journey() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(50.0, 2.0, 1_000_000, 1, Instant.now());
        var journey = traversal.depart("agent-1", "zone-a", "zone-b", telemetry);

        var cancelled = traversal.cancelJourney(journey.journeyId());
        assertThat(cancelled.status()).isEqualTo(BetweenTraversal.JourneyStatus.FAILED);
        assertThat(cancelled.isComplete()).isTrue();
    }

    @Test void get_journey_by_id() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(50.0, 2.0, 1_000_000, 1, Instant.now());
        var journey = traversal.depart("agent-1", "zone-a", "zone-b", telemetry);

        assertThat(traversal.getJourney(journey.journeyId())).isPresent();
        assertThat(traversal.getJourney("nonexistent")).isEmpty();
    }

    @Test void journeys_for_agent() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(50.0, 2.0, 1_000_000, 1, Instant.now());
        traversal.depart("agent-1", "zone-a", "zone-b", telemetry);
        traversal.depart("agent-2", "zone-b", "zone-c", telemetry);

        assertThat(traversal.journeysFor("agent-1")).hasSize(1);
        assertThat(traversal.journeysFor("agent-2")).hasSize(1);
    }

    @Test void active_journeys_excludes_completed() {
        var telemetry = new BetweenTraversal.TelemetrySnapshot(50.0, 2.0, 1_000_000, 1, Instant.now());
        var j1 = traversal.depart("agent-1", "zone-a", "zone-b", telemetry);
        traversal.depart("agent-2", "zone-b", "zone-c", telemetry);
        traversal.cancelJourney(j1.journeyId());

        assertThat(traversal.activeJourneys()).hasSize(1);
    }

    @Test void completed_count() {
        assertThat(traversal.completedCount()).isEqualTo(0);
    }

    @Test void telemetry_snapshot_captures_data() {
        var ts = new BetweenTraversal.TelemetrySnapshot(42.5, 3.1, 100_000_000, 4, Instant.now());
        assertThat(ts.latencyMs()).isEqualTo(42.5);
        assertThat(ts.jitterMs()).isEqualTo(3.1);
        assertThat(ts.bandwidthBps()).isEqualTo(100_000_000);
        assertThat(ts.hopCount()).isEqualTo(4);
    }
}
