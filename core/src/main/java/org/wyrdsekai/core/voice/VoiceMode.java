package org.wyrdsekai.core.voice;

/**
 * Voice interaction modes for companion agents.
 *
 * <p>Privacy tiers (from least to most intrusive):
 * <ol>
 *   <li>{@link #DISABLED} — no voice (default)</li>
 *   <li>{@link #PUSH_TO_TALK} — audio captured only while button held</li>
 *   <li>{@link #WAKE_WORD} — keyword detector runs locally, captures after trigger</li>
 *   <li>{@link #ALWAYS_ON} — continuous capture (steward-explicit only)</li>
 * </ol>
 *
 * @see VoiceService
 */
public enum VoiceMode {
    /** Voice is completely disabled. No audio capture. */
    DISABLED,

    /** Audio captured only while the push-to-talk button is held. Lowest risk. */
    PUSH_TO_TALK,

    /** Listens for a trigger phrase, then captures. Medium risk. */
    WAKE_WORD,

    /** Continuous speech capture. Highest risk — steward must explicitly enable. */
    ALWAYS_ON
}
