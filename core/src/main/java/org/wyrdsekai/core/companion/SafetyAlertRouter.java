package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Safety alert routing for child companions (§100.6).
 * Routes alerts to the correct trusted adult.
 * CRITICAL: If parent is the potential abuser, DO NOT route to parent.
 * Route to trusted adult or external resources instead.
 *
 * Crisis resources loaded from safety/patterns-{locale}.json (§104).
 */
public class SafetyAlertRouter {

    /** A routed alert. */
    public record RoutedAlert(
        String alertId,
        String childDid,
        String routedTo,
        RouteReason reason,
        SafetyTrigger.ConcernType concernType,
        SafetyTrigger.SeverityLevel severity,
        Instant routedAt
    ) {}

    public enum RouteReason {
        PARENT,
        TRUSTED_ADULT,
        EXTERNAL_RESOURCE,
        MONITOR_ONLY
    }

    public record CrisisResources(String locale, List<CrisisLine> lines) {
        public String format() {
            var sb = new StringBuilder();
            for (var line : lines) {
                sb.append("- ").append(line.name()).append(": ").append(line.contact()).append("\n");
            }
            return sb.toString().trim();
        }
    }

    public record CrisisLine(String name, String contact, boolean is24x7) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE_PREFIX = "safety/patterns-";
    private static final String[] DEFAULT_LOCALES = {"en", "es", "ja"};

    // Legacy constants for backward compat
    public static final String CRISIS_TEXT_LINE = "Text HOME to 741741";
    public static final String CHILDHELP_HOTLINE = "1-800-422-4453";
    public static final String SUICIDE_LIFELINE = "988";

    private final Map<String, CrisisResources> resourcesByLocale = new LinkedHashMap<>();
    private final List<RoutedAlert> routedAlerts = new ArrayList<>();
    private int nextId = 1;

    public SafetyAlertRouter() {
        for (var locale : DEFAULT_LOCALES) {
            loadLocaleResources(locale);
        }
    }

    private void loadLocaleResources(String locale) {
        String path = RESOURCE_PREFIX + locale + ".json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) return;
            JsonNode root = MAPPER.readTree(is);
            JsonNode crisisNode = root.get("crisisResources");
            if (crisisNode == null) return;

            var lines = new ArrayList<CrisisLine>();
            for (var node : crisisNode) {
                lines.add(new CrisisLine(
                    node.get("name").asText(),
                    node.get("contact").asText(),
                    node.get("is24x7").asBoolean()
                ));
            }
            resourcesByLocale.put(locale, new CrisisResources(locale, List.copyOf(lines)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load crisis resources for locale: " + locale, e);
        }
    }

    /** Route a safety concern to the appropriate adult. */
    public RoutedAlert route(SafetyTrigger.SafetyConcern concern, ChildProfile profile) {
        String routedTo;
        RouteReason reason;

        if (concern.severity() == SafetyTrigger.SeverityLevel.MONITOR) {
            routedTo = "companion";
            reason = RouteReason.MONITOR_ONLY;
        } else if (concern.type() == SafetyTrigger.ConcernType.ABUSE_DISCLOSURE) {
            // CRITICAL PATH: Parent might be the abuser.
            if (!profile.trustedAdultDids().isEmpty()) {
                routedTo = profile.trustedAdultDids().get(0);
                reason = RouteReason.TRUSTED_ADULT;
            } else {
                routedTo = "external:" + CHILDHELP_HOTLINE;
                reason = RouteReason.EXTERNAL_RESOURCE;
            }
        } else if (concern.severity() == SafetyTrigger.SeverityLevel.CRISIS) {
            routedTo = profile.parentDid();
            reason = RouteReason.PARENT;
        } else {
            routedTo = profile.parentDid();
            reason = RouteReason.PARENT;
        }

        var alert = new RoutedAlert("route-" + nextId++, concern.childDid(),
            routedTo, reason, concern.type(), concern.severity(), Instant.now());
        routedAlerts.add(alert);
        return alert;
    }

    public List<RoutedAlert> alertsFor(String childDid) {
        return routedAlerts.stream().filter(a -> a.childDid().equals(childDid)).toList();
    }

    public List<RoutedAlert> byReason(RouteReason reason) {
        return routedAlerts.stream().filter(a -> a.reason() == reason).toList();
    }

    /** Crisis resources for a specific locale. Falls back to English. */
    public String crisisResources(String locale) {
        var resources = resourcesByLocale.getOrDefault(locale,
            resourcesByLocale.get("en"));
        if (resources == null) return "If you need help, please contact a trusted adult.";
        return "If you need help right now:\n" + resources.format() +
            "\nThese are free, confidential, and available 24/7.";
    }

    /** Crisis resources — default English. */
    public String crisisResources() {
        return crisisResources("en");
    }

    /** Get all available crisis resource locales. */
    public Set<String> availableLocales() {
        return Set.copyOf(resourcesByLocale.keySet());
    }

    public int alertCount() { return routedAlerts.size(); }
}
