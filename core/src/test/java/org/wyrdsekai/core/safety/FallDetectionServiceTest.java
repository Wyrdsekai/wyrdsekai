package org.wyrdsekai.core.safety;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §99.8 — Fall Detection & Health Anomaly Monitoring.
 */
class FallDetectionServiceTest {

    private FallDetectionService service;

    @BeforeEach
    void setup() {
        service = new FallDetectionService();
        service.setResponseTimeout(Duration.ofMinutes(5));
        service.addEmergencyContact(new FallDetectionService.EmergencyContact(
            "Daughter", "phone", "+1-555-0100", 1));
        service.addEmergencyContact(new FallDetectionService.EmergencyContact(
            "Neighbor", "sms", "+1-555-0200", 2));
    }

    @Test
    void fall_detected_creates_alert() {
        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of("acceleration", "high", "motion_after", "none"),
            Instant.now());

        var alert = service.processSensorEvent(event);
        assertTrue(alert.isPresent());
        assertEquals(FallDetectionService.AlertState.CHECKING_IN, alert.get().state());
        assertEquals(1, service.activeAlertCount());
    }

    @Test
    void low_severity_no_alert() {
        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.2, Map.of(), Instant.now());

        var alert = service.processSensorEvent(event);
        assertTrue(alert.isEmpty());
        assertEquals(0, service.activeAlertCount());
    }

    @Test
    void emergency_button_always_alerts() {
        var event = new FallDetectionService.SensorEvent(
            "button-1", FallDetectionService.EventType.EMERGENCY_BUTTON,
            0.1, // low severity shouldn't matter
            Map.of(), Instant.now());

        var alert = service.processSensorEvent(event);
        assertTrue(alert.isPresent());
    }

    @Test
    void activity_checkin_never_alerts() {
        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.ACTIVITY_CHECKIN,
            1.0, Map.of(), Instant.now());

        var alert = service.processSensorEvent(event);
        assertTrue(alert.isEmpty());
    }

    @Test
    void person_responds_ok() {
        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.8, Map.of(), Instant.now());

        var alert = service.processSensorEvent(event).orElseThrow();
        assertTrue(service.respondToAlert(alert.alertId(), true, "Just tripped, I'm fine"));

        assertEquals(0, service.activeAlertCount());
        assertEquals(1, service.history().size());
        assertEquals(FallDetectionService.AlertState.FALSE_ALARM,
            service.history().getFirst().state());
    }

    @Test
    void person_responds_not_ok() {
        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of(), Instant.now());

        var alert = service.processSensorEvent(event).orElseThrow();
        service.respondToAlert(alert.alertId(), false, "I fell and it hurts");

        assertEquals(FallDetectionService.AlertState.RESOLVED,
            service.history().getFirst().state());
    }

    @Test
    void timeout_escalation() {
        service.setResponseTimeout(Duration.ZERO); // immediate timeout for testing

        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of(), Instant.now());

        service.processSensorEvent(event);
        var escalated = service.checkTimeouts();

        assertEquals(1, escalated.size());
        assertEquals(FallDetectionService.AlertState.ESCALATING, escalated.getFirst().state());
    }

    @Test
    void contacts_notified_after_escalation() {
        service.setResponseTimeout(Duration.ZERO);

        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of(), Instant.now());

        var alert = service.processSensorEvent(event).orElseThrow();
        service.checkTimeouts();

        assertTrue(service.markContactsNotified(alert.alertId(), "Daughter"));
        assertEquals(0, service.activeAlertCount());
        assertEquals(FallDetectionService.AlertState.CONTACTS_NOTIFIED,
            service.history().getFirst().state());
    }

    @Test
    void respond_to_nonexistent_alert() {
        assertFalse(service.respondToAlert("nonexistent", true, null));
    }

    @Test
    void emergency_contacts_sorted_by_priority() {
        var contacts = service.emergencyContacts();
        assertEquals(2, contacts.size());
        assertEquals("Daughter", contacts.getFirst().name()); // priority 1
        assertEquals("Neighbor", contacts.get(1).name());     // priority 2
    }

    @Test
    void remove_emergency_contact() {
        service.removeEmergencyContact("Neighbor");
        assertEquals(1, service.emergencyContacts().size());
    }

    @Test
    void generate_check_in_messages() {
        var fall = new FallDetectionService.SensorEvent(
            "s1", FallDetectionService.EventType.FALL_DETECTED, 0.9, Map.of(), Instant.now());
        assertTrue(service.generateCheckIn(fall).contains("okay"));

        var noMotion = new FallDetectionService.SensorEvent(
            "s1", FallDetectionService.EventType.NO_MOTION, 0.7, Map.of(), Instant.now());
        assertTrue(service.generateCheckIn(noMotion).contains("movement"));

        var meds = new FallDetectionService.SensorEvent(
            "s1", FallDetectionService.EventType.MEDICATION_MISSED, 0.6, Map.of(), Instant.now());
        assertTrue(service.generateCheckIn(meds).contains("medication"));
    }

    @Test
    void handler_notified_on_alert() {
        var lastAlert = new AtomicReference<FallDetectionService.Alert>();
        service.addHandler(lastAlert::set);

        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of(), Instant.now());

        service.processSensorEvent(event);
        assertNotNull(lastAlert.get());
        assertEquals(FallDetectionService.AlertState.CHECKING_IN, lastAlert.get().state());
    }

    @Test
    void handler_error_does_not_crash() {
        service.addHandler(alert -> { throw new RuntimeException("Handler crash"); });

        var event = new FallDetectionService.SensorEvent(
            "motion-1", FallDetectionService.EventType.FALL_DETECTED,
            0.9, Map.of(), Instant.now());

        // Should not throw
        var alert = service.processSensorEvent(event);
        assertTrue(alert.isPresent());
    }

    @Test
    void history_filtered_by_type() {
        service.processSensorEvent(new FallDetectionService.SensorEvent(
            "s1", FallDetectionService.EventType.FALL_DETECTED, 0.9, Map.of(), Instant.now()));
        service.processSensorEvent(new FallDetectionService.SensorEvent(
            "s2", FallDetectionService.EventType.NO_MOTION, 0.8, Map.of(), Instant.now()));

        // Resolve both
        for (var alert : service.activeAlerts()) {
            service.respondToAlert(alert.alertId(), true, "Fine");
        }

        assertEquals(1, service.history(FallDetectionService.EventType.FALL_DETECTED).size());
        assertEquals(1, service.history(FallDetectionService.EventType.NO_MOTION).size());
    }

    @Test
    void custom_threshold() {
        service.setAlertThreshold(0.8);

        var lowEvent = new FallDetectionService.SensorEvent(
            "s1", FallDetectionService.EventType.FALL_DETECTED, 0.7, Map.of(), Instant.now());
        assertTrue(service.processSensorEvent(lowEvent).isEmpty());

        var highEvent = new FallDetectionService.SensorEvent(
            "s2", FallDetectionService.EventType.FALL_DETECTED, 0.9, Map.of(), Instant.now());
        assertTrue(service.processSensorEvent(highEvent).isPresent());
    }
}
