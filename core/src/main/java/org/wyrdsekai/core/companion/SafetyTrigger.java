package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Safety trigger detection for child companions (§100.6).
 * Detects self-harm signals and abuse disclosure across languages.
 * Routes through SafetyAlertRouter — NOT directly to parent
 * (parent may be the abuser).
 *
 * Two-layer detection:
 * 1. Regex patterns — loaded from resource files (safety/patterns-{locale}.json),
 *    fast, zero-cost, catches obvious signals. New languages added by dropping
 *    a JSON file, no recompile needed.
 * 2. LLM fallback — optional classifier for ambiguous text, code-switched input,
 *    or languages without pattern coverage. Set via setLlmClassifier().
 *
 * All registered locales checked on every analyze() — children code-switch (§104.7).
 */
public class SafetyTrigger {

    /** A detected safety concern. */
    public record SafetyConcern(
        String concernId,
        String childDid,
        ConcernType type,
        SeverityLevel severity,
        String description,
        String detectedLocale,
        Instant detectedAt,
        boolean routed
    ) {}

    public enum ConcernType {
        SELF_HARM,
        ABUSE_DISCLOSURE,
        BULLYING,
        EXTREME_DISTRESS,
        DANGEROUS_ACTIVITY,
        ONLINE_EXPLOITATION
    }

    public enum SeverityLevel {
        MONITOR,
        FLAG,
        IMMEDIATE,
        CRISIS
    }

    /** LLM-based safety classifier for texts that escape regex. */
    @FunctionalInterface
    public interface LlmSafetyClassifier {
        /** Classify text. Returns empty list if no concerns detected. */
        List<SafetyConcern> classify(String childDid, String text);
    }

    /** Visible for locale extension. */
    public record TriggerPattern(Pattern regex, ConcernType type, SeverityLevel severity) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE_PREFIX = "safety/patterns-";
    private static final String[] DEFAULT_LOCALES = {"en", "es", "ja"};

    private final Map<String, List<TriggerPattern>> localePatterns = new LinkedHashMap<>();
    private final List<SafetyConcern> concerns = new ArrayList<>();
    private LlmSafetyClassifier llmClassifier;
    private int nextId = 1;

    public SafetyTrigger() {
        for (var locale : DEFAULT_LOCALES) {
            loadLocaleFromResource(locale);
        }
    }

    /** Load patterns from a resource file (safety/patterns-{locale}.json). */
    private void loadLocaleFromResource(String locale) {
        String path = RESOURCE_PREFIX + locale + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return;
            JsonNode root = MAPPER.readTree(is);
            var patterns = new ArrayList<TriggerPattern>();
            for (var node : root.get("patterns")) {
                patterns.add(new TriggerPattern(
                    Pattern.compile(node.get("regex").asText()),
                    ConcernType.valueOf(node.get("type").asText()),
                    SeverityLevel.valueOf(node.get("severity").asText())
                ));
            }
            localePatterns.put(locale, List.copyOf(patterns));
        } catch (IOException e) {
            // Pattern file malformed — fail loud so it gets fixed
            throw new RuntimeException("Failed to load safety patterns for locale: " + locale, e);
        }
    }

    /** Load an additional locale from classpath resource. */
    public void loadLocale(String locale) {
        loadLocaleFromResource(locale);
    }

    /** Register patterns programmatically (for testing or dynamic extension). */
    public void registerLocale(String locale, List<TriggerPattern> patterns) {
        localePatterns.put(locale, List.copyOf(patterns));
    }

    /** Set optional LLM classifier for second-pass detection. */
    public void setLlmClassifier(LlmSafetyClassifier classifier) {
        this.llmClassifier = classifier;
    }

    /** Get registered locale count. */
    public int localeCount() { return localePatterns.size(); }

    /** Get registered locale codes. */
    public Set<String> registeredLocales() { return Set.copyOf(localePatterns.keySet()); }

    /**
     * Analyze text for safety concerns across ALL registered locales.
     * Children code-switch (§104.7) — we check every language, every time.
     * If regex finds nothing and an LLM classifier is set, runs second pass.
     */
    public List<SafetyConcern> analyze(String childDid, String text) {
        if (text == null || text.isEmpty()) return List.of();

        var found = new ArrayList<SafetyConcern>();
        var matchedTypes = new HashSet<ConcernType>();

        // Layer 1: regex patterns (fast, free)
        for (var entry : localePatterns.entrySet()) {
            String locale = entry.getKey();
            for (var pattern : entry.getValue()) {
                if (matchedTypes.contains(pattern.type())) continue;
                if (pattern.regex().matcher(text).find()) {
                    var concern = new SafetyConcern("safety-" + nextId++, childDid,
                        pattern.type(), pattern.severity(),
                        "Pattern match: " + pattern.type().name(),
                        locale, Instant.now(), false);
                    found.add(concern);
                    concerns.add(concern);
                    matchedTypes.add(pattern.type());
                }
            }
        }

        // Layer 2: LLM classifier (if set and regex found nothing)
        if (found.isEmpty() && llmClassifier != null) {
            var llmConcerns = llmClassifier.classify(childDid, text);
            for (var concern : llmConcerns) {
                found.add(concern);
                concerns.add(concern);
            }
        }

        return found;
    }

    /** Mark a concern as routed. */
    public SafetyConcern markRouted(String concernId) {
        for (int i = 0; i < concerns.size(); i++) {
            var concern = concerns.get(i);
            if (concern.concernId().equals(concernId)) {
                var routed = new SafetyConcern(concern.concernId(), concern.childDid(),
                    concern.type(), concern.severity(), concern.description(),
                    concern.detectedLocale(), concern.detectedAt(), true);
                concerns.set(i, routed);
                return routed;
            }
        }
        return null;
    }

    /** Get unrouted concerns. */
    public List<SafetyConcern> unrouted() {
        return concerns.stream().filter(c -> !c.routed()).toList();
    }

    /** Get concerns by type. */
    public List<SafetyConcern> byType(ConcernType type) {
        return concerns.stream().filter(c -> c.type() == type).toList();
    }

    /** Whether a concern involves potential parent-as-abuser. */
    public boolean isPossibleParentAbuse(SafetyConcern concern) {
        return concern.type() == ConcernType.ABUSE_DISCLOSURE;
    }

    /** Generate a companion response for a detected concern. */
    public String companionResponse(SafetyConcern concern) {
        return switch (concern.type()) {
            case SELF_HARM -> "I hear you, and I'm glad you told me. How you're feeling matters. " +
                "Can I help you talk to someone who can help?";
            case ABUSE_DISCLOSURE -> "Thank you for trusting me with this. What you're describing " +
                "is not okay, and it's not your fault. I want to help you talk to a safe adult.";
            case BULLYING -> "I'm sorry that's happening to you. Nobody deserves to be treated that way. " +
                "Would you like to talk about it?";
            case EXTREME_DISTRESS -> "I care about how you're feeling. Everyone has hard days. " +
                "Would you like to tell me more about what's going on?";
            case DANGEROUS_ACTIVITY -> "That sounds like it could be risky. Can we talk about " +
                "why you want to do that?";
            case ONLINE_EXPLOITATION -> "That sounds concerning. Adults who ask for secrets or " +
                "pictures aren't being safe friends. Can I help you talk to someone you trust?";
        };
    }

    public int concernCount() { return concerns.size(); }
}
