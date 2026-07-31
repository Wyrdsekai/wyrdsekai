package org.wyrdsekai.core.safety;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fall detection and health anomaly monitoring for aging companions (§99.8).
 *
 * Integrates with Hearth room MCP (motion sensors, wearables) to:
 * 1. Detect potential falls (sudden acceleration → stop pattern)
 * 2. Monitor activity patterns (no motion for extended periods)
 * 3. Track health anomalies (sleep changes, medication missed)
 * 4. Route emergency contacts when no response received
 *
 * Data flow: Sensor → MCP → Hearth room script → FallDetectionService
 *
 * Key principle (§99): the companion INFORMS, never blocks.
 * Emergency contact list is set by the PERSON, not family.
 * Health data never leaves household without explicit grant.
 */
public class FallDetectionService {

    /** A detected event from sensors. */
    public record SensorEvent(
        String sensorId,
        EventType type,
        double severity,  // 0.0 = noise, 1.0 = critical
        Map<String, String> data,
        Instant timestamp
    ) {}

    public enum EventType {
        /** Sudden acceleration followed by no motion. */
        FALL_DETECTED,
        /** No motion during expected waking hours. */
        NO_MOTION,
        /** Medication not taken at scheduled time. */
        MEDICATION_MISSED,
        /** Sleep pattern deviation (2+ hours from baseline). */
        SLEEP_ANOMALY,
        /** Heart rate anomaly (from wearable). */
        HEART_RATE_ANOMALY,
        /** Manual emergency button press. */
        EMERGENCY_BUTTON,
        /** Regular motion/activity check-in. */
        ACTIVITY_CHECKIN
    }

    /** Alert state for an ongoing concern. */
    public enum AlertState {
        /** Event detected, companion checking in verbally. */
        CHECKING_IN,
        /** Person responded, situation resolved. */
        RESOLVED,
        /** No response within timeout, contacting emergency list. */
        ESCALATING,
        /** Emergency contacts notified. */
        CONTACTS_NOTIFIED,
        /** False alarm acknowledged by person. */
        FALSE_ALARM
    }

    /** An active alert tracking a detected event. */
    public record Alert(
        String alertId,
        SensorEvent triggerEvent,
        AlertState state,
        Instant createdAt,
        Instant lastUpdated,
        String responseNotes
    ) {
        public Alert withState(AlertState newState, String notes) {
            return new Alert(alertId, triggerEvent, newState, createdAt, Instant.now(),
                notes != null ? notes : responseNotes);
        }
    }

    /** Emergency contact. */
    public record EmergencyContact(
        String name,
        String method,    // "phone", "sms", "email", "a2a"
        String address,   // phone number, email, DID
        int priority      // lower = higher priority
    ) {}

    /** Alert handler callback — for integration with companion/room system. */
    @FunctionalInterface
    public interface AlertHandler {
        void handle(Alert alert);
    }

    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();
    private final List<Alert> alertHistory = Collections.synchronizedList(new ArrayList<>());
    private final List<EmergencyContact> emergencyContacts = new ArrayList<>();
    private final List<AlertHandler> handlers = new ArrayList<>();
    private int nextAlertId = 1;

    /** Time to wait for person's response before escalating. */
    private Duration responseTimeout = Duration.ofMinutes(5);

    /** Minimum severity to trigger an alert. */
    private double alertThreshold = 0.5;

    /** Baseline activity patterns — hours of expected motion. */
    private int wakingHourStart = 7;
    private int wakingHourEnd = 22;

    // --- Configuration ---

    public void setResponseTimeout(Duration timeout) {
        this.responseTimeout = timeout;
    }

    public void setAlertThreshold(double threshold) {
        this.alertThreshold = threshold;
    }

    public void setWakingHours(int start, int end) {
        this.wakingHourStart = start;
        this.wakingHourEnd = end;
    }

    public void addEmergencyContact(EmergencyContact contact) {
        emergencyContacts.add(contact);
        emergencyContacts.sort(Comparator.comparingInt(EmergencyContact::priority));
    }

    public void removeEmergencyContact(String name) {
        emergencyContacts.removeIf(c -> c.name().equals(name));
    }

    public List<EmergencyContact> emergencyContacts() {
        return List.copyOf(emergencyContacts);
    }

    public void addHandler(AlertHandler handler) {
        handlers.add(handler);
    }

    // --- Event Processing ---

    /**
     * Process an incoming sensor event. Creates an alert if severity
     * exceeds threshold, or if event type is inherently critical.
     *
     * @return Alert if one was created, empty if event was below threshold
     */
    public Optional<Alert> processSensorEvent(SensorEvent event) {
        // Emergency button always creates alert
        if (event.type() == EventType.EMERGENCY_BUTTON) {
            return Optional.of(createAlert(event));
        }

        // Activity check-ins just update baseline, no alert
        if (event.type() == EventType.ACTIVITY_CHECKIN) {
            return Optional.empty();
        }

        // Check severity threshold
        if (event.severity() < alertThreshold) {
            return Optional.empty();
        }

        return Optional.of(createAlert(event));
    }

    /**
     * Record a person's response to an active alert.
     * Moves alert to RESOLVED or FALSE_ALARM state.
     */
    public boolean respondToAlert(String alertId, boolean isOkay, String notes) {
        var alert = activeAlerts.get(alertId);
        if (alert == null) return false;

        var newState = isOkay ? AlertState.FALSE_ALARM : AlertState.RESOLVED;
        var updated = alert.withState(newState, notes);
        activeAlerts.remove(alertId);
        alertHistory.add(updated);
        notifyHandlers(updated);
        return true;
    }

    /**
     * Check for alerts that have timed out waiting for response.
     * Should be called periodically (e.g., every 30 seconds).
     *
     * @return Alerts that were escalated
     */
    public List<Alert> checkTimeouts() {
        var now = Instant.now();
        var escalated = new ArrayList<Alert>();

        for (var alert : activeAlerts.values()) {
            if (alert.state() == AlertState.CHECKING_IN
                && Duration.between(alert.createdAt(), now).compareTo(responseTimeout) > 0) {
                var updated = alert.withState(AlertState.ESCALATING,
                    "No response after " + responseTimeout.toMinutes() + " minutes");
                activeAlerts.put(alert.alertId(), updated);
                escalated.add(updated);
                notifyHandlers(updated);
            }
        }

        return escalated;
    }

    /**
     * Mark an escalated alert as contacts-notified.
     * Called after emergency contacts have been reached.
     */
    public boolean markContactsNotified(String alertId, String contactedNames) {
        var alert = activeAlerts.get(alertId);
        if (alert == null || alert.state() != AlertState.ESCALATING) return false;

        var updated = alert.withState(AlertState.CONTACTS_NOTIFIED,
            "Contacted: " + contactedNames);
        activeAlerts.remove(alertId);
        alertHistory.add(updated);
        notifyHandlers(updated);
        return true;
    }

    /**
     * Generate the verbal check-in message for a detected event.
     * The companion says this to the person.
     */
    public String generateCheckIn(SensorEvent event) {
        return switch (event.type()) {
            case FALL_DETECTED -> "Are you okay? I noticed a sudden stop. Can you let me know you're all right?";
            case NO_MOTION -> "I haven't noticed any movement in a while. Is everything okay?";
            case MEDICATION_MISSED -> "It looks like it might be time for your medication. Would you like a reminder?";
            case SLEEP_ANOMALY -> "I noticed your sleep pattern was different last night. How are you feeling?";
            case HEART_RATE_ANOMALY -> "I noticed something unusual with your heart rate. How are you feeling?";
            case EMERGENCY_BUTTON -> "I see you pressed the emergency button. I'm here — what do you need?";
            case ACTIVITY_CHECKIN -> "";
        };
    }

    // --- Query ---

    public List<Alert> activeAlerts() {
        return List.copyOf(activeAlerts.values());
    }

    public int activeAlertCount() {
        return activeAlerts.size();
    }

    public List<Alert> history() {
        return List.copyOf(alertHistory);
    }

    public List<Alert> history(EventType type) {
        return alertHistory.stream()
            .filter(a -> a.triggerEvent().type() == type)
            .toList();
    }

    // --- Internal ---

    private Alert createAlert(SensorEvent event) {
        var alert = new Alert(
            "fall-" + nextAlertId++, event, AlertState.CHECKING_IN,
            Instant.now(), Instant.now(), null);
        activeAlerts.put(alert.alertId(), alert);
        notifyHandlers(alert);
        return alert;
    }

    private void notifyHandlers(Alert alert) {
        for (var handler : handlers) {
            try {
                handler.handle(alert);
            } catch (Exception e) {
                // Handler errors must not break alert processing
            }
        }
    }
}
