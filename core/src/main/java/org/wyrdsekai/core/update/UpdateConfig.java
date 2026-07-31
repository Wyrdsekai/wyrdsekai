package org.wyrdsekai.core.update;

import org.wyrdsekai.common.model.AppVersion;

import java.time.Duration;

/**
 * Configuration for the mesh update protocol.
 * Loaded from environment variables / .env file.
 */
public record UpdateConfig(
    String channelUrl,          // release channel URL (http/https/file/mesh://)
    UpdatePolicy policy,        // auto / prompt / manual / disabled
    Duration checkInterval,     // how often to poll channel
    Duration stabilityDelay,    // wait after seed proves healthy before mesh propagation
    String maintenanceWindow,   // "HH:MM-HH:MM" window for auto restarts (null = anytime)
    String nodeRole,            // "primary" or "secondary"
    String releasePublicKey,    // base64 Ed25519 release signing key (null = skip verification)
    String pinnedVersion,       // pin to this version, ignore all updates (null = no pin)
    int versionCacheSize        // how many old versions to keep
) {

    public enum UpdatePolicy {
        AUTO, PROMPT, MANUAL, DISABLED
    }

    /**
     * Load from environment variables with sensible defaults.
     */
    public static UpdateConfig fromEnv() {
        return new UpdateConfig(
            env("WYRDSEKAI_UPDATE_CHANNEL", ""),
            parsePolicy(env("WYRDSEKAI_UPDATE_POLICY", "prompt")),
            parseDuration(env("WYRDSEKAI_UPDATE_INTERVAL", "6h")),
            parseDuration(env("WYRDSEKAI_UPDATE_DELAY", "5m")),
            env("WYRDSEKAI_UPDATE_WINDOW", null),
            env("WYRDSEKAI_NODE_ROLE", "secondary"),
            env("WYRDSEKAI_RELEASE_KEY", null),
            env("WYRDSEKAI_UPDATE_PIN", null),
            parseInt(env("WYRDSEKAI_VERSION_CACHE", "3"))
        );
    }

    /**
     * Check if updates are enabled at all.
     */
    public boolean enabled() {
        return policy != UpdatePolicy.DISABLED
            && pinnedVersion == null
            && channelUrl != null && !channelUrl.isEmpty();
    }

    /**
     * Check if this node is the primary (steward's node — updates last).
     */
    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(nodeRole);
    }

    /**
     * Determine the effective policy for a given version delta.
     */
    public UpdatePolicy effectivePolicy(String currentVersion, ReleaseManifest manifest) {
        if (policy == UpdatePolicy.DISABLED) return UpdatePolicy.DISABLED;
        if (pinnedVersion != null) return UpdatePolicy.DISABLED;

        // Breaking wire changes always require prompt
        if (manifest.breaking()) return UpdatePolicy.PROMPT;

        // Wire protocol jump > 1 always requires prompt
        int wireDelta = manifest.wireProtocol() - AppVersion.WIRE_PROTOCOL;
        if (wireDelta > 1) return UpdatePolicy.PROMPT;

        // Version type determines policy
        var versionType = classifyDelta(currentVersion, manifest.version());
        return switch (policy) {
            case AUTO -> switch (versionType) {
                case PATCH, MINOR -> UpdatePolicy.AUTO;
                case MAJOR -> UpdatePolicy.PROMPT;
            };
            case PROMPT -> switch (versionType) {
                case PATCH -> UpdatePolicy.AUTO;
                case MINOR, MAJOR -> UpdatePolicy.PROMPT;
            };
            case MANUAL -> UpdatePolicy.MANUAL;
            case DISABLED -> UpdatePolicy.DISABLED;
        };
    }

    // --- Internal ---

    enum VersionDelta { PATCH, MINOR, MAJOR }

    static VersionDelta classifyDelta(String from, String to) {
        var fromParts = from.split("[.-]");
        var toParts = to.split("[.-]");
        int fromMajor = fromParts.length > 0 ? parseIntSafe(fromParts[0]) : 0;
        int toMajor = toParts.length > 0 ? parseIntSafe(toParts[0]) : 0;
        if (toMajor != fromMajor) return VersionDelta.MAJOR;

        int fromMinor = fromParts.length > 1 ? parseIntSafe(fromParts[1]) : 0;
        int toMinor = toParts.length > 1 ? parseIntSafe(toParts[1]) : 0;
        if (toMinor != fromMinor) return VersionDelta.MINOR;

        return VersionDelta.PATCH;
    }

    private static UpdatePolicy parsePolicy(String s) {
        try { return UpdatePolicy.valueOf(s.toUpperCase()); }
        catch (Exception e) { return UpdatePolicy.PROMPT; }
    }

    private static Duration parseDuration(String s) {
        if (s == null || s.isEmpty()) return Duration.ofHours(6);
        s = s.trim().toLowerCase();
        try {
            if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.replace("h", "")));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.replace("s", "")));
            return Duration.ofHours(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Duration.ofHours(6);
        }
    }

    private static String env(String key, String defaultValue) {
        var v = System.getenv(key);
        if (v != null && !v.isEmpty()) return v;
        return System.getProperty(key.toLowerCase().replace('_', '.'), defaultValue);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { return 3; }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
