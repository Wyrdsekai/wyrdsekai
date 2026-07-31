package org.wyrdsekai.core.accessibility;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Adapts companion output based on accessibility preferences (§103.8).
 * Post-processes agent responses before delivery to user.
 *
 * This operates at the text level — structural adaptations
 * (font, contrast, layout) are client-side concerns.
 */
public class OutputAdapter {

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}" +
        "\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]");

    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern MARKDOWN_ITALIC = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern MARKDOWN_HEADER = Pattern.compile("^#{1,3}\\s+(.+)$", Pattern.MULTILINE);

    /** Alter Epoch-inspired screen reader tags: {A}...{a} content only shown to screen readers. */
    private static final Pattern SCREEN_READER_TAG = Pattern.compile("\\{A}(.*?)\\{a}", Pattern.DOTALL);

    /** Adapt output for the given preferences. */
    public String adapt(String output, AccessibilityPreferences prefs) {
        if (output == null || output.isEmpty()) return output;

        var result = output;

        // Process screen reader tags first — they affect all users
        if (prefs != null && prefs.screenReader()) {
            // Keep {A}...{a} content, strip the tags
            result = SCREEN_READER_TAG.matcher(result).replaceAll("$1");
        } else {
            // Strip {A}...{a} blocks entirely for non-screen-reader users
            result = stripScreenReaderTags(result);
        }

        if (prefs == null || !prefs.hasAccommodations()) return result.trim();

        if (prefs.noEmoji()) {
            result = stripEmoji(result);
        }

        if (prefs.voicePrimary()) {
            result = stripMarkdownForVoice(result);
        }

        if (prefs.screenReader()) {
            result = adaptForScreenReader(result);
        }

        if (prefs.shortMessages()) {
            result = truncateToShort(result);
        }

        return result.trim();
    }

    /** Strip emoji characters. */
    String stripEmoji(String text) {
        return EMOJI_PATTERN.matcher(text).replaceAll("").replaceAll("  +", " ").trim();
    }

    /** Strip markdown formatting for voice output — keep the content. */
    String stripMarkdownForVoice(String text) {
        var result = MARKDOWN_BOLD.matcher(text).replaceAll("$1");
        result = MARKDOWN_ITALIC.matcher(result).replaceAll("$1");
        result = MARKDOWN_HEADER.matcher(result).replaceAll("$1.");
        // Remove bullet points
        result = result.replaceAll("(?m)^\\s*[-*]\\s+", "");
        return result;
    }

    /** Add screen reader landmarks. */
    String adaptForScreenReader(String text) {
        // Convert markdown tables to verbal descriptions
        // Ensure paragraphs are clearly separated
        return text.replaceAll("\\|", ", ").replaceAll("-{3,}", "");
    }

    /** Truncate to approximately 3 sentences for cognitive load. */
    String truncateToShort(String text) {
        var sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length <= 3) return text;
        var sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(" ");
            sb.append(sentences[i]);
        }
        return sb.toString();
    }

    /** Strip {A}...{a} screen reader blocks for non-screen-reader users. */
    String stripScreenReaderTags(String text) {
        return SCREEN_READER_TAG.matcher(text).replaceAll("").replaceAll("  +", " ").trim();
    }

    /** Generate audio cue descriptions for a room event. */
    public String audioCue(String eventType) {
        return switch (eventType) {
            case "enter" -> "[soft chime — someone entered]";
            case "leave" -> "[fading tone — someone left]";
            case "message" -> "[gentle ping — new message]";
            case "alert" -> "[attention tone — important notice]";
            case "error" -> "[low tone — something went wrong]";
            case "companion" -> "[warm tone — your companion responds]";
            case "topology" -> "[double chime — the world has changed]";
            default -> "";
        };
    }

    /** Situational adaptation hints (§103.11). */
    public record SituationalContext(
        boolean oneHanded,
        boolean brightSunlight,
        boolean noisyEnvironment,
        boolean driving,
        boolean distracted
    ) {
        /** Derive effective preferences from situational context. */
        public AccessibilityPreferences derivePreferences(AccessibilityPreferences base) {
            var result = base;
            if (oneHanded || driving) {
                result = result.withStt(true, base.sttLanguage());
                result = result.withTts(true, base.ttsRate(), base.ttsVoice());
            }
            return result;
        }

        public static SituationalContext normal() {
            return new SituationalContext(false, false, false, false, false);
        }
    }
}
