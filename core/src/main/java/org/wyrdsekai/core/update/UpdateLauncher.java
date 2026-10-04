package org.wyrdsekai.core.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts {@code wyrd update now --yes} OUTSIDE the running service, so the installer it runs
 * can stop and restart this very process without being killed along with it.
 *
 * <p>On Linux the package's prerm stops the unit, and systemd stops the unit's whole cgroup —
 * a child of the server included, halfway through {@code dpkg -i}. {@code systemd-run} puts
 * the updater in a transient unit of its own. macOS's launchd kills the job's process group
 * on bootout, so the updater is submitted as a launchd job of its own. On Windows the .msi
 * needs an elevation prompt a service cannot show, so the launcher only stages the download
 * (the CLI verifies and installs when a person runs it).
 */
public final class UpdateLauncher {

    private static final Logger log = LoggerFactory.getLogger(UpdateLauncher.class);

    private UpdateLauncher() {}

    /**
     * The command lines this would run on each platform, for a test to read without running them.
     *
     * <p>{@code --no-siblings}: the node's own update is Wyrdsekai's setting ({@code WYRDSEKAI_UPDATE=auto})
     * and updates Wyrdsekai only. CodeZaiku and ResearchZosho update on their own only by their own
     * settings ({@code CODEZAIKU_UPDATE}, {@code RESEARCHZOSHO_UPDATE}); a person's {@code wyrd update now}
     * is what asks them (their update contract, 2026-09-28).</p>
     */
    public static List<String> command(String os, Path wyrd, Path logFile) {
        return command(os, wyrd, logFile, null);
    }

    /**
     * The same, taking the installer from a household peer first ({@code --from <its http url>}): the
     * bytes come over the house network, the checksums still from the release itself.
     */
    public static List<String> command(String os, Path wyrd, Path logFile, String fromUrl) {
        var o = os.toLowerCase(Locale.ROOT);
        var from = fromUrl == null || fromUrl.isBlank() ? List.<String>of() : List.of("--from", fromUrl.trim());
        if (o.contains("win")) {
            var ps1 = wyrd.resolveSibling("wyrd.ps1");
            var cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1.toString(), "update", "stage"));
            cmd.addAll(from);
            return cmd;
        }
        if (o.contains("mac") || o.contains("darwin")) {
            var cmd = new ArrayList<>(List.of("launchctl", "submit", "-l", "com.wyrdsekai.self-update", "-o", logFile.toString(), "-e", logFile.toString(),
                "--", wyrd.toString(), "update", "now", "--yes", "--no-siblings"));
            cmd.addAll(from);
            return cmd;
        }
        var cmd = new ArrayList<String>();
        if (Files.isExecutable(Path.of("/usr/bin/systemd-run")) || Files.isExecutable(Path.of("/bin/systemd-run"))) {
            cmd.addAll(List.of("systemd-run", "--unit", "wyrdsekai-self-update-" + System.currentTimeMillis(), "--collect", "--quiet",
                "--property=StandardOutput=append:" + logFile, "--property=StandardError=append:" + logFile));
            cmd.addAll(List.of(wyrd.toString(), "update", "now", "--yes", "--no-siblings"));
            cmd.addAll(from);
        } else {
            var tail = "update now --yes --no-siblings" + (from.isEmpty() ? "" : " --from " + from.get(1));
            cmd.addAll(List.of("setsid", "-f", "sh", "-c", "exec " + wyrd + " " + tail + " >> " + logFile + " 2>&1"));
        }
        return cmd;
    }

    /** Launch it; the outcome is in the log file, and in the next boot's version. */
    public static boolean launch(Path installRoot, Path dataDir) {
        return launch(installRoot, dataDir, null);
    }

    /** Launch it, taking the installer from {@code fromUrl} (a household peer) first when given. */
    public static boolean launch(Path installRoot, Path dataDir, String fromUrl) {
        var os = System.getProperty("os.name", "");
        var wyrd = installRoot.resolve("bin").resolve(os.toLowerCase(Locale.ROOT).contains("win") ? "wyrd.ps1" : "wyrd");
        var logFile = dataDir.resolve("logs").resolve("self-update.log");
        try {
            Files.createDirectories(logFile.getParent());
            var cmd = command(os, wyrd, logFile, fromUrl);
            log.info("[self-update] launching: {}", String.join(" ", cmd));
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            var p = pb.start();
            // systemd-run / launchctl submit return at once; a setsid -f also. Anything still
            // running after a few seconds is the updater itself under a plain sh, which is fine.
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!p.isAlive() && p.exitValue() != 0) {
                log.warn("[self-update] launcher exited {} — see {}", p.exitValue(), logFile);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("[self-update] could not launch the updater: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
