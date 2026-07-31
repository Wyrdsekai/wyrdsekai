package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Represents the agent's awareness of the human's physical location.
 *
 * <p>Updated by the phone node via GPS data (published as {@link AgentEvent.LocationUpdate}
 * events through the {@link AgentEventStream}). The agent uses this to provide
 * context-aware responses ("You're heading out -- anything you need before you go?").</p>
 *
 * <p>Follows the same singleton pattern as {@link WatcherService} and
 * {@link NotificationService}: initialized at startup, accessed via {@link #get()}.</p>
 *
 * @see AgentEvent.LocationUpdate
 * @see CalendarContext
 */
public class LocationContext {

    /** Location states inferred from name or geofence. */
    public enum LocationState { HOME, AWAY, WORK, COMMUTING, UNKNOWN }

    private volatile LocationState currentState = LocationState.UNKNOWN;
    private volatile LocationState previousState = LocationState.UNKNOWN;
    private volatile String locationName;
    private volatile double latitude;
    private volatile double longitude;
    private volatile Instant lastUpdated;

    /** Global instance -- initialized by Main.java at startup. */
    private static volatile LocationContext instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new LocationContext(); }

    /** Get the global instance. May be null if not initialized. */
    public static LocationContext get() { return instance; }

    /** Reset for testing. */
    static void reset() { instance = null; }

    /**
     * Update from phone GPS data.
     *
     * @param lat  latitude
     * @param lon  longitude
     * @param name human-readable location name (e.g. "home", "office", "Trader Joe's")
     */
    public void update(double lat, double lon, String name) {
        this.previousState = this.currentState;
        this.latitude = lat;
        this.longitude = lon;
        this.locationName = name;
        this.currentState = inferState(name);
        this.lastUpdated = Instant.now();
    }

    /**
     * Update from a full {@link AgentEvent.LocationUpdate} event.
     */
    public void update(AgentEvent.LocationUpdate event) {
        this.previousState = this.currentState;
        this.latitude = event.latitude();
        this.longitude = event.longitude();
        this.locationName = event.locationName();
        this.currentState = event.state() != null ? event.state() : inferState(event.locationName());
        this.lastUpdated = event.timestamp();
    }

    /**
     * Infer state from location name via keyword matching.
     * Future: geofence zones configured by steward.
     */
    public LocationState inferState(String name) {
        if (name == null || name.isBlank()) return LocationState.UNKNOWN;
        String lower = name.toLowerCase().strip();
        if (lower.contains("home") || lower.contains("house") || lower.contains("apartment")) {
            return LocationState.HOME;
        }
        if (lower.contains("office") || lower.contains("work") || lower.contains("workplace")
                || lower.contains("studio") || lower.contains("coworking")) {
            return LocationState.WORK;
        }
        if (lower.contains("commut") || lower.contains("transit") || lower.contains("train")
                || lower.contains("bus") || lower.contains("subway") || lower.contains("driving")) {
            return LocationState.COMMUTING;
        }
        // Named location that doesn't match known patterns = AWAY
        return LocationState.AWAY;
    }

    /**
     * Build context string for the agent's prompt.
     * Returns null if location is unknown (gracefully absent).
     */
    public String buildContext() {
        if (currentState == LocationState.UNKNOWN || lastUpdated == null) return null;
        var sb = new StringBuilder("## Human Location\n");
        if (locationName != null && !locationName.isBlank()) {
            sb.append(locationName);
        } else {
            sb.append(currentState.name().toLowerCase());
        }
        sb.append(" (").append(currentState).append(", updated ");
        long minutes = Duration.between(lastUpdated, Instant.now()).toMinutes();
        if (minutes <= 0) {
            sb.append("just now");
        } else {
            sb.append(minutes).append("m ago");
        }
        sb.append(")\n");
        return sb.toString();
    }

    /** How long since the last location update. */
    public Duration timeSinceUpdate() {
        if (lastUpdated == null) return Duration.ofDays(365); // effectively never
        return Duration.between(lastUpdated, Instant.now());
    }

    /** Whether the location recently changed state (e.g. HOME -> AWAY). */
    public boolean hasStateChanged() {
        return previousState != currentState && currentState != LocationState.UNKNOWN;
    }

    public LocationState currentState() { return currentState; }
    public LocationState previousState() { return previousState; }
    public String locationName() { return locationName; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public Instant lastUpdated() { return lastUpdated; }
}
