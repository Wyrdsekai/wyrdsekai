package org.wyrdsekai.core.observability;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Log redaction for sensitive content (§105.9).
 * Ensures human message content, agent private thoughts,
 * soul fragments, and ER diagnostic details never appear in logs.
 */
public class RedactingLayout {

    /** Categories of sensitive data. */
    public enum SensitiveCategory {
        /** Human message content — NEVER in logs. */
        HUMAN_MESSAGE,
        /** Agent internal processing. */
        AGENT_THOUGHT,
        /** Soul fragment content. */
        SOUL_FRAGMENT,
        /** ER diagnostic raw findings. */
        ER_DIAGNOSTIC,
        /** Credentials, keys, tokens. */
        CREDENTIAL,
        /** Personal identifiable information. */
        PII
    }

    /** Result of redaction. */
    public record RedactionResult(
        String redactedText,
        int redactionCount,
        Set<SensitiveCategory> categoriesFound
    ) {}

    private static final String REDACTION_MARKER = "[REDACTED]";

    // Patterns for sensitive content detection
    private static final List<PatternRule> RULES = List.of(
        // Credentials
        new PatternRule(Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key|private[_-]?key)\\s*[:=]\\s*\\S+"),
            SensitiveCategory.CREDENTIAL),
        // Bearer tokens
        new PatternRule(Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9._\\-]+"),
            SensitiveCategory.CREDENTIAL),
        // Ed25519 private keys (base64-ish, 64+ chars)
        new PatternRule(Pattern.compile("(?i)(private[_-]?key|signing[_-]?key)\\s*[:=]\\s*[a-zA-Z0-9+/=]{32,}"),
            SensitiveCategory.CREDENTIAL),
        // Email addresses (PII)
        new PatternRule(Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
            SensitiveCategory.PII),
        // Phone numbers (PII)
        new PatternRule(Pattern.compile("\\b\\d{3}[\\-.]\\d{3}[\\-.]\\d{4}\\b"),
            SensitiveCategory.PII)
    );

    /** Marker strings that identify blocks of sensitive content. */
    private static final Map<String, SensitiveCategory> BLOCK_MARKERS = Map.of(
        "user_message:", SensitiveCategory.HUMAN_MESSAGE,
        "human_input:", SensitiveCategory.HUMAN_MESSAGE,
        "agent_thought:", SensitiveCategory.AGENT_THOUGHT,
        "internal_reasoning:", SensitiveCategory.AGENT_THOUGHT,
        "fragment_content:", SensitiveCategory.SOUL_FRAGMENT,
        "soul_fragment:", SensitiveCategory.SOUL_FRAGMENT,
        "diagnostic_detail:", SensitiveCategory.ER_DIAGNOSTIC,
        "er_finding:", SensitiveCategory.ER_DIAGNOSTIC
    );

    private final Set<SensitiveCategory> enabledCategories;

    public RedactingLayout() {
        this(EnumSet.allOf(SensitiveCategory.class));
    }

    public RedactingLayout(Set<SensitiveCategory> enabledCategories) {
        this.enabledCategories = EnumSet.copyOf(enabledCategories);
    }

    /** Redact sensitive content from a log message. */
    public RedactionResult redact(String text) {
        if (text == null || text.isEmpty()) {
            return new RedactionResult(text, 0, Set.of());
        }

        var categories = EnumSet.noneOf(SensitiveCategory.class);
        var result = text;
        int count = 0;

        // Apply pattern rules
        for (var rule : RULES) {
            if (!enabledCategories.contains(rule.category())) continue;
            var matcher = rule.pattern().matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(REDACTION_MARKER);
                categories.add(rule.category());
                count++;
            }
        }

        // Apply block markers
        for (var entry : BLOCK_MARKERS.entrySet()) {
            if (!enabledCategories.contains(entry.getValue())) continue;
            if (result.contains(entry.getKey())) {
                int idx = result.indexOf(entry.getKey());
                // Redact from marker to end of line or end of string
                int endIdx = result.indexOf('\n', idx);
                if (endIdx < 0) endIdx = result.length();
                result = result.substring(0, idx) + entry.getKey() + " " + REDACTION_MARKER
                       + result.substring(endIdx);
                categories.add(entry.getValue());
                count++;
            }
        }

        return new RedactionResult(result, count, categories);
    }

    /** Check if text contains any sensitive content. */
    public boolean containsSensitive(String text) {
        if (text == null) return false;
        return redact(text).redactionCount() > 0;
    }

    /** Redact a structured log entry (key-value pairs). */
    public Map<String, String> redactMap(Map<String, String> entries) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : entries.entrySet()) {
            var redacted = redact(entry.getValue());
            result.put(entry.getKey(), redacted.redactedText());
        }
        return result;
    }

    /** Create a safe metadata map (only non-sensitive keys). */
    public Map<String, String> safeMetadata(String agentDid, String operation,
                                             long durationMs, int tokenCount) {
        return Map.of(
            "agent", agentDid,
            "operation", operation,
            "duration_ms", String.valueOf(durationMs),
            "tokens", String.valueOf(tokenCount)
        );
    }

    private record PatternRule(Pattern pattern, SensitiveCategory category) {}
}
