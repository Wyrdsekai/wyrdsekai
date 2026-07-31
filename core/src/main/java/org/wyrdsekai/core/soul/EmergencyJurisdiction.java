package org.wyrdsekai.core.soul;

import java.util.Optional;

/**
 * Wave 4.2: jurisdiction-aware emergency-call
 * routing. The companion's {@code emergency_call} action resolves a
 * destination from the steward's configured jurisdiction at request
 * time — the agent does not embed numbers in their substrate.
 *
 * <p>Two number families per jurisdiction:
 * <ul>
 *   <li><b>{@link #generalEmergency()}</b> — general dispatch (911/999/112).
 *       Used for imminent physical harm.</li>
 *   <li><b>{@link #mentalHealthLine()}</b> — mental-health crisis line
 *       (988-equivalent). Used for suicidal ideation without method/timeline.</li>
 * </ul>
 *
 * <p>The companion never <i>infers</i> the jurisdiction — it must be
 * configured by the steward at install time. Without configuration the
 * action returns {@link #UNKNOWN}, which the handler surfaces as
 * "I cannot route — steward must configure jurisdiction" rather than
 * guessing.
 */
public enum EmergencyJurisdiction {
    US("911",   "988"),
    UK("999",   "116123"),     // Samaritans
    EU("112",   "116123"),     // EU-wide Samaritans-equivalent
    JP_FIRE("119", "0570064556"),  // 119 = fire/ambulance; mental-health "yorisoi" line
    JP_POLICE("110", "0570064556"),
    AU("000",   "131114"),     // Lifeline AU
    CA("911",   "988"),
    NZ("111",   "1737"),
    UNKNOWN("",   "");

    private final String generalEmergency;
    private final String mentalHealthLine;

    EmergencyJurisdiction(String generalEmergency, String mentalHealthLine) {
        this.generalEmergency = generalEmergency;
        this.mentalHealthLine = mentalHealthLine;
    }

    public String generalEmergency() {
        return generalEmergency;
    }

    public String mentalHealthLine() {
        return mentalHealthLine;
    }

    public boolean isConfigured() {
        return this != UNKNOWN && !generalEmergency.isEmpty();
    }

    /**
     * Resolve a jurisdiction code from a steward-configured string.
     * Accepts ISO country codes ({@code "US"}, {@code "GB"}, etc.) and
     * common aliases ({@code "uk"}, {@code "japan"}). Returns
     * {@link #UNKNOWN} for blanks / unrecognised input — caller surfaces
     * the gap to the steward.
     */
    public static Optional<EmergencyJurisdiction> resolve(String steward) {
        if (steward == null || steward.isBlank()) return Optional.of(UNKNOWN);
        var s = steward.trim().toUpperCase();
        return switch (s) {
            case "US", "USA", "UNITED STATES", "AMERICA" -> Optional.of(US);
            case "UK", "GB", "BRITAIN", "ENGLAND", "SCOTLAND", "WALES" -> Optional.of(UK);
            case "EU", "GERMANY", "FRANCE", "SPAIN", "ITALY", "NETHERLANDS",
                 "BELGIUM", "AUSTRIA", "POLAND" -> Optional.of(EU);
            case "JP", "JAPAN" -> Optional.of(JP_FIRE);
            case "AU", "AUSTRALIA" -> Optional.of(AU);
            case "CA", "CANADA" -> Optional.of(CA);
            case "NZ", "NEW ZEALAND" -> Optional.of(NZ);
            default -> Optional.of(UNKNOWN);
        };
    }
}
