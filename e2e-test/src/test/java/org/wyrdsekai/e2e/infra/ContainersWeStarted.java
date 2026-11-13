package org.wyrdsekai.e2e.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The containers a test run brought up, written down so the build stops them when it ends
 * (root build.gradle.kts, {@code ContainersThisBuildStarted}). A model container one run started
 * held the card for fifteen hours after the run, until it was found before a release (home-server,
 * 2026-11-12). One that was already running before the run is someone else's, and is left.
 */
public final class ContainersWeStarted {

    private static final Logger log = LoggerFactory.getLogger(ContainersWeStarted.class);
    /** Where the build reads what to stop; set by the build on every test task. */
    static final String LEDGER = "wyrdsekai.e2e.startedLedger";

    private ContainersWeStarted() {}

    /** {@code docker compose up -d <service>}, written down for the build's end when it was not already running. */
    public static Process up(Path compose, String profile, String service) throws IOException {
        boolean wasRunning = running(compose, service);
        var cmd = new ArrayList<String>(List.of("docker", "compose", "-f", compose.toAbsolutePath().toString()));
        if (profile != null && !profile.isBlank()) { cmd.add("--profile"); cmd.add(profile); }
        cmd.add("up"); cmd.add("-d"); cmd.add(service);
        var proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!wasRunning) remember(compose, profile, service);
        return proc;
    }

    static boolean running(Path compose, String service) {
        try {
            var p = new ProcessBuilder("docker", "compose", "-f", compose.toAbsolutePath().toString(),
                "ps", "-q", "--status", "running", service).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(20, TimeUnit.SECONDS);
            return p.exitValue() == 0 && !out.isEmpty();
        } catch (Exception e) {
            return false;   // cannot tell: treated as ours, and stopped at the end
        }
    }

    static synchronized void remember(Path compose, String profile, String service) {
        var ledger = System.getProperty(LEDGER);
        if (ledger == null || ledger.isBlank()) return;
        try {
            var file = Path.of(ledger);
            Files.createDirectories(file.getParent());
            Files.writeString(file, compose.toAbsolutePath() + "\t" + (profile == null ? "" : profile) + "\t" + service + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write down that this run started {}: {}", service, e.toString());
        }
    }
}
