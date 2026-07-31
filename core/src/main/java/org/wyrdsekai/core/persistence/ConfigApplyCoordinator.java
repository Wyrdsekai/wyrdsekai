package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.config.WyrdConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates a restart-to-apply-config request from the in-world Scroll of
 * Settings (or any other config writer) back to the process lifecycle.
 *
 * <p>Two restart strategies:
 * <ol>
 *   <li><b>systemd service</b> (system install): call {@code systemctl
 *       restart wyrdsekai --no-block} via ProcessBuilder. We run as the
 *       same unit, so the restart is gentle and picks up the refreshed
 *       {@code EnvironmentFile=/etc/wyrdsekai/wyrdsekai.conf}.</li>
 *   <li><b>source / foreground</b>: drop a marker file under
 *       {@code $DATA_DIR/.restart-requested} and exit with code 2. A
 *       supervisor (wyrd start's watchdog, test harness) sees the marker
 *       and restarts us; if nothing is watching, the operator restarts
 *       manually — the scroll narrates that expectation.</li>
 * </ol>
 *
 * <p>Idempotent — repeated requests within a short window don't spawn
 * multiple restart attempts. The gate resets when the process dies, which
 * is by definition when the restart completes.
 */
public final class ConfigApplyCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConfigApplyCoordinator.class);
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final String MARKER_NAME = ".restart-requested";

    /** Exit code the foreground-mode restart uses so supervisors know why. */
    public static final int RESTART_EXIT_CODE = 2;

    /**
     * Set to true in tests so a config-apply request drops the marker file
     * but does NOT call {@code System.exit(2)} from a background thread
     * (which would kill the gradle test worker mid-suite). Default: false —
     * production behaviour.
     */
    private static volatile boolean disableExit =
        "true".equalsIgnoreCase(System.getProperty("wyrdsekai.configApply.disableExit"))
        || WyrdConfig.get().resolveBool(
            "WYRDSEKAI_CONFIG_APPLY_DISABLE_EXIT", "config.apply_disable_exit", false);

    public static void setDisableExitForTests(boolean disable) {
        disableExit = disable;
    }

    private ConfigApplyCoordinator() {}

    /** Request a full server restart so an updated config file takes effect. */
    public static void requestRestart(String reason) {
        if (!inFlight.compareAndSet(false, true)) {
            log.info("Config apply already in flight — ignoring duplicate request ({})", reason);
            return;
        }
        log.info("Config apply requested: {}", reason);

        // Write the marker so foreground supervisors and tests can observe.
        try {
            Files.createDirectories(SystemPaths.dataDir());
            Files.writeString(markerPath(),
                Long.toString(System.currentTimeMillis()) + "\n" + reason + "\n");
        } catch (Exception e) {
            log.warn("Could not write restart marker: {}", e.getMessage());
        }

        // Prefer systemd when available — gives us a clean re-read of
        // EnvironmentFile without requiring an external watchdog.
        if (isSystemService()) {
            tryServiceRestartDetached();
            return;
        }

        // Source/foreground mode: exit with the sentinel code after a brief
        // grace period so the narration reaches the client first.
        scheduleExit();
    }

    /** Consumers (tests, wyrd watchdog) can check + clear the marker. */
    public static Path markerPath() {
        return SystemPaths.dataDir().resolve(MARKER_NAME);
    }

    /** True if the .restart-requested marker currently exists. */
    public static boolean isRestartRequested() {
        return Files.exists(markerPath());
    }

    public static void clearMarker() {
        try { Files.deleteIfExists(markerPath()); } catch (Exception ignore) {}
    }

    /** For tests only — resets the gate without touching disk. */
    public static void resetForTests() {
        inFlight.set(false);
        clearMarker();
    }

    // ── internals ─────────────────────────────────────────────────────────

    private static boolean isSystemService() {
        return SystemPaths.isSystemService();
    }

    private static void tryServiceRestartDetached() {
        // Run in a background thread so the original actor can finish
        // publishing its narration before we go away.
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(600);
                var pb = new ProcessBuilder("systemctl", "restart", "--no-block",
                    "wyrdsekai");
                pb.redirectErrorStream(true);
                pb.inheritIO();
                var proc = pb.start();
                int rc = proc.waitFor();
                log.info("systemctl restart exit code: {}", rc);
            } catch (Exception e) {
                log.warn("systemctl restart failed, falling back to self-exit: {}",
                    e.getMessage());
                scheduleExit();
            }
        }, "wyrd-config-apply");
        t.setDaemon(true);
        t.start();
    }

    private static void scheduleExit() {
        if (disableExit) {
            log.info("Exit disabled (test mode) — restart marker is at {}", markerPath());
            return;
        }
        Thread t = new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            log.info("Exiting with {} to request supervisor restart", RESTART_EXIT_CODE);
            System.exit(RESTART_EXIT_CODE);
        }, "wyrd-config-apply-exit");
        t.setDaemon(true);
        t.start();
    }
}
