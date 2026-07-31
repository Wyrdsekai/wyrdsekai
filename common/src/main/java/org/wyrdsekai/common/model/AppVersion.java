package org.wyrdsekai.common.model;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;

/**
 * Application version info, loaded from build-generated properties.
 * Falls back to system properties and defaults when properties file is absent.
 *
 * Used by Between heartbeat/hello to advertise node version,
 * and by the mesh update protocol to determine version deltas.
 */
public final class AppVersion {

    /** Between wire protocol version. Increment on breaking wire changes. */
    public static final int WIRE_PROTOCOL = 1;

    /**
     * federation handshake schema version.
     * Increment on breaking changes to the federation propose/accept
     * message format or the bilateral_agreements table semantics.
     * Peers compare this at propose time; mismatched schemas refuse
     * to handshake (rather than silently producing inconsistent state).
     */
    public static final int FEDERATION_SCHEMA = 1;

    private static final AppVersion INSTANCE = load();

    private final String version;
    private final String buildHash;
    private final String gitSha;
    private final boolean gitDirty;
    private final Instant buildTimestamp;

    private AppVersion(String version, String buildHash, String gitSha,
                       boolean gitDirty, Instant buildTimestamp) {
        this.version = version;
        this.buildHash = buildHash;
        this.gitSha = gitSha;
        this.gitDirty = gitDirty;
        this.buildTimestamp = buildTimestamp;
    }

    public static AppVersion get() {
        return INSTANCE;
    }

    public String version() { return version; }
    /** Short git hash (7 chars) — operator-friendly. */
    public String buildHash() { return buildHash; }
    /** Full 40-char git SHA — unambiguous for drift comparisons. F14. */
    public String gitSha() { return gitSha; }
    /** True if HEAD had uncommitted changes at build time. F14. */
    public boolean gitDirty() { return gitDirty; }
    public Instant buildTimestamp() { return buildTimestamp; }
    public int wireProtocol() { return WIRE_PROTOCOL; }
    /** F14: federation handshake compatibility version. */
    public int federationSchema() { return FEDERATION_SCHEMA; }

    @Override
    public String toString() {
        return version + " (" + buildHash + (gitDirty ? "+dirty" : "") + ")";
    }

    private static AppVersion load() {
        // Try loading from build-generated properties on classpath
        Properties props = new Properties();
        try (InputStream is = AppVersion.class.getResourceAsStream("/wyrdsekai-version.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException ignored) {}

        var version = props.getProperty("version",
            System.getProperty("wyrdsekai.version", "0.1.0-SNAPSHOT"));
        var buildHash = props.getProperty("buildHash",
            System.getProperty("wyrdsekai.build.hash", "unknown"));
        var gitSha = props.getProperty("gitSha",
            System.getProperty("wyrdsekai.build.gitSha", buildHash));
        var gitDirty = Boolean.parseBoolean(props.getProperty("gitDirty",
            System.getProperty("wyrdsekai.build.gitDirty", "false")));
        var buildTs = props.getProperty("buildTimestamp", "");

        Instant timestamp;
        try {
            if (buildTs.isEmpty()) {
                timestamp = Instant.now();
            } else if (buildTs.matches("\\d+")) {
                timestamp = Instant.ofEpochMilli(Long.parseLong(buildTs));
            } else {
                timestamp = Instant.parse(buildTs);
            }
        } catch (Exception e) {
            timestamp = Instant.now();
        }

        return new AppVersion(version, buildHash, gitSha, gitDirty, timestamp);
    }
}
