package org.wyrdsekai.server.ws;

/**
 * Parses MUD-style input text into structured command types.
 * Extracts emote, tell, whisper, and say commands from shorthand prefixes
 * and full-word variants.
 *
 * <p>Prefix shorthands:
 * <ul>
 *   <li>{@code :action} or {@code ;action} → emote</li>
 *   <li>{@code >name text} → tell (whisper to target)</li>
 * </ul>
 *
 * <p>Full-word commands:
 * <ul>
 *   <li>{@code emote action} → emote</li>
 *   <li>{@code tell name text} → tell</li>
 *   <li>{@code whisper name text} → whisper</li>
 *   <li>{@code say text} → say (explicit)</li>
 * </ul>
 *
 * <p>Anything else is treated as plain say.
 */
public final class InputParser {

    private InputParser() {}

    /** The parsed result of a MUD input line. */
    public sealed interface ParsedInput {
        record Say(String text) implements ParsedInput {}
        record Emote(String text) implements ParsedInput {}
        record Tell(String target, String text) implements ParsedInput {}
        record Whisper(String target, String text) implements ParsedInput {}
    }

    /**
     * Parse a raw text input line into a structured command.
     *
     * @param text the raw input text (already trimmed by caller)
     * @return the parsed input command
     */
    public static ParsedInput parse(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedInput.Say(text != null ? text : "");
        }

        var trimmed = text.trim();

        // Emote shorthand: :action or ;action
        if (trimmed.startsWith(":") || trimmed.startsWith(";")) {
            var emoteText = trimmed.substring(1).trim();
            if (!emoteText.isEmpty()) {
                return new ParsedInput.Emote(emoteText);
            }
            // Empty emote (just ":" or ";") falls through to say
            return new ParsedInput.Say(trimmed);
        }

        // Tell shorthand: >name text
        if (trimmed.startsWith(">")) {
            var rest = trimmed.substring(1).trim();
            var spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                var targetName = rest.substring(0, spaceIdx);
                var tellText = rest.substring(spaceIdx + 1).trim();
                return new ParsedInput.Tell(targetName, tellText);
            }
            // No text after target name — falls through to say
            return new ParsedInput.Say(trimmed);
        }

        var lower = trimmed.toLowerCase();

        // Full word: emote <text>
        if (lower.startsWith("emote ")) {
            var emoteText = trimmed.substring(6).trim();
            if (!emoteText.isEmpty()) {
                return new ParsedInput.Emote(emoteText);
            }
        }

        // Full word: tell <name> <text>
        if (lower.startsWith("tell ")) {
            var rest = trimmed.substring(5).trim();
            var spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                var targetName = rest.substring(0, spaceIdx);
                var tellText = rest.substring(spaceIdx + 1).trim();
                return new ParsedInput.Tell(targetName, tellText);
            }
        }

        // Full word: whisper <name> <text>
        if (lower.startsWith("whisper ")) {
            var rest = trimmed.substring(8).trim();
            var spaceIdx = rest.indexOf(' ');
            if (spaceIdx > 0) {
                var targetName = rest.substring(0, spaceIdx);
                var whisperText = rest.substring(spaceIdx + 1).trim();
                return new ParsedInput.Whisper(targetName, whisperText);
            }
        }

        // Say shorthand: 'text or "text (standard MUD convention)
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            var sayText = trimmed.substring(1).trim();
            if (!sayText.isEmpty()) {
                return new ParsedInput.Say(sayText);
            }
        }

        // Full word: say <text> (explicit prefix, stripped)
        if (lower.startsWith("say ")) {
            return new ParsedInput.Say(trimmed.substring(4).trim());
        }

        // Default: treat as say for WebSocket (clients send pre-parsed C2S.Say messages,
        // so text reaching here has already been identified as speech by the client)
        return new ParsedInput.Say(trimmed);
    }
}
