package org.wyrdsekai.core.companion;

import java.util.*;

/**
 * Specialized companion behavior for aging users (§99).
 *
 * Key principle: the companion is a PRESENCE, not a replacement.
 * Japanese framing: Ibis (tsukumogami) — objects that gain a soul through
 * long presence. Memory is the primary function.
 *
 * Medical, household, and social events are automatically elevated
 * to high significance for the memory system. The companion adapts
 * without diagnosing or condescending.
 *
 * Integration points:
 * - FallDetectionService (§99.8): sensor-based health monitoring
 * - ScamDetector (§99.5): financial protection (inform, never block)
 * - Hearth room MCP: IoT/home automation
 * - A2A: family bridge coordination
 */
public class AgingCompanionMode {

    /**
     * Profile for an aging companion's human.
     * Set by the PERSON, not by family members.
     * Health data never leaves household without explicit grant.
     *
     * @param name              Person's preferred name
     * @param medications       Current medications (for reminders)
     * @param emergencyContacts Emergency contact names (details in FallDetectionService)
     * @param wakeHour          Typical waking hour (0-23)
     * @param sleepHour         Typical sleep hour (0-23)
     * @param medicalConditions Known conditions (for context, not diagnosis)
     * @param preferences       Personalization (e.g., "preferred_greeting" -> "Good morning, dear")
     */
    public record AgingProfile(
        String name,
        List<String> medications,
        List<String> emergencyContacts,
        int wakeHour,
        int sleepHour,
        List<String> medicalConditions,
        Map<String, String> preferences
    ) {
        /** Create a minimal profile with just a name and schedule. */
        public static AgingProfile minimal(String name, int wakeHour, int sleepHour) {
            return new AgingProfile(name, List.of(), List.of(),
                wakeHour, sleepHour, List.of(), Map.of());
        }
    }

    /** Memory significance categories. */
    public static final String CATEGORY_MEDICAL = "medical";
    public static final String CATEGORY_HOUSEHOLD = "household";
    public static final String CATEGORY_SOCIAL = "social";
    public static final String CATEGORY_ROUTINE = "routine";
    public static final String CATEGORY_UNKNOWN = "unknown";

    /** Anomaly severity levels. */
    public enum AnomalyLevel {
        NONE,       // Within normal range
        LOW,        // Slightly unusual, companion notes internally
        MODERATE,   // Worth a gentle check-in
        HIGH,       // Contact emergency list candidate
        CRITICAL    // Immediate escalation needed
    }

    /** Reminder types. */
    public enum ReminderType {
        MEDICATION,
        APPOINTMENT,
        SOCIAL
    }

    // ─── Memory Significance ────────────────────────────────────

    private static final Set<String> MEDICAL_KEYWORDS = Set.of(
        "doctor", "hospital", "medication", "medicine", "prescription",
        "pain", "symptom", "appointment", "blood pressure", "diabetes",
        "heart", "surgery", "therapy", "physical", "nurse", "clinic",
        "dizzy", "fell", "fall", "nauseous", "headache", "chest",
        "breathing", "oxygen", "insulin", "dosage", "pill", "pharmacy",
        "ambulance", "emergency", "allergic", "reaction", "diagnosis"
    );

    private static final Set<String> HOUSEHOLD_KEYWORDS = Set.of(
        "groceries", "bills", "repair", "plumber", "electrician",
        "cleaning", "laundry", "cooking", "garbage", "mail", "package",
        "delivery", "keys", "lock", "alarm", "thermostat", "water",
        "gas", "electric", "rent", "mortgage", "insurance", "tax"
    );

    private static final Set<String> SOCIAL_KEYWORDS = Set.of(
        "visit", "birthday", "anniversary", "friend", "family",
        "grandchild", "daughter", "son", "neighbor", "church",
        "club", "meeting", "lunch", "dinner", "call", "phone",
        "letter", "invitation", "wedding", "funeral", "reunion"
    );

    /**
     * Categorize memory content by significance.
     * Medical is always highest priority. Household and social are medium.
     * Everything else is routine.
     *
     * @param content The memory text to categorize
     * @return Significance category
     */
    public String categorizeMemory(String content) {
        if (content == null || content.isBlank()) {
            return CATEGORY_UNKNOWN;
        }

        String lower = content.toLowerCase();

        // Medical keywords take priority
        for (var kw : MEDICAL_KEYWORDS) {
            if (lower.contains(kw)) {
                return CATEGORY_MEDICAL;
            }
        }

        // Household
        for (var kw : HOUSEHOLD_KEYWORDS) {
            if (lower.contains(kw)) {
                return CATEGORY_HOUSEHOLD;
            }
        }

        // Social
        for (var kw : SOCIAL_KEYWORDS) {
            if (lower.contains(kw)) {
                return CATEGORY_SOCIAL;
            }
        }

        return CATEGORY_ROUTINE;
    }

    /**
     * Whether a category is high significance (auto-elevated in memory system).
     * Medical and household are always high.
     *
     * @param category The category from categorizeMemory
     * @return true if high significance
     */
    public boolean isHighSignificance(String category) {
        return CATEGORY_MEDICAL.equals(category) || CATEGORY_HOUSEHOLD.equals(category);
    }

    // ─── Reminders ──────────────────────────────────────────────

    /**
     * Generate a reminder message appropriate for the context.
     * Tone is warm, never clinical or condescending.
     *
     * @param type    Reminder type
     * @param profile The person's aging profile
     * @return Narrative reminder text
     */
    public String generateReminder(ReminderType type, AgingProfile profile) {
        Objects.requireNonNull(type, "reminder type must not be null");
        Objects.requireNonNull(profile, "profile must not be null");

        String greeting = profile.preferences().getOrDefault(
            "preferred_greeting", "Hello, " + profile.name());

        return switch (type) {
            case MEDICATION -> {
                if (profile.medications().isEmpty()) {
                    yield greeting + ". No medications are listed in your profile.";
                }
                var meds = String.join(", ", profile.medications());
                yield greeting + ". It might be time for your medication: " + meds + ". " +
                      "Take them when you're ready — no rush.";
            }
            case APPOINTMENT ->
                greeting + ". You have an appointment coming up. " +
                "Would you like me to help you prepare?";
            case SOCIAL -> {
                if (profile.emergencyContacts().isEmpty()) {
                    yield greeting + ". It's been a while since you've connected with someone. " +
                          "Would you like to reach out?";
                }
                var contact = profile.emergencyContacts().get(0);
                yield greeting + ". It's been a little while since you spoke with " +
                      contact + ". Would you like to give them a call?";
            }
        };
    }

    // ─── Activity Anomaly Detection ─────────────────────────────

    /**
     * Assess anomaly level based on inactivity duration during waking hours.
     * Integrates with FallDetectionService for sensor-based detection.
     *
     * @param hoursNoMotion    Hours without detected motion
     * @param wakingHourStart  Expected waking hour start
     * @param wakingHourEnd    Expected waking hour end
     * @return Anomaly severity level
     */
    public AnomalyLevel assessActivityAnomaly(int hoursNoMotion,
                                               int wakingHourStart,
                                               int wakingHourEnd) {
        // Outside waking hours, inactivity is expected
        // This method is called with context — caller knows current hour
        // We assess purely based on duration

        if (hoursNoMotion <= 0) {
            return AnomalyLevel.NONE;
        }

        // Short inactivity is normal (nap, reading, etc.)
        if (hoursNoMotion <= 2) {
            return AnomalyLevel.NONE;
        }

        // 2-4 hours: slightly unusual during waking hours
        if (hoursNoMotion <= 4) {
            return AnomalyLevel.LOW;
        }

        // 4-6 hours: worth checking in
        if (hoursNoMotion <= 6) {
            return AnomalyLevel.MODERATE;
        }

        // 6-8 hours: concerning — contact list candidate
        if (hoursNoMotion <= 8) {
            return AnomalyLevel.HIGH;
        }

        // 8+ hours during waking hours: critical
        return AnomalyLevel.CRITICAL;
    }

    /**
     * Generate a check-in message appropriate for the anomaly level.
     * Gentle and non-alarming. Companion informs, never diagnoses.
     *
     * @param level   Anomaly severity
     * @param profile Person's profile
     * @return Check-in message text
     */
    public String generateCheckIn(AnomalyLevel level, AgingProfile profile) {
        String name = profile.name();

        return switch (level) {
            case NONE -> "";
            case LOW -> name + ", it's been quiet for a while. Everything okay?";
            case MODERATE -> name + ", I haven't noticed any activity in some time. " +
                "Just checking in — are you all right?";
            case HIGH -> name + ", it's been several hours without any activity. " +
                "I'm a little concerned. Can you let me know you're okay? " +
                "If I don't hear from you soon, I'll reach out to your contacts.";
            case CRITICAL -> name + ", I haven't been able to reach you. " +
                "I'm going to notify your emergency contacts now. " +
                "If you can hear me, please respond.";
        };
    }
}
