package org.wyrdsekai.core.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The node keeping itself current, the way its siblings do ({@code researchzosho update auto on},
 * {@code CODEZAIKU_UPDATE=auto}). Every {@code WYRDSEKAI_UPDATE_INTERVAL} (default six hours) it
 * asks what the latest release is; in {@code auto} mode, when that is newer than what runs, the
 * clock is inside the window ({@code WYRDSEKAI_UPDATE_WINDOW}, default {@code 03:00-05:00} local —
 * the housekeeping hour, when the household is asleep) and the node has been idle for ten
 * minutes, it hands the install to {@link UpdateLauncher}. One attempt per version per day, so a
 * failing install is not retried every six hours; the outcome is what {@code VERSION} says next boot.
 *
 * <p>Never on a dev build, never with a version pinned ({@code WYRDSEKAI_UPDATE_PIN}), and never
 * while a coding task or an inference request is in flight.
 */
public final class SelfUpdate {

    private static final Logger log = LoggerFactory.getLogger(SelfUpdate.class);
    private static final ObjectMapper M = new ObjectMapper();
    static final String DEFAULT_WINDOW = "03:00-05:00";
    static final Duration QUIET = Duration.ofMinutes(10);

    private final Path installRoot;
    private final Path dataDir;
    private final BooleanSupplier idle;
    private final BooleanSupplier launcher;
    private volatile Map<String, Object> state = new LinkedHashMap<>();

    public SelfUpdate(Path installRoot, Path dataDir, BooleanSupplier idle, BooleanSupplier launcher) {
        this.installRoot = installRoot;
        this.dataDir = dataDir;
        this.idle = idle;
        this.launcher = launcher;
        this.state = readState();
    }

    public SelfUpdate(Path installRoot, Path dataDir) {
        this(installRoot, dataDir, () -> ActivityGauge.idleFor(QUIET), () -> UpdateLauncher.launch(installRoot, dataDir));
    }

    /** Schedule the checks: first after {@code initialDelay}, then every {@code interval}. */
    public void start(ScheduledExecutorService scheduler, Duration initialDelay, Duration interval) {
        if (ReleaseCheck.mode().equals("off")) { log.info("[self-update] off (WYRDSEKAI_UPDATE=off)"); return; }
        scheduler.scheduleAtFixedRate(() -> { try { tick(); } catch (Throwable t) { log.warn("[self-update] check failed: {}", t.toString()); } },
            initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("[self-update] mode {}: checking {} every {}{}", ReleaseCheck.mode(), ReleaseCheck.REPO, interval,
            ReleaseCheck.mode().equals("auto") ? ", installing inside " + window() + " when idle" : "");
    }

    /** One check; returns what it decided, for the log and the status route. */
    public synchronized String tick() {
        var status = ReleaseCheck.status(installRoot, dataDir);
        var s = new LinkedHashMap<String, Object>(state);
        s.put("lastCheck", Instant.now().toString());
        s.put("installed", status.installed());
        s.put("latest", status.latest() == null ? "" : status.latest());
        s.put("updateAvailable", status.updateAvailable());
        String decision;
        if (!status.updateAvailable()) decision = status.latest() == null ? "latest unknown (GitHub not reached)" : "current";
        else if (!status.mode().equals("auto")) decision = "newer release " + status.latest() + " — wyrd update now";
        else if (!ReleaseCheck.isRelease(status.installed())) decision = "dev build; not auto-updating";
        else if (pinned() != null) decision = "pinned to " + pinned();
        else if (attemptedToday(s, status.latest())) decision = "already attempted " + status.latest() + " today";
        else if (!inWindow(LocalTime.now(ZoneId.systemDefault()))) decision = "waiting for the window " + window();
        else if (!idle.getAsBoolean()) decision = "busy; trying again next check";
        else {
            s.put("lastAttemptVersion", status.latest());
            s.put("lastAttemptAt", Instant.now().toString());
            boolean ok = launcher.getAsBoolean();
            decision = ok ? "installing " + status.latest() + " (the service restarts when it is in)" : "could not launch the updater";
            s.put("lastAttemptResult", ok ? "launched" : "launch failed");
        }
        s.put("decision", decision);
        state = s;
        writeState(s);
        if (status.updateAvailable()) log.info("[self-update] {} → {}: {}", status.installed(), status.latest(), decision);
        else log.debug("[self-update] {}", decision);
        return decision;
    }

    public Map<String, Object> state() { return Map.copyOf(state); }

    static String pinned() {
        var p = ReleaseCheck.env.apply("WYRDSEKAI_UPDATE_PIN");
        return p == null || p.isBlank() ? null : p.trim();
    }

    static String window() {
        var w = ReleaseCheck.env.apply("WYRDSEKAI_UPDATE_WINDOW");
        return w == null || w.isBlank() ? DEFAULT_WINDOW : w.trim();
    }

    /** {@code HH:MM-HH:MM}, wrapping past midnight; a window that cannot be read means "any time". */
    static boolean inWindow(LocalTime now) { return inWindow(window(), now); }

    static boolean inWindow(String window, LocalTime now) {
        try {
            var parts = window.split("-");
            var from = LocalTime.parse(parts[0].trim());
            var to = LocalTime.parse(parts[1].trim());
            if (from.equals(to)) return true;
            return from.isBefore(to) ? !now.isBefore(from) && now.isBefore(to) : !now.isBefore(from) || now.isBefore(to);
        } catch (Exception e) {
            return true;
        }
    }

    static boolean attemptedToday(Map<String, Object> s, String version) {
        var v = String.valueOf(s.getOrDefault("lastAttemptVersion", ""));
        var at = String.valueOf(s.getOrDefault("lastAttemptAt", ""));
        if (!v.equals(version) || at.isBlank()) return false;
        try { return Duration.between(Instant.parse(at), Instant.now()).compareTo(Duration.ofHours(24)) < 0; }
        catch (Exception e) { return false; }
    }

    private Path stateFile() { return dataDir.resolve("self-update.json"); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readState() {
        try {
            var f = stateFile();
            if (Files.isRegularFile(f)) return M.readValue(Files.readString(f, StandardCharsets.UTF_8), LinkedHashMap.class);
        } catch (IOException ignored) { }
        return new LinkedHashMap<>();
    }

    private void writeState(Map<String, Object> s) {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(stateFile(), M.writerWithDefaultPrettyPrinter().writeValueAsString(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("[self-update] could not write state: {}", e.toString());
        }
    }
}
