package org.wyrdsekai.daemon.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configures the daemon to start automatically on boot.
 *
 * Platform-specific implementations:
 * - Windows: Registry Run key (HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run)
 * - macOS: LaunchAgent plist (~/.local/share/wyrdsekai/org.wyrdsekai.daemon.plist)
 * - Linux: systemd user service (~/.config/systemd/user/wyrdsekai-daemon.service)
 */
public final class DesktopAutoStart {

    private static final Logger log = LoggerFactory.getLogger(DesktopAutoStart.class);
    private static final String APP_NAME = "WyrdsekaiDaemon";

    private DesktopAutoStart() {}

    /**
     * Enable auto-start on boot.
     *
     * @param jarPath absolute path to the daemon jar
     * @param extraArgs additional CLI arguments
     */
    public static boolean enable(String jarPath, String extraArgs) {
        var os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return enableWindows(jarPath, extraArgs);
            } else if (os.contains("mac") || os.contains("darwin")) {
                return enableMacOS(jarPath, extraArgs);
            } else {
                return enableLinux(jarPath, extraArgs);
            }
        } catch (Exception e) {
            log.error("Failed to enable auto-start: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Disable auto-start on boot.
     */
    public static boolean disable() {
        var os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return disableWindows();
            } else if (os.contains("mac") || os.contains("darwin")) {
                return disableMacOS();
            } else {
                return disableLinux();
            }
        } catch (Exception e) {
            log.error("Failed to disable auto-start: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if auto-start is currently enabled.
     */
    public static boolean isEnabled() {
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return isEnabledWindows();
        } else if (os.contains("mac") || os.contains("darwin")) {
            return isEnabledMacOS();
        } else {
            return isEnabledLinux();
        }
    }

    // --- Windows: Registry Run key ---

    private static boolean enableWindows(String jarPath, String extraArgs) throws Exception {
        var cmd = "javaw -jar \"" + jarPath + "\" --headless " + extraArgs;
        var proc = new ProcessBuilder("reg", "add",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", APP_NAME, "/t", "REG_SZ", "/d", cmd, "/f")
            .redirectErrorStream(true).start();
        var result = proc.waitFor() == 0;
        if (result) log.info("Windows auto-start enabled");
        return result;
    }

    private static boolean disableWindows() throws Exception {
        var proc = new ProcessBuilder("reg", "delete",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", APP_NAME, "/f")
            .redirectErrorStream(true).start();
        var result = proc.waitFor() == 0;
        if (result) log.info("Windows auto-start disabled");
        return result;
    }

    private static boolean isEnabledWindows() {
        try {
            var proc = new ProcessBuilder("reg", "query",
                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", APP_NAME)
                .redirectErrorStream(true).start();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // --- macOS: LaunchAgent ---

    private static final Path MACOS_PLIST = Path.of(
        System.getProperty("user.home"), "Library", "LaunchAgents",
        "org.wyrdsekai.daemon.plist");

    private static boolean enableMacOS(String jarPath, String extraArgs) throws IOException {
        var plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
              "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>org.wyrdsekai.daemon</string>
                <key>ProgramArguments</key>
                <array>
                    <string>java</string>
                    <string>-jar</string>
                    <string>%s</string>
                    <string>--headless</string>
                    %s
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>KeepAlive</key>
                <true/>
                <key>StandardOutPath</key>
                <string>/tmp/wyrdsekai-daemon.log</string>
                <key>StandardErrorPath</key>
                <string>/tmp/wyrdsekai-daemon.log</string>
            </dict>
            </plist>
            """.formatted(jarPath,
                extraArgs.isEmpty() ? "" :
                    "<string>" + extraArgs.replace(" ", "</string>\n                    <string>") + "</string>");

        Files.createDirectories(MACOS_PLIST.getParent());
        Files.writeString(MACOS_PLIST, plist);
        log.info("macOS LaunchAgent written to {}", MACOS_PLIST);
        return true;
    }

    private static boolean disableMacOS() throws IOException {
        if (Files.exists(MACOS_PLIST)) {
            Files.delete(MACOS_PLIST);
            log.info("macOS LaunchAgent removed");
        }
        return true;
    }

    private static boolean isEnabledMacOS() {
        return Files.exists(MACOS_PLIST);
    }

    // --- Linux: systemd user service ---

    private static final Path LINUX_SERVICE = Path.of(
        System.getProperty("user.home"), ".config", "systemd", "user",
        "wyrdsekai-daemon.service");

    private static boolean enableLinux(String jarPath, String extraArgs) throws Exception {
        var unit = """
            [Unit]
            Description=Wyrdsekai Inference Daemon
            After=network.target

            [Service]
            Type=simple
            ExecStart=java -jar %s --headless %s
            Restart=on-failure
            RestartSec=10

            [Install]
            WantedBy=default.target
            """.formatted(jarPath, extraArgs);

        Files.createDirectories(LINUX_SERVICE.getParent());
        Files.writeString(LINUX_SERVICE, unit);

        // Enable and start
        new ProcessBuilder("systemctl", "--user", "daemon-reload")
            .redirectErrorStream(true).start().waitFor();
        new ProcessBuilder("systemctl", "--user", "enable", "wyrdsekai-daemon")
            .redirectErrorStream(true).start().waitFor();

        log.info("Linux systemd user service installed at {}", LINUX_SERVICE);
        return true;
    }

    private static boolean disableLinux() throws Exception {
        new ProcessBuilder("systemctl", "--user", "disable", "wyrdsekai-daemon")
            .redirectErrorStream(true).start().waitFor();
        if (Files.exists(LINUX_SERVICE)) {
            Files.delete(LINUX_SERVICE);
        }
        new ProcessBuilder("systemctl", "--user", "daemon-reload")
            .redirectErrorStream(true).start().waitFor();
        log.info("Linux systemd user service removed");
        return true;
    }

    private static boolean isEnabledLinux() {
        try {
            var proc = new ProcessBuilder("systemctl", "--user", "is-enabled", "wyrdsekai-daemon")
                .redirectErrorStream(true).start();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
