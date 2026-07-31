package org.wyrdsekai.common.model;

/**
 * Conversational hint (§65.2) — suggested actions presented to the player.
 *
 * @param label    Display text (e.g. "My photos are a mess")
 * @param intent   Semantic intent for the system (e.g. "organize_photos")
 * @param action   Action type — "say", "go", "use", "take", "look", "command"
 * @param labelKey Optional i18n key — clients with i18n support can translate locally
 */
public record Hint(String label, String intent, String action, String labelKey) {

    /** Backward-compatible constructor — labelKey defaults to null. */
    public Hint(String label, String intent, String action) {
        this(label, intent, action, null);
    }
}
