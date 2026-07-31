package org.wyrdsekai.core.parlor;

/**
 * Parlor moderation preset.
 *
 * <p>Operators pick one of three postures rather than hand-tuning every
 * individual rate limit. Presets exist so a small family-only Parlor
 * doesn't need to configure a dozen knobs to get the intended vibe.</p>
 */
public enum ModerationPreset {
    /**
     * Relaxed defaults — fewer limits, looser dedup, no stranger-to-stranger
     * tell in Parlor (only to host companion). For small households with
     * minimal public traffic. Trust is already there; don't get in the way.
     */
    QUIET,

    /**
     * Spec defaults (rate limits from §6.10 table apply as written). For
     * casual social zones.
     */
    NORMAL,

    /**
     * Very low limits, mandatory companion greeting before any stranger can
     * speak, auto-eject on any rate breach, captcha-style prove-you're-human
     * on first join. For high-traffic public zones with harassment risk.
     */
    STRICT;

    /**
     * Multiplier applied to base rate limits. Preset-dependent — STRICT
     * scales windows UP (more cooldown), QUIET scales DOWN (less cooldown).
     */
    public double rateLimitMultiplier() {
        return switch (this) {
            case QUIET -> 0.5;
            case NORMAL -> 1.0;
            case STRICT -> 2.0;
        };
    }

    /**
     * Whether a stranger may tell another stranger in Parlor. STRICT and
     * QUIET both restrict this; NORMAL allows. (QUIET's restriction is
     * "small household feel" — in a family kitchen, anonymous side-chatter
     * is strange.)
     */
    public boolean allowsStrangerToStrangerTell() {
        return this == NORMAL;
    }

    /**
     * Whether first-join requires a companion greeting before the visitor
     * can speak (STRICT-mode anti-bot / anti-flood gate).
     */
    public boolean requiresGreetingBeforeSpeak() {
        return this == STRICT;
    }

    /** Parse with case-insensitive matching; unknown falls back to NORMAL. */
    public static ModerationPreset fromString(String s) {
        if (s == null || s.isBlank()) return NORMAL;
        return switch (s.strip().toLowerCase()) {
            case "quiet" -> QUIET;
            case "strict" -> STRICT;
            default -> NORMAL;
        };
    }
}
