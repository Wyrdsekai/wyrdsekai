package org.wyrdsekai.core.skill;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent skill permission configuration.
 * Supports glob-style patterns: "hearth.*" matches all hearth skills.
 * Default: deny all. Steward grants access.
 */
public class SkillPermission {

    private final Map<String, Boolean> rules = new ConcurrentHashMap<>();

    /** Grant access to a skill or pattern (e.g., "hearth.*", "herald.email.*"). */
    public void allow(String pattern) {
        rules.put(pattern, true);
    }

    /** Deny access to a skill or pattern. */
    public void deny(String pattern) {
        rules.put(pattern, false);
    }

    /** Check if a specific skill ID is allowed. */
    public boolean isAllowed(String skillId) {
        // Exact match first
        Boolean exact = rules.get(skillId);
        if (exact != null) return exact;

        // Glob matching: "hearth.*" matches "hearth.ha.set-light"
        // More specific patterns take precedence
        Boolean bestMatch = null;
        int bestLength = -1;

        for (var entry : rules.entrySet()) {
            String pattern = entry.getKey();
            if (matchesGlob(pattern, skillId) && pattern.length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = pattern.length();
            }
        }

        // Wildcard match
        if (bestMatch != null) return bestMatch;

        // Global wildcard
        Boolean global = rules.get("*");
        if (global != null) return global;

        // Default: deny
        return false;
    }

    /** Simple glob matching: "hearth.*" matches "hearth.ha.set-light". */
    static boolean matchesGlob(String pattern, String skillId) {
        if (pattern.equals("*")) return true;
        if (pattern.equals(skillId)) return true;
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return skillId.startsWith(prefix + ".") || skillId.equals(prefix);
        }
        return false;
    }

    /** Create a permission that allows everything. */
    public static SkillPermission allowAll() {
        var perm = new SkillPermission();
        perm.allow("*");
        return perm;
    }

    /** Create a permission that denies everything. */
    public static SkillPermission denyAll() {
        return new SkillPermission();
    }

    /**
     * Default per-companion permission (Phase 1.2). Allows the LOW-consequence
     * skills out of the box (weather, search, rss, kiwix, calendar-read, notes,
     * grocery, medication reminders, clipboard, …) but gates the CONSEQUENTIAL
     * categories behind an explicit steward grant — messaging AS the household
     * ({@code herald.*}), physical home control ({@code hearth.ha.*}/{@code
     * hearth.locks.*}), and real spend ({@code trading.*}). Emergency call is
     * always allowed (safety-critical, exact match wins over the deny). This is
     * the skill-level version of the tiered posture chosen for MCP grants:
     * ambient reach open, consequential reach deliberately handed over.
     */
    public static SkillPermission companionDefault() {
        var p = new SkillPermission();
        p.allow("*");
        p.deny("herald.*");        // email / signal / gmail / chat / whatsapp — sends AS the household
        p.deny("hearth.ha.*");     // Home Assistant physical control (lights/locks/climate/cameras)
        p.deny("hearth.locks.*");
        p.deny("trading.*");       // Privacy.com / Stripe — real money
        p.allow("herald.call.emergency"); // safety floor — always available
        return p;
    }

    /** Number of rules configured. */
    public int ruleCount() {
        return rules.size();
    }

    /** Get all rules (for serialization/display). */
    public Map<String, Boolean> rules() {
        return Map.copyOf(rules);
    }
}
