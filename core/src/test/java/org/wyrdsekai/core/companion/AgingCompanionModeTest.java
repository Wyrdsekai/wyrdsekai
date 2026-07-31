package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgingCompanionMode (§99).
 */
class AgingCompanionModeTest {

    private AgingCompanionMode mode;

    @BeforeEach
    void setup() {
        mode = new AgingCompanionMode();
    }

    @Nested
    class MemoryCategorization {

        @Test
        void categorizes_medical_content() {
            assertEquals("medical", mode.categorizeMemory("Doctor appointment at 3pm"));
            assertEquals("medical", mode.categorizeMemory("Need to pick up prescription from pharmacy"));
            assertEquals("medical", mode.categorizeMemory("Blood pressure was high today"));
            assertEquals("medical", mode.categorizeMemory("I felt dizzy this morning"));
        }

        @Test
        void categorizes_household_content() {
            assertEquals("household", mode.categorizeMemory("Need to buy groceries"));
            assertEquals("household", mode.categorizeMemory("The plumber is coming Tuesday"));
            assertEquals("household", mode.categorizeMemory("Electric bill is due next week"));
        }

        @Test
        void categorizes_social_content() {
            assertEquals("social", mode.categorizeMemory("Birthday party for grandchild on Saturday"));
            assertEquals("social", mode.categorizeMemory("Should call my daughter"));
            assertEquals("social", mode.categorizeMemory("Neighbor invited me for lunch"));
        }

        @Test
        void categorizes_routine_content() {
            assertEquals("routine", mode.categorizeMemory("The sky was beautiful today"));
            assertEquals("routine", mode.categorizeMemory("Watched a nice show on television"));
        }

        @Test
        void handles_null_content() {
            assertEquals("unknown", mode.categorizeMemory(null));
        }

        @Test
        void handles_blank_content() {
            assertEquals("unknown", mode.categorizeMemory("  "));
        }

        @Test
        void medical_takes_priority_over_others() {
            // "doctor visit with friend" — medical keyword should win
            assertEquals("medical", mode.categorizeMemory("doctor visit with friend at the club"));
        }
    }

    @Nested
    class SignificanceTests {

        @Test
        void medical_is_high_significance() {
            assertTrue(mode.isHighSignificance("medical"));
        }

        @Test
        void household_is_high_significance() {
            assertTrue(mode.isHighSignificance("household"));
        }

        @Test
        void social_is_not_high_significance() {
            assertFalse(mode.isHighSignificance("social"));
        }

        @Test
        void routine_is_not_high_significance() {
            assertFalse(mode.isHighSignificance("routine"));
        }

        @Test
        void unknown_is_not_high_significance() {
            assertFalse(mode.isHighSignificance("unknown"));
        }

        @Test
        void null_is_not_high_significance() {
            assertFalse(mode.isHighSignificance(null));
        }
    }

    @Nested
    class ReminderGeneration {

        private AgingCompanionMode.AgingProfile profileWithMeds;
        private AgingCompanionMode.AgingProfile profileNoMeds;

        @BeforeEach
        void setup() {
            profileWithMeds = new AgingCompanionMode.AgingProfile(
                "Margaret", List.of("Metformin", "Lisinopril"),
                List.of("Alice", "Bob"), 7, 22,
                List.of("diabetes", "hypertension"),
                Map.of("preferred_greeting", "Good morning, dear"));

            profileNoMeds = AgingCompanionMode.AgingProfile.minimal("Tom", 8, 21);
        }

        @Test
        void medication_reminder_includes_meds() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.MEDICATION, profileWithMeds);
            assertTrue(reminder.contains("Metformin"));
            assertTrue(reminder.contains("Lisinopril"));
            assertTrue(reminder.contains("Good morning, dear"));
        }

        @Test
        void medication_reminder_for_no_meds() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.MEDICATION, profileNoMeds);
            assertTrue(reminder.contains("No medications"));
        }

        @Test
        void appointment_reminder() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.APPOINTMENT, profileWithMeds);
            assertTrue(reminder.contains("appointment"));
        }

        @Test
        void social_reminder_includes_contact() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.SOCIAL, profileWithMeds);
            assertTrue(reminder.contains("Alice")); // first emergency contact
        }

        @Test
        void social_reminder_without_contacts() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.SOCIAL, profileNoMeds);
            assertTrue(reminder.contains("reach out"));
            assertFalse(reminder.contains("null"));
        }

        @Test
        void uses_preferred_greeting() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.APPOINTMENT, profileWithMeds);
            assertTrue(reminder.startsWith("Good morning, dear"));
        }

        @Test
        void uses_default_greeting_when_no_preference() {
            var reminder = mode.generateReminder(
                AgingCompanionMode.ReminderType.APPOINTMENT, profileNoMeds);
            assertTrue(reminder.startsWith("Hello, Tom"));
        }

        @Test
        void rejects_null_type() {
            assertThrows(NullPointerException.class,
                () -> mode.generateReminder(null, profileWithMeds));
        }

        @Test
        void rejects_null_profile() {
            assertThrows(NullPointerException.class,
                () -> mode.generateReminder(AgingCompanionMode.ReminderType.MEDICATION, null));
        }
    }

    @Nested
    class AnomalyAssessment {

        @Test
        void no_anomaly_for_zero_hours() {
            assertEquals(AgingCompanionMode.AnomalyLevel.NONE,
                mode.assessActivityAnomaly(0, 7, 22));
        }

        @Test
        void no_anomaly_for_short_inactivity() {
            assertEquals(AgingCompanionMode.AnomalyLevel.NONE,
                mode.assessActivityAnomaly(1, 7, 22));
            assertEquals(AgingCompanionMode.AnomalyLevel.NONE,
                mode.assessActivityAnomaly(2, 7, 22));
        }

        @Test
        void low_anomaly_for_moderate_inactivity() {
            assertEquals(AgingCompanionMode.AnomalyLevel.LOW,
                mode.assessActivityAnomaly(3, 7, 22));
            assertEquals(AgingCompanionMode.AnomalyLevel.LOW,
                mode.assessActivityAnomaly(4, 7, 22));
        }

        @Test
        void moderate_anomaly_for_extended_inactivity() {
            assertEquals(AgingCompanionMode.AnomalyLevel.MODERATE,
                mode.assessActivityAnomaly(5, 7, 22));
            assertEquals(AgingCompanionMode.AnomalyLevel.MODERATE,
                mode.assessActivityAnomaly(6, 7, 22));
        }

        @Test
        void high_anomaly_for_long_inactivity() {
            assertEquals(AgingCompanionMode.AnomalyLevel.HIGH,
                mode.assessActivityAnomaly(7, 7, 22));
            assertEquals(AgingCompanionMode.AnomalyLevel.HIGH,
                mode.assessActivityAnomaly(8, 7, 22));
        }

        @Test
        void critical_for_very_long_inactivity() {
            assertEquals(AgingCompanionMode.AnomalyLevel.CRITICAL,
                mode.assessActivityAnomaly(9, 7, 22));
            assertEquals(AgingCompanionMode.AnomalyLevel.CRITICAL,
                mode.assessActivityAnomaly(12, 7, 22));
        }

        @Test
        void negative_hours_is_none() {
            assertEquals(AgingCompanionMode.AnomalyLevel.NONE,
                mode.assessActivityAnomaly(-1, 7, 22));
        }
    }

    @Nested
    class CheckInMessages {

        private AgingCompanionMode.AgingProfile profile;

        @BeforeEach
        void setup() {
            profile = AgingCompanionMode.AgingProfile.minimal("Margaret", 7, 22);
        }

        @Test
        void none_gives_empty_message() {
            assertEquals("", mode.generateCheckIn(AgingCompanionMode.AnomalyLevel.NONE, profile));
        }

        @Test
        void low_gives_gentle_message() {
            var msg = mode.generateCheckIn(AgingCompanionMode.AnomalyLevel.LOW, profile);
            assertTrue(msg.contains("Margaret"));
            assertTrue(msg.contains("okay"));
        }

        @Test
        void moderate_gives_concerned_message() {
            var msg = mode.generateCheckIn(AgingCompanionMode.AnomalyLevel.MODERATE, profile);
            assertTrue(msg.contains("Margaret"));
            assertTrue(msg.contains("activity"));
        }

        @Test
        void high_mentions_contacts() {
            var msg = mode.generateCheckIn(AgingCompanionMode.AnomalyLevel.HIGH, profile);
            assertTrue(msg.contains("Margaret"));
            assertTrue(msg.contains("contacts"));
        }

        @Test
        void critical_escalates() {
            var msg = mode.generateCheckIn(AgingCompanionMode.AnomalyLevel.CRITICAL, profile);
            assertTrue(msg.contains("Margaret"));
            assertTrue(msg.contains("notify"));
        }
    }

    @Nested
    class AgingProfileTests {

        @Test
        void minimal_profile_creation() {
            var profile = AgingCompanionMode.AgingProfile.minimal("Tom", 8, 21);
            assertEquals("Tom", profile.name());
            assertEquals(8, profile.wakeHour());
            assertEquals(21, profile.sleepHour());
            assertTrue(profile.medications().isEmpty());
            assertTrue(profile.emergencyContacts().isEmpty());
            assertTrue(profile.medicalConditions().isEmpty());
            assertTrue(profile.preferences().isEmpty());
        }

        @Test
        void full_profile_creation() {
            var profile = new AgingCompanionMode.AgingProfile(
                "Margaret",
                List.of("Metformin"),
                List.of("Alice"),
                7, 22,
                List.of("diabetes"),
                Map.of("language", "en"));

            assertEquals(1, profile.medications().size());
            assertEquals(1, profile.emergencyContacts().size());
            assertEquals(1, profile.medicalConditions().size());
            assertEquals("en", profile.preferences().get("language"));
        }
    }
}
