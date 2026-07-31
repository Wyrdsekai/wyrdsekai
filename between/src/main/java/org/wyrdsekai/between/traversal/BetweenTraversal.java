package org.wyrdsekai.between.traversal;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Between Traversal — travel between zones with narrative generation (§74).
 * Travel time is a function of RTT between zones. The Between is not empty space;
 * telemetry data becomes the world's texture during transit.
 */
public class BetweenTraversal {

    /** A traversal journey from one zone to another. */
    public record Journey(
        String journeyId,
        String agentId,
        String sourceZoneId,
        String targetZoneId,
        double rttMs,
        Duration travelTime,
        Instant departedAt,
        Instant arrivedAt,
        JourneyStatus status,
        List<String> narrative
    ) {
        public boolean isComplete() {
            return status == JourneyStatus.ARRIVED || status == JourneyStatus.FAILED;
        }

        public boolean hasArrived() {
            return status == JourneyStatus.ARRIVED;
        }

        public Duration elapsed() {
            var end = arrivedAt != null ? arrivedAt : Instant.now();
            return Duration.between(departedAt, end);
        }
    }

    public enum JourneyStatus { DEPARTING, IN_TRANSIT, ARRIVING, ARRIVED, FAILED }

    /** Telemetry snapshot during traversal. */
    public record TelemetrySnapshot(
        double latencyMs,
        double jitterMs,
        long bandwidthBps,
        int hopCount,
        Instant capturedAt
    ) {}

    /**
     * Minimum travel time (even for same-node zones).
     * The Between always has presence; instant teleportation breaks immersion.
     */
    public static final Duration MIN_TRAVEL_TIME = Duration.ofSeconds(3);

    /** Maximum travel time (cap to prevent frustration). */
    public static final Duration MAX_TRAVEL_TIME = Duration.ofMinutes(2);

    /** Base multiplier: 1ms RTT → this many seconds of travel. */
    public static final double RTT_TO_SECONDS_MULTIPLIER = 0.1;

    private final Map<String, Journey> activeJourneys = new LinkedHashMap<>();
    private final TelemetryMapper telemetryMapper = new TelemetryMapper();
    private int nextJourneyId = 1;

    /**
     * Calculate travel time from RTT between zones.
     * Formula: travel_seconds = max(MIN, min(MAX, RTT_ms × multiplier))
     */
    public Duration calculateTravelTime(double rttMs) {
        var seconds = rttMs * RTT_TO_SECONDS_MULTIPLIER;
        var clamped = Duration.ofMillis(Math.round(seconds * 1000));

        if (clamped.compareTo(MIN_TRAVEL_TIME) < 0) return MIN_TRAVEL_TIME;
        if (clamped.compareTo(MAX_TRAVEL_TIME) > 0) return MAX_TRAVEL_TIME;
        return clamped;
    }

    /**
     * Begin a journey between zones.
     * Generates narrative from telemetry data.
     */
    public Journey depart(String agentId, String sourceZoneId, String targetZoneId,
                          TelemetrySnapshot telemetry) {
        var travelTime = calculateTravelTime(telemetry.latencyMs());
        var narrative = telemetryMapper.generateNarrative(telemetry, sourceZoneId, targetZoneId);

        var journeyId = "journey-" + nextJourneyId++;
        var journey = new Journey(journeyId, agentId, sourceZoneId, targetZoneId,
            telemetry.latencyMs(), travelTime, Instant.now(), null,
            JourneyStatus.IN_TRANSIT, narrative);

        activeJourneys.put(journeyId, journey);
        return journey;
    }

    /**
     * Check if a journey has completed (enough time elapsed).
     * Returns updated journey with ARRIVED status if complete.
     */
    public Journey checkArrival(String journeyId) {
        var journey = activeJourneys.get(journeyId);
        if (journey == null) return null;

        if (journey.isComplete()) return journey;

        if (journey.elapsed().compareTo(journey.travelTime()) >= 0) {
            var arrived = new Journey(journey.journeyId(), journey.agentId(),
                journey.sourceZoneId(), journey.targetZoneId(),
                journey.rttMs(), journey.travelTime(), journey.departedAt(),
                Instant.now(), JourneyStatus.ARRIVED, journey.narrative());
            activeJourneys.put(journeyId, arrived);
            return arrived;
        }

        return journey;
    }

    /** Cancel a journey (agent disconnected, etc). */
    public Journey cancelJourney(String journeyId) {
        var journey = activeJourneys.get(journeyId);
        if (journey == null || journey.isComplete()) return journey;

        var failed = new Journey(journey.journeyId(), journey.agentId(),
            journey.sourceZoneId(), journey.targetZoneId(),
            journey.rttMs(), journey.travelTime(), journey.departedAt(),
            Instant.now(), JourneyStatus.FAILED, journey.narrative());
        activeJourneys.put(journeyId, failed);
        return failed;
    }

    /** Get a journey by ID. */
    public Optional<Journey> getJourney(String journeyId) {
        return Optional.ofNullable(activeJourneys.get(journeyId));
    }

    /** All active (non-complete) journeys. */
    public List<Journey> activeJourneys() {
        return activeJourneys.values().stream()
            .filter(j -> !j.isComplete())
            .toList();
    }

    /** All journeys for an agent. */
    public List<Journey> journeysFor(String agentId) {
        return activeJourneys.values().stream()
            .filter(j -> j.agentId().equals(agentId))
            .toList();
    }

    /** Count of completed journeys. */
    public int completedCount() {
        return (int) activeJourneys.values().stream()
            .filter(Journey::hasArrived)
            .count();
    }

    /** Get the telemetry mapper for direct narrative generation. */
    public TelemetryMapper mapper() {
        return telemetryMapper;
    }
}
