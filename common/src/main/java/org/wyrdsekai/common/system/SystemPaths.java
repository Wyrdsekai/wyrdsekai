package org.wyrdsekai.common.system;

import java.nio.file.Path;

/**
 * Canonical data-directory layout. Every subsystem that reads or writes
 * persistent state must go through here so the install, uninstall, reset
 * and backup flows have a single source of truth.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code WYRDSEKAI_DATA_DIR} env var (explicit override; set by the
 *       systemd unit to {@code /var/lib/wyrdsekai}, by macOS LaunchDaemon
 *       to an explicit path, and honoured by the CLI via
 *       {@code --data-dir}).</li>
 *   <li>{@code /var/lib/wyrdsekai} when running as a system service (detected
 *       by {@code $USER=root} or the {@code WYRDSEKAI_SERVICE_MODE=true}
 *       flag). Follows FHS convention.</li>
 *   <li>{@code %APPDATA%\wyrdsekai} on Windows.</li>
 *   <li>{@code $HOME/.wyrdsekai} otherwise (source/dev mode).</li>
 * </ol>
 *
 * <p>The service systemd unit sets {@code WYRDSEKAI_DATA_DIR} so user-run
 * CLI commands and root-run service daemons can't silently disagree about
 * where state lives — a class of bug that stole a session of debugging.
 */
public final class SystemPaths {

    /** System-wide data dir when running under systemd (Linux FHS). */
    public static final String SYSTEM_DATA_DIR = "/var/lib/wyrdsekai";

    /** System-wide config dir. Holds {@code wyrdsekai.conf} (Phase 3). */
    public static final String SYSTEM_CONFIG_DIR = "/etc/wyrdsekai";

    private SystemPaths() {}

    /**
     * Base data directory. Honours {@code WYRDSEKAI_DATA_DIR} first, then
     * falls back per the class javadoc.
     */
    public static Path dataDir() {
        // Tests can override via system property without mutating the JVM
        // environment (env-var mutation requires reflection hacks that fail
        // on JDK 17+). Falls through to env var, then service default.
        String propDir = System.getProperty("wyrdsekai.dataDir");
        if (propDir != null && !propDir.isEmpty()) return Path.of(propDir);
        String envDir = System.getenv("WYRDSEKAI_DATA_DIR");
        if (envDir != null && !envDir.isEmpty()) return Path.of(envDir);

        // Service mode (systemd unit runs as root, or explicitly flagged).
        if (isSystemService()) return Path.of(SYSTEM_DATA_DIR);

        if (OsDetect.isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null) return Path.of(appData, "wyrdsekai");
        }
        return Path.of(System.getProperty("user.home"), ".wyrdsekai");
    }

    /** True when running under systemd as root (the .deb install path). */
    public static boolean isSystemService() {
        if ("true".equalsIgnoreCase(System.getenv("WYRDSEKAI_SERVICE_MODE"))) {
            return true;
        }
        // Heuristic: systemd-launched process has INVOCATION_ID set and
        // typically runs as root for the wyrdsekai.service unit.
        String invId = System.getenv("INVOCATION_ID");
        String user = System.getenv("USER");
        return invId != null && !invId.isEmpty() && "root".equals(user);
    }

    /**
     * Config file (Phase 3). Resolution order:
     * <ol>
     *   <li>If {@code wyrdsekai.dataDir} (test) or {@code WYRDSEKAI_DATA_DIR}
     *       is set, the config always lives under {@code $dataDir/wyrdsekai.conf}.
     *       Explicit override wins — prevents tests from accidentally picking up
     *       a root-owned {@code /etc/wyrdsekai/wyrdsekai.conf} that happens to
     *       exist on the dev box, which would fail with AccessDenied on write.</li>
     *   <li>When running as the system service, prefer {@code /etc/wyrdsekai/wyrdsekai.conf}
     *       if it exists.</li>
     *   <li>Otherwise, {@code $dataDir/wyrdsekai.conf}.</li>
     * </ol>
     */
    public static Path configFile() {
        // SINGLE-CANONICAL-CONF INVARIANT (see the .deb unit): a system-service
        // node reads /etc/wyrdsekai/wyrdsekai.conf via EnvironmentFile, and the
        // unit ALSO sets WYRDSEKAI_DATA_DIR — so the data-dir override must NOT
        // outrank the /etc conf here, or in-world writes (Scroll of Settings)
        // land in /var/lib/.../wyrdsekai.conf where the service never reads
        // them (second-node, 2026-07-04). /etc wins whenever we run as a service and
        // the file exists; DATA_DIR keeps governing every non-service run.
        if (isSystemService()) {
            Path sys = Path.of(SYSTEM_CONFIG_DIR, "wyrdsekai.conf");
            if (sys.toFile().exists()) return sys;
        }
        return dataDir().resolve("wyrdsekai.conf");
    }

    // ── Canonical subdirs ─────────────────────────────────────────────────

    public static Path dbPath()      { return dataDir().resolve("world.db"); }
    public static Path libraryDb()   { return dataDir().resolve("library.db"); }
    public static Path scriptsDir()  { return dataDir().resolve("scripts"); }
    public static Path vaultDir()    { return dataDir().resolve("vault"); }
    public static Path soulsDir()    { return dataDir().resolve("souls"); }
    public static Path packsDir()    { return dataDir().resolve("packs"); }
    public static Path searchDir()   { return dataDir().resolve("search"); }
    public static Path backupsDir()  { return dataDir().resolve("backups"); }
    public static Path dataSubdir()  { return dataDir().resolve("data"); }
    // D.6 — story persistence + journal markdown roots.
    public static Path storyDir()    { return dataSubdir().resolve("story"); }
    public static Path biographyDir(){ return dataSubdir().resolve("biography"); }

    // ── Canonical files ───────────────────────────────────────────────────

    public static Path contactsFile()     { return dataDir().resolve("contacts"); }
    public static Path envFile()          { return dataDir().resolve("env"); }
    public static Path nodeIdentityFile() { return dataDir().resolve("node-identity.json"); }
    public static Path activityLog()      { return dataSubdir().resolve("agent-activity.jsonl"); }
    public static Path sshHostKey()       { return dataDir().resolve("ssh_host_key"); }
    public static Path natsConfFile()     { return dataDir().resolve("nats.conf"); }
}
