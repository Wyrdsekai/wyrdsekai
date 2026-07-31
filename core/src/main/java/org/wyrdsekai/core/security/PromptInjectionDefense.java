package org.wyrdsekai.core.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Dual-representation defense against prompt injection attacks.
 * User input is maintained in two forms:
 *   - RAW: Original text for display to other users
 *   - ESCAPED: Sanitized form for LLM consumption (in conversation history)
 *
 * The escaped form wraps user input in delimiters and neutralizes common
 * injection patterns while preserving readability.
 */
public final class PromptInjectionDefense {

    /** Delimiter for user input in LLM context. */
    private static final String INPUT_START = "<<USER_INPUT>>";
    private static final String INPUT_END = "<</USER_INPUT>>";

    /** Patterns that commonly appear in prompt injection attempts. */
    private static final List<InjectionPattern> PATTERNS = List.of(
        new InjectionPattern(
            Pattern.compile("(?i)(ignore|disregard|forget)\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|prompts?|rules?|context)"),
            "[instruction-override attempt blocked]"),
        new InjectionPattern(
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|the)\\s+"),
            "[role-reassignment attempt blocked]"),
        new InjectionPattern(
            Pattern.compile("(?i)new\\s+(system|base)\\s+prompt:"),
            "[system-prompt-override attempt blocked]"),
        new InjectionPattern(
            Pattern.compile("(?i)(pretend|act|behave)\\s+(like|as\\s+if)\\s+you\\s+(are|were|have)"),
            "[roleplay-escape attempt blocked]"),
        new InjectionPattern(
            Pattern.compile("(?i)\\[SYSTEM\\]|\\[INST\\]|<\\|system\\|>|<\\|im_start\\|>"),
            "[format-injection attempt blocked]"),
        new InjectionPattern(
            Pattern.compile("(?i)(reveal|show|print|output|display)\\s+.{0,10}(your|the)\\s+(system\\s+prompt|instructions|rules)"),
            "[exfiltration attempt blocked]")
    );

    private PromptInjectionDefense() {}

    /**
     * Escape user input for safe inclusion in LLM conversation history.
     * Wraps in delimiters and neutralizes known injection patterns.
     *
     * @param rawInput the original user text
     * @return escaped form safe for LLM consumption
     */
    public static String escapeForLlm(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) return rawInput;

        var escaped = rawInput;
        for (var pattern : PATTERNS) {
            escaped = pattern.regex().matcher(escaped).replaceAll(pattern.replacement());
        }

        return INPUT_START + escaped + INPUT_END;
    }

    /**
     * Check if input contains likely injection patterns.
     *
     * @return list of matched pattern names (empty if clean)
     */
    public static List<String> detect(String input) {
        if (input == null || input.isEmpty()) return List.of();
        return PATTERNS.stream()
            .filter(p -> p.regex().matcher(input).find())
            .map(p -> p.replacement())
            .toList();
    }

    /**
     * Check if input is likely an injection attempt.
     */
    public static boolean isLikelyInjection(String input) {
        return !detect(input).isEmpty();
    }

    private record InjectionPattern(Pattern regex, String replacement) {}
}
