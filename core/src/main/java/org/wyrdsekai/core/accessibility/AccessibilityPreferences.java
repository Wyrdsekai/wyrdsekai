package org.wyrdsekai.core.accessibility;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

/**
 * Per-user accessibility preferences (§103.8).
 * Stored per HouseholdMember, applied to all agent output.
 * MUDs are inherently accessible — this extends, never removes.
 *
 * Explicit preferences set by user. Implicit adaptation (emergent
 * from interaction patterns) handled by CompanionActor, not stored here.
 */
public record AccessibilityPreferences(
    @JsonProperty("screenReader") boolean screenReader,
    @JsonProperty("plainLanguage") boolean plainLanguage,
    @JsonProperty("reducedMotion") boolean reducedMotion,
    @JsonProperty("highContrast") boolean highContrast,
    @JsonProperty("voicePrimary") boolean voicePrimary,
    @JsonProperty("dyslexiaFont") boolean dyslexiaFont,
    @JsonProperty("shortMessages") boolean shortMessages,
    @JsonProperty("noEmoji") boolean noEmoji,
    @JsonProperty("ttsEnabled") boolean ttsEnabled,
    @JsonProperty("ttsRate") double ttsRate,
    @JsonProperty("ttsVoice") String ttsVoice,
    @JsonProperty("sttEnabled") boolean sttEnabled,
    @JsonProperty("sttLanguage") String sttLanguage,
    @JsonProperty("audioCues") boolean audioCues,
    @JsonProperty("sessionTimeoutMinutes") int sessionTimeoutMinutes,
    @JsonProperty("extraPreferences") Set<String> extraPreferences
) {

    @JsonCreator
    public AccessibilityPreferences {}

    /** Default — no special accommodations. */
    public static AccessibilityPreferences defaults() {
        return new AccessibilityPreferences(
            false, false, false, false, false, false, false, false,
            false, 1.0, "default", false, "auto", false, 0, Set.of()
        );
    }

    /** Screen-reader optimized profile. */
    public static AccessibilityPreferences screenReaderProfile() {
        return new AccessibilityPreferences(
            true, true, true, true, false, false, false, true,
            true, 1.0, "default", false, "auto", true, 0, Set.of()
        );
    }

    /** Voice-primary profile (driving, low vision, motor). */
    public static AccessibilityPreferences voicePrimaryProfile() {
        return new AccessibilityPreferences(
            false, true, false, false, true, false, true, false,
            true, 1.0, "default", true, "auto", true, 0, Set.of()
        );
    }

    /** Cognitive accessibility profile (ADHD, cognitive load). */
    public static AccessibilityPreferences cognitiveProfile() {
        return new AccessibilityPreferences(
            false, true, true, false, false, false, true, true,
            false, 1.0, "default", false, "auto", false, 60, Set.of()
        );
    }

    /** Generate a prompt addendum for CompanionActor output adaptation. */
    public String promptAddendum() {
        var sb = new StringBuilder();
        if (plainLanguage) sb.append("Use simple, clear language. Avoid jargon and metaphor. ");
        if (shortMessages) sb.append("Keep responses brief — 2-3 sentences maximum. ");
        if (noEmoji) sb.append("Do not use emoji or special Unicode characters. ");
        if (screenReader) sb.append("Structure output for screen readers: use clear headings, avoid tables, describe spatial relationships verbally. ");
        if (dyslexiaFont) sb.append("Use shorter sentences and simpler words. ");
        if (voicePrimary) sb.append("Format for speech: avoid visual formatting, use natural spoken phrasing. ");
        return sb.toString().trim();
    }

    /** Whether any accommodations are active. */
    public boolean hasAccommodations() {
        return screenReader || plainLanguage || reducedMotion || highContrast
            || voicePrimary || dyslexiaFont || shortMessages || noEmoji
            || ttsEnabled || sttEnabled || audioCues || sessionTimeoutMinutes > 0
            || !extraPreferences.isEmpty();
    }

    /** Check if a specific extra preference is set. */
    public boolean hasExtra(String preference) {
        return extraPreferences != null && extraPreferences.contains(preference);
    }

    /** Builder-style with for immutable record. */
    public AccessibilityPreferences withTts(boolean enabled, double rate, String voice) {
        return new AccessibilityPreferences(screenReader, plainLanguage, reducedMotion,
            highContrast, voicePrimary, dyslexiaFont, shortMessages, noEmoji,
            enabled, rate, voice, sttEnabled, sttLanguage, audioCues,
            sessionTimeoutMinutes, extraPreferences);
    }

    public AccessibilityPreferences withStt(boolean enabled, String language) {
        return new AccessibilityPreferences(screenReader, plainLanguage, reducedMotion,
            highContrast, voicePrimary, dyslexiaFont, shortMessages, noEmoji,
            ttsEnabled, ttsRate, ttsVoice, enabled, language, audioCues,
            sessionTimeoutMinutes, extraPreferences);
    }
}
