package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scam pattern detection for aging companions (§99.5).
 * Flags suspicious calls, emails, and transactions.
 * Informs, never blocks — the human decides.
 *
 * Patterns loaded from resource files (safety/scam-patterns-{locale}.json).
 * New languages added by dropping a JSON file, no recompile needed.
 * All registered locales checked — elderly people may receive
 * scam messages in multiple languages.
 */
public class ScamDetector {

    /** A detected scam indicator. */
    public record ScamAlert(
        String alertId,
        String agentDid,
        ScamCategory category,
        SeverityLevel severity,
        String description,
        String sourceContent,
        String detectedLocale,
        Instant detectedAt,
        boolean acknowledged
    ) {}

    public enum ScamCategory {
        URGENCY_PRESSURE,
        AUTHORITY_IMPERSONATION,
        UNUSUAL_FINANCIAL,
        SOCIAL_EXPLOITATION,
        TECH_SUPPORT_SCAM,
        PRIZE_SCAM,
        SUBSCRIPTION_TRAP
    }

    public enum SeverityLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /** Visible for extension. */
    public record ScamPattern(Pattern regex, ScamCategory category, SeverityLevel severity) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE_PREFIX = "safety/scam-patterns-";
    private static final String[] DEFAULT_LOCALES = {"en", "es", "ja"};

    private final Map<String, List<ScamPattern>> localePatterns = new LinkedHashMap<>();
    private final List<ScamAlert> alerts = new ArrayList<>();
    private int nextId = 1;

    public ScamDetector() {
        for (var locale : DEFAULT_LOCALES) {
            loadLocaleFromResource(locale);
        }
    }

    private void loadLocaleFromResource(String locale) {
        String path = RESOURCE_PREFIX + locale + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return;
            JsonNode root = MAPPER.readTree(is);
            var patterns = new ArrayList<ScamPattern>();
            for (var node : root.get("patterns")) {
                patterns.add(new ScamPattern(
                    Pattern.compile(node.get("regex").asText()),
                    ScamCategory.valueOf(node.get("category").asText()),
                    SeverityLevel.valueOf(node.get("severity").asText())
                ));
            }
            localePatterns.put(locale, List.copyOf(patterns));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load scam patterns for locale: " + locale, e);
        }
    }

    /** Load an additional locale from classpath resource. */
    public void loadLocale(String locale) {
        loadLocaleFromResource(locale);
    }

    /** Register patterns programmatically. */
    public void registerLocale(String locale, List<ScamPattern> patterns) {
        localePatterns.put(locale, List.copyOf(patterns));
    }

    public int localeCount() { return localePatterns.size(); }
    public Set<String> registeredLocales() { return Set.copyOf(localePatterns.keySet()); }

    /** Analyze text content for scam indicators across all locales. */
    public List<ScamAlert> analyze(String agentDid, String content) {
        if (content == null || content.isEmpty()) return List.of();

        var found = new ArrayList<ScamAlert>();
        var matchedCategories = new HashSet<ScamCategory>();

        for (var entry : localePatterns.entrySet()) {
            String locale = entry.getKey();
            for (var pattern : entry.getValue()) {
                if (matchedCategories.contains(pattern.category())) continue;
                if (pattern.regex().matcher(content).find()) {
                    var alert = new ScamAlert("scam-" + nextId++, agentDid,
                        pattern.category(), pattern.severity(),
                        describeCategory(pattern.category()),
                        truncate(content, 200), locale,
                        Instant.now(), false);
                    found.add(alert);
                    alerts.add(alert);
                    matchedCategories.add(pattern.category());
                }
            }
        }
        return found;
    }

    /** Acknowledge an alert (human reviewed it). */
    public ScamAlert acknowledge(String alertId) {
        for (int i = 0; i < alerts.size(); i++) {
            var alert = alerts.get(i);
            if (alert.alertId().equals(alertId)) {
                var acked = new ScamAlert(alert.alertId(), alert.agentDid(),
                    alert.category(), alert.severity(), alert.description(),
                    alert.sourceContent(), alert.detectedLocale(),
                    alert.detectedAt(), true);
                alerts.set(i, acked);
                return acked;
            }
        }
        return null;
    }

    /** Get unacknowledged alerts for an agent. */
    public List<ScamAlert> unacknowledged(String agentDid) {
        return alerts.stream()
            .filter(a -> a.agentDid().equals(agentDid))
            .filter(a -> !a.acknowledged())
            .toList();
    }

    /** Get alerts by severity. */
    public List<ScamAlert> bySeverity(SeverityLevel severity) {
        return alerts.stream().filter(a -> a.severity() == severity).toList();
    }

    /** Generate a companion-appropriate warning message. */
    public String warningMessage(ScamAlert alert) {
        return switch (alert.category()) {
            case URGENCY_PRESSURE ->
                "This message is pressing you to act quickly. Legitimate organizations don't do that. " +
                "Take your time — there's no real deadline.";
            case AUTHORITY_IMPERSONATION ->
                "Someone claims to be from an authority. Real agencies don't contact people this way. " +
                "If you're concerned, call them directly using a number you trust.";
            case UNUSUAL_FINANCIAL ->
                "This involves an unusual payment method. Gift cards and wire transfers are " +
                "almost never used by legitimate organizations.";
            case PRIZE_SCAM ->
                "If you didn't enter a contest, you didn't win one. " +
                "Legitimate prizes never require payment to claim.";
            case TECH_SUPPORT_SCAM ->
                "No company monitors your computer remotely. " +
                "If someone calls claiming your computer is infected, it's a scam.";
            case SOCIAL_EXPLOITATION ->
                "Be cautious with online relationships that quickly involve money. " +
                "Take your time and talk to someone you trust about this.";
            case SUBSCRIPTION_TRAP ->
                "You may be paying for a service you don't use. " +
                "Would you like me to help you review your subscriptions?";
        };
    }

    public int alertCount() { return alerts.size(); }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String describeCategory(ScamCategory cat) {
        return switch (cat) {
            case URGENCY_PRESSURE -> "Message creates false urgency";
            case AUTHORITY_IMPERSONATION -> "Claims to be from authority/organization";
            case UNUSUAL_FINANCIAL -> "Requests unusual payment method";
            case PRIZE_SCAM -> "Claims you won a prize";
            case TECH_SUPPORT_SCAM -> "Fake tech support warning";
            case SOCIAL_EXPLOITATION -> "Possible social/romance exploitation";
            case SUBSCRIPTION_TRAP -> "Possible subscription trap";
        };
    }
}
