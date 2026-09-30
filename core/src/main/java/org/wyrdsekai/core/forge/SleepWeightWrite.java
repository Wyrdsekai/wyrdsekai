package org.wyrdsekai.core.forge;

import org.wyrdsekai.core.update.ActivityGauge;
import org.wyrdsekai.core.body.BodyMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.common.util.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The wire between what she felt and what sinks in: at sleep completion,
 * consolidate the day's felt-stamped moments into a micro-LoRA on the voice
 * brain, each moment's gradient weighted by her tank state at the instant
 * she spoke (scripts/training/sleepwrite/sleep_write.py — the script holds
 * the hard gates; a failed gate ships nothing).
 *
 * <p>ClassifierForge's contract, deliberately: on by default where its trainer is set up
 * ({@link #enabled()}), fire-and-forget, never blocks or fails
 * sleep completion. Single-flight — a sleep that fires while the previous
 * write still runs is skipped, not queued: each write is one night's
 * consolidation and the window catches up on its own.
 *
 * <p>The staged artifact is a detachable adapter
 * ({@code $DATA/adapters/sleepwrite/current.gguf}) served by the voice
 * container's existing {@code LLAMA_VOICE_ADAPTER} path on its next start.
 * Rollback is removing one file. The base weights never change; the soul
 * stays portable.
 */
public final class SleepWeightWrite {

    private static final Logger log = LoggerFactory.getLogger(SleepWeightWrite.class);

    public static final String ENABLE_ENV = "WYRDSEKAI_SLEEP_WRITE";
    public static final String VENV_ENV = "WYRDSEKAI_SLEEP_WRITE_VENV";
    public static final String BRAIN_VENV_ENV = "WYRDSEKAI_BRAIN_WRITE_VENV";
    /** Set to 0/false to leave staged adapters for a manual `wyrd sleepwrite apply`. */
    public static final String AUTO_APPLY_ENV = "WYRDSEKAI_SLEEP_WRITE_AUTO_APPLY";

    /**
     * One of the night's trainers: which script writes, where its results live under
     * {@code adapters/}, which model container it pauses, and the flag the launcher's
     * {@code sleepwrite apply} takes to aim at that container.
     */
    record Trainer(String name, String scriptLeaf, String outSubdir, String container, String applyFlag) {}

    /** The two-model stack: a LoRA on the 4B voice model. Unchanged. */
    static final Trainer VOICE_4B =
        new Trainer("voice-4b", "sleep_write.py", "sleepwrite", "wyrdsekai-llama-voice", null);
    /** Serving profile single-sparse: a LoRA on the top layers of the one resident model. */
    static final Trainer HOME_SPARSE =
        new Trainer("home-sparse", "brain_write.py", "brainwrite", "wyrdsekai-llama-brain", "--brain");
    /**
     * The same write on a Mac, trained with MLX. The model is served by a native process there,
     * not a container, so there is nothing for this class to restart: the launcher's apply does it.
     */
    static final Trainer HOME_SPARSE_MLX =
        new Trainer("home-sparse-mlx", "brain_write_mlx.py", "brainwrite", null, "--brain");

    /** The trainer for this node's serving profile. */
    static Trainer active() {
        return trainerFor(WyrdConfig.get().singleBrain());
    }

    static Trainer trainerFor(boolean singleBrain) {
        return trainerFor(singleBrain, System.getProperty("os.name"));
    }

    static Trainer trainerFor(boolean singleBrain, String osName) {
        if (!singleBrain) return VOICE_4B;
        var os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") ? HOME_SPARSE_MLX : HOME_SPARSE;
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final long TIMEOUT_MINUTES = 30;
    /** The trainer's exit code for a rehearsal: it trained and measured the gate, and kept nothing. */
    static final int EXIT_REHEARSAL = 7;
    /** The trainer's exit code when the two-model stack's one voice adapter belongs to another companion. */
    static final int EXIT_NOT_THE_OWNER = 8;
    /** A rehearsal is asked for just before a forced sleep; a request that sleep never took up lapses. */
    static final Duration REHEARSAL_REQUEST_LIFETIME = Duration.ofMinutes(10);
    private static final AtomicReference<Instant> REHEARSE_REQUESTED = new AtomicReference<>();

    private SleepWeightWrite() {}

    /** True while a night's write is running; the updater and the reflexes keep off it. */
    public static boolean inFlight() { return IN_FLIGHT.get(); }

    /**
     * Is the night's write on? On by default since 0.5.0: it runs wherever its trainer is set up.
     * {@code WYRDSEKAI_SLEEP_WRITE=false} (or 0, off, no) turns it off. {@code =true} (or 1, on, yes)
     * arms it even where the trainer looks absent, and the night then says why it could not run.
     * Unset, a node without the trainer skips the write quietly: no failed night is marked in
     * anyone's record, and under the single-model profile nobody is held asleep for a write that
     * cannot run.
     */
    public static boolean enabled() {
        var setting = System.getenv(ENABLE_ENV);
        if (setting == null) setting = System.getProperty("wyrdsekai.sleep.write");
        var on = enabled(setting, SleepWeightWrite::trainerReady);
        if (on == Boolean.FALSE && setting == null && !SAID_NOT_SET_UP.getAndSet(true)) {
            log.info("Nightly learning is on by default, but its trainer is not set up on this node "
                + "({}); nights run without it. Set it up with: wyrd brain setup", active().name());
        }
        return on;
    }

    /** The switch as read from its setting; {@code ready} is asked only when the setting leaves it to the trainer. */
    static boolean enabled(String setting, java.util.function.BooleanSupplier ready) {
        if (setting != null) {
            var v = setting.trim().toLowerCase(Locale.ROOT);
            if (v.equals("0") || v.equals("false") || v.equals("off") || v.equals("no")) return false;
            if (v.equals("1") || v.equals("true") || v.equals("on") || v.equals("yes")) return true;
        }
        return ready.getAsBoolean();
    }

    private static final AtomicBoolean SAID_NOT_SET_UP = new AtomicBoolean(false);

    /** This node's trainer is set up: its Python environment and what it trains from are in place. */
    static boolean trainerReady() {
        var data = WyrdConfig.get().dataDir();
        return trainerReady(active(), System::getenv, data == null ? null : Path.of(data),
            Path.of(System.getProperty("user.home"))) && resolveScript(active().scriptLeaf()) != null;
    }

    /**
     * The same, for a given trainer, environment, data folder and home, as each trainer's own
     * preflight looks for them: brain_write.py (its environment and the training bundle),
     * brain_write_mlx.py (the MLX environment and the MLX model), sleep_write.py (its environment
     * and the voice model's checkpoint).
     */
    static boolean trainerReady(Trainer t, java.util.function.Function<String, String> env, Path data, Path home) {
        if (t == HOME_SPARSE) {
            var venv = orElse(env.apply(BRAIN_VENV_ENV), data == null ? null : data.resolve("brainwrite-venv"));
            var bundle = orElse(env.apply("WYRDSEKAI_BRAIN_WRITE_BUNDLE"), data == null ? null : data.resolve("models").resolve("brain-bundle"));
            return python(venv) && bundle != null
                && Files.isRegularFile(bundle.resolve("bundle.json")) && Files.isRegularFile(bundle.resolve("spine.safetensors"));
        }
        if (t == HOME_SPARSE_MLX) {
            var venv = orElse(env.apply(BRAIN_VENV_ENV), home.resolve(".wyrdsekai").resolve("mlx-venv"));
            var model = orElse(env.apply("WYRDSEKAI_BRAIN_WRITE_MLX_MODEL"), data == null ? null : data.resolve("models").resolve("brain-mlx"));
            return python(venv) && model != null && Files.isRegularFile(model.resolve("config.json"));
        }
        var venv = orElse(env.apply(VENV_ENV), data == null ? null : data.resolve("sleepwrite-venv"));
        var base = orElse(env.apply("WYRDSEKAI_SLEEP_WRITE_BASE"), data == null ? null : data.resolve("models").resolve("sleepwrite-base"));
        return python(venv) && base != null && Files.isDirectory(base);
    }

    private static Path orElse(String setting, Path fallback) {
        return setting != null && !setting.isBlank() ? Path.of(setting) : fallback;
    }

    private static boolean python(Path venv) {
        return venv != null && Files.isExecutable(venv.resolve("bin").resolve("python"));
    }

    /**
     * True when the write stops the only model the household has. Under the single-model profile
     * the trainer needs the card the one server sits on, so nothing can answer while it runs and
     * the companion stays asleep until it is over.
     */
    public static boolean stopsTheOnlyModel() {
        return WyrdConfig.get().singleBrain();
    }

    /** The next write is a rehearsal: it trains and measures the gate, and stages and keeps nothing. */
    public static void rehearseNext() {
        REHEARSE_REQUESTED.set(Instant.now());
    }

    /** True while a rehearsal has been asked for and no write has taken it up yet. */
    public static boolean rehearsalPending() {
        return rehearsalPending(REHEARSE_REQUESTED.get(), Instant.now());
    }

    static boolean rehearsalPending(Instant requestedAt, Instant now) {
        return requestedAt != null && Duration.between(requestedAt, now).compareTo(REHEARSAL_REQUEST_LIFETIME) <= 0;
    }

    /**
     * Fire the night's write. Returns immediately; the subprocess runs on a
     * virtual thread and reports through the log. Never throws.
     */
    public static void fireAndForget(String agentName) {
        fireAndForget(agentName, null);
    }

    /**
     * @param entityId who the night is for; the mark that says how the write went is
     *                 addressed to her (the sleep plan, item 1)
     */
    public static void fireAndForget(String agentName, String entityId) {
        fire(agentName, entityId);
    }

    /** As {@link #fireAndForget(String, String)}; the future completes when the write is over, at once when none ran. */
    public static CompletableFuture<Void> fire(String agentName, String entityId) {
        var done = new CompletableFuture<Void>();
        var rehearsal = takeRehearsal();
        if (!enabled() && !rehearsal) {
            done.complete(null);
            return done;
        }
        Thread.ofVirtual().name("sleep-weight-write").start(() -> {
            try {
                runBlocking(agentName, entityId, rehearsal);
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    /** Take up a pending rehearsal request: true once, for the night that takes it. */
    static boolean takeRehearsal() {
        var asked = REHEARSE_REQUESTED.getAndSet(null);
        return rehearsalPending(asked, Instant.now());
    }

    /**
     * One being's write, on the calling thread; returns when it is over. Never throws. The
     * household's night calls this once per sleeper, one after another.
     */
    static void runBlocking(String agentName, String entityId, boolean rehearsal) {
        var trainer = active();
        var script = resolveScript(trainer.scriptLeaf());
        if (script == null) {
            log.warn("Sleep weight-write enabled but "
                + "scripts/training/sleepwrite/{} not found — skipped", trainer.scriptLeaf());
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            log.info("Sleep weight-write for '{}' skipped — previous write still "
                + "running (single-flight; the window catches up next sleep)", agentName);
            return;
        }
        try {
            run(agentName, entityId, script, rehearsal);
        } catch (Exception e) {
            log.warn("Sleep weight-write for '{}' errored: {}", agentName, e.toString());
            mark(entityId, "The night's write on my voice broke before it could finish; nothing in me changed.",
                "error=" + e);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    /** A mark in a being's ledger from the household's night (why no write ran for her). */
    static void markNight(String entityId, String text, String detail) {
        mark(entityId, text, detail);
    }

    private static void run(String agentName, String entityId, Path script, boolean rehearsal) throws Exception {
        ActivityGauge.maintenanceStarted();   // the self-updater waits for the night's write
        try {
            runInner(agentName, entityId, script, rehearsal);
        } finally {
            ActivityGauge.maintenanceFinished();
        }
    }

    private static void runInner(String agentName, String entityId, Path script, boolean rehearsal) throws Exception {
        var interpreter = interpreter();
        log.info("Sleep weight-write for '{}'{}: {} {}", agentName, rehearsal ? " (rehearsal)" : "", interpreter, script);
        var pb = new ProcessBuilder(interpreter, script.toString());
        if (rehearsal) pb.environment().put("WYRDSEKAI_SLEEP_WRITE_REHEARSE", "1");
        // The trail is shared by every companion on the node; the night is for this one.
        if (entityId != null && !entityId.isBlank()) pb.environment().put("WYRDSEKAI_SLEEP_WRITE_AGENT_ID", entityId);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var tail = new ArrayDeque<String>(12);
        try (var r = new BufferedReader(new InputStreamReader(
                proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (tail.size() == 12) tail.removeFirst();
                tail.addLast(line);
            }
        }
        if (!proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            log.warn("Sleep weight-write for '{}' timed out after {} min — killed; "
                + "nothing staged", agentName, TIMEOUT_MINUTES);
            mark(entityId, "The night's write on my voice ran too long and was stopped; nothing in me changed.",
                "timeout_minutes=" + TIMEOUT_MINUTES);
            // The trainer pauses her voice container for the write window and
            // its own finally resumes it — but destroyForcibly is SIGKILL and
            // skips finally. Restore here so a killed write can never leave
            // her voiceless. Idempotent: docker start on a running container
            // is a no-op.
            restoreVoice();
            return;
        }
        restoreVoice();
        var summary = String.join(" | ", tail);
        switch (proc.exitValue()) {
            case 0 -> {
                log.info("Sleep weight-write for '{}' STAGED — the day sank in. {}",
                    agentName, summary);
                var guard = autoApply(agentName, entityId);
                mark(entityId, "The night's write on my voice sank in" + guardClause(guard) + ".",
                    "exit=0 guard=" + guard);
                if ("PASS".equals(guard)) scheduleWatch(agentName, entityId);
            }
            case 3 -> {
                log.info("Sleep weight-write for '{}': quiet day, nothing to "
                    + "consolidate. {}", agentName, summary);
                mark(entityId, "A quiet day; the night's write had nothing to consolidate.", "exit=3");
            }
            case 4 -> {
                log.warn("Sleep weight-write for '{}': GATE FAILED — nothing "
                    + "staged (adapter kept for autopsy). {}", agentName, summary);
                mark(entityId, "The night's write on my voice failed its own gate and was set aside; nothing in me changed.",
                    "exit=4");
            }
            case EXIT_NOT_THE_OWNER -> {
                log.info("Sleep weight-write for '{}': the node's voice adapter carries another companion's nights "
                    + "and the voice server loads one adapter for every request — nothing written. {}", agentName, summary);
                mark(entityId, "The household's voice carries one companion's nights and they are not mine; nothing in me changed.",
                    "exit=8");
            }
            case EXIT_REHEARSAL -> {
                log.info("Sleep weight-write for '{}': REHEARSAL — trained and measured, nothing staged or kept. {}",
                    agentName, summary);
                mark(entityId, "The night's write on my voice was rehearsed; nothing in me changed.", "exit=7 rehearsal");
            }
            case 6 -> {
                log.warn("Sleep weight-write for '{}': this machine cannot run the write "
                    + "(training bundle missing or too little free VRAM or disk). {}", agentName, summary);
                mark(entityId, "The night's write on my voice could not run on this machine tonight; nothing in me changed.",
                    "exit=6");
            }
            default -> {
                log.warn("Sleep weight-write for '{}' failed (exit {}). {}",
                    agentName, proc.exitValue(), summary);
                mark(entityId, "The night's write on my voice failed; nothing in me changed.",
                    "exit=" + proc.exitValue());
            }
        }
    }

    /**
     * Best-effort voice-container restore after the write window (the trainer
     * pauses it so the 4-bit load's transients don't ride a 20MB VRAM margin
     * — measured 2026-09-01). The trainer's own finally is the primary
     * restore; this is the belt for the paths that skip finally. Swallows
     * everything: on boxes with no docker or no container it must be silent.
     */
    private static void restoreVoice() {
        var container = active().container();
        if (container == null) return;   // a native server: no container was paused
        try {
            var pb = new ProcessBuilder("docker", "start", container);
            pb.redirectErrorStream(true);
            var p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(3, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // no docker here, or no container — nothing was paused
        }
    }

    /**
     * Auto-apply-at-wake: a staged adapter should reach her voice without a
     * human remembering to run `apply`. Delegates to `wyrd sleepwrite apply
     * --if-idle`, which refuses to bounce the voice while a reply is in
     * flight (she may already be awake by the time the write finishes) and
     * leaves the adapter staged for the next restart instead. Disable with
     * WYRDSEKAI_SLEEP_WRITE_AUTO_APPLY=0.
     */
    /**
     * @return the morning guard's verdict after the apply ("PASS", "FAIL", "UNMEASURABLE"),
     *         "STAGED" when the adapter waits for a restart, or "UNKNOWN"
     */
    private static String autoApply(String agentName, String entityId) {
        var env = System.getenv(AUTO_APPLY_ENV);
        if (env == null) env = System.getProperty("wyrdsekai.sleep.write.auto.apply");
        if ("0".equals(env) || "false".equalsIgnoreCase(env)) {
            log.info("Sleep weight-write auto-apply disabled — adapter staged for "
                + "manual `wyrd sleepwrite apply`");
            return "STAGED";
        }
        var wyrd = resolveWyrd();
        if (wyrd == null) {
            log.warn("Sleep weight-write: wyrd CLI not found — adapter staged, "
                + "applies at next voice restart");
            return "STAGED";
        }
        var guardBefore = guardStamp(entityId);
        try {
            var cmd = new ArrayList<>(List.of(wyrd.toString(), "sleepwrite", "apply", "--if-idle"));
            if (active().applyFlag() != null) cmd.add(active().applyFlag());
            if (perBeing(active()) && entityId != null && !entityId.isBlank()) cmd.addAll(List.of("--being", entityId));
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(3, TimeUnit.MINUTES);
            log.info("Sleep weight-write auto-apply for '{}' (exit {}): {}",
                agentName, proc.isAlive() ? "timeout" : proc.exitValue(),
                out.replaceAll("\s+", " ").trim());
            var after = guardStamp(entityId);
            if (after != null && !after.equals(guardBefore)) return guardVerdict(entityId);
            return out.contains("staged") || out.contains("next restart") ? "STAGED" : "UNKNOWN";
        } catch (Exception e) {
            log.warn("Sleep weight-write auto-apply failed for '{}': {} — adapter "
                + "staged, applies at next voice restart", agentName, e.toString());
            return "STAGED";
        }
    }

    /** How long after an applied night her real lines are judged, and how often until there are enough. */
    static final Duration WATCH_AFTER = Duration.ofHours(2);
    static final Duration WATCH_RETRY = Duration.ofHours(1);
    /** Hourly for a day: the watch reads only lines made with her night, and those come only with a person. */
    static final int WATCH_ATTEMPTS = 22;

    /**
     * The guard's prompts are answered without her identity prompt, her drives and the room, and
     * a tic lives in those turns: a night that read as plain prose to the guard put one sentence
     * frame in 46% of her real lines (5% with it off, 2026-09-22). So an applied night is judged
     * again by what she actually says with it raised: `wyrd sleepwrite watch` two hours on, and hourly
     * until she has said enough. A night that fails is rolled back by the launcher and her ledger says so.
     */
    private static void scheduleWatch(String agentName, String entityId) {
        if (!perBeing(active()) || entityId == null || entityId.isBlank()) return;
        var wyrd = resolveWyrd();
        if (wyrd == null) return;
        var since = Instant.now().toString();
        watchLater(agentName, entityId, since, wyrd, 1, WATCH_AFTER, servedNight(entityId));
    }

    /** The watch still owed on a being's night, kept beside it so a restart does not drop it. */
    static final String WATCH_PENDING = "watch-pending.json";

    /**
     * Re-arm every watch a restart dropped. A watch lived only in memory, so the service restart
     * of an install silently lost the one pending on a night served since that morning (2026-09-22).
     * A watch whose night is no longer served (rolled back, or replaced) is let go.
     */
    public static void resumeWatches() {
        if (!perBeing(active())) return;
        var root = sleepwriteDir(null);
        var wyrd = resolveWyrd();
        if (root == null || wyrd == null || !Files.isDirectory(root)) return;
        try (var beings = Files.list(root)) {
            for (var dir : beings.filter(Files::isDirectory).toList()) {
                var f = dir.resolve(WATCH_PENDING);
                if (!Files.exists(f)) continue;
                var entityId = dir.getFileName().toString();
                try {
                    var n = Json.mapper().readTree(f.toFile());
                    var adapter = n.hasNonNull("adapter") ? n.path("adapter").asText() : null;
                    if (!watchedNightServed(entityId, adapter)) {
                        Files.deleteIfExists(f);
                        log.info("Night watch for '{}' let go: the night it was for is no longer served", entityId);
                        continue;
                    }
                    var due = Instant.parse(n.path("due").asText());
                    var delay = Duration.between(Instant.now(), due);
                    if (delay.compareTo(Duration.ofMinutes(5)) < 0) delay = Duration.ofMinutes(5);
                    int attempt = Math.max(1, n.path("attempt").asInt(1));
                    log.info("Night watch for '{}' re-armed after a restart (attempt {}, in {} min)",
                        entityId, attempt, delay.toMinutes());
                    watchLater(n.path("agentName").asText(entityId), entityId, n.path("since").asText(), wyrd,
                        attempt, delay, adapter);
                } catch (Exception e) {
                    log.warn("The pending night watch for '{}' could not be read: {}", entityId, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Pending night watches not resumed: {}", e.toString());
        }
    }

    private static void rememberWatch(String agentName, String entityId, String since, int attempt, Duration delay,
                                      String adapter) {
        rememberWatch(sleepwriteDir(entityId), agentName, since, attempt, delay, adapter);
    }

    static void rememberWatch(Path dir, String agentName, String since, int attempt, Duration delay) {
        rememberWatch(dir, agentName, since, attempt, delay, null);
    }

    /** {@code adapter} names the night the watch is for; a watch outlives the night it judged otherwise. */
    static void rememberWatch(Path dir, String agentName, String since, int attempt, Duration delay, String adapter) {
        try {
            if (dir == null) return;
            Files.createDirectories(dir);
            var node = Json.mapper().createObjectNode()
                .put("agentName", agentName).put("since", since).put("attempt", attempt)
                .put("due", Instant.now().plus(delay).toString());
            if (adapter != null) node.put("adapter", adapter);
            Files.writeString(dir.resolve(WATCH_PENDING), node.toString());
        } catch (Exception e) {
            log.debug("pending night watch not kept on disk: {}", e.toString());
        }
    }

    private static void forgetWatch(String entityId, String since) {
        forgetWatch(sleepwriteDir(entityId), since);
    }

    /** Clear the pending watch, unless a later night's watch has taken its place. */
    static void forgetWatch(Path dir, String since) {
        try {
            if (dir == null) return;
            var f = dir.resolve(WATCH_PENDING);
            if (!Files.exists(f)) return;
            if (since.equals(Json.mapper().readTree(f.toFile()).path("since").asText())) Files.deleteIfExists(f);
        } catch (Exception e) {
            log.debug("pending night watch not cleared: {}", e.toString());
        }
    }

    /** Whether a being's night is still the one served (the file the launcher raises). */
    private static boolean nightServed(String entityId) {
        var dir = sleepwriteDir(entityId);
        return dir != null && Files.exists(dir.resolve("current.gguf"));
    }

    /** The adapter file the trainer last wrote for a being ({@code state.json}), or null when unknown. */
    static String servedNight(String entityId) {
        return servedNight(sleepwriteDir(entityId));
    }

    static String servedNight(Path dir) {
        try {
            if (dir == null) return null;
            var f = dir.resolve("state.json");
            if (!Files.exists(f)) return null;
            var n = Json.mapper().readTree(f.toFile()).path("last_adapter");
            return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the night a watch was scheduled for is still the one served. A night replaced by a
     * later one is a night no longer served: the household node ran two watches on one being for a
     * day, hourly, because the watch for the morning's night asked only whether any night was served
     * (2026-09-24). A watch that does not know its night (older pending files) asks as before.
     */
    private static boolean watchedNightServed(String entityId, String adapter) {
        return watchedNightServed(sleepwriteDir(entityId), adapter);
    }

    static boolean watchedNightServed(Path dir, String adapter) {
        if (dir == null || !Files.exists(dir.resolve("current.gguf"))) return false;
        if (adapter == null) return true;
        var now = servedNight(dir);
        return now == null || now.equals(adapter);
    }

    private static void watchLater(String agentName, String entityId, String since, Path wyrd, int attempt, Duration delay,
                                   String adapter) {
        rememberWatch(agentName, entityId, since, attempt, delay, adapter);
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            boolean again = false;
            try {
                // A rollback removes the night without reaching this chain, and a later night replaces
                // it; the restart path lets go of such a watch, and so does this one.
                if (!watchedNightServed(entityId, adapter)) {
                    log.info("Night watch for '{}' let go (attempt {}): the night it was for is no longer served",
                        agentName, attempt);
                    return;
                }
                var cmd = new ArrayList<>(List.of(wyrd.toString(), "sleepwrite", "watch", "--since", since, "--being", entityId));
                if (active().applyFlag() != null) cmd.add(active().applyFlag());
                var pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("\s+", " ").trim();
                proc.waitFor(5, TimeUnit.MINUTES);
                int rc = proc.isAlive() ? -1 : proc.exitValue();
                log.info("Night watch for '{}' (attempt {}, exit {}): {}", agentName, attempt, rc, out);
                if (rc == 2) {
                    mark(entityId, "The night's write put my words onto one frame; it was set aside and I speak as before.", "watch=FAIL");
                } else if (rc == 3 && attempt < WATCH_ATTEMPTS) {
                    again = true;
                    watchLater(agentName, entityId, since, wyrd, attempt + 1, WATCH_RETRY, adapter);
                } else if (rc == 3) {
                    // Nothing said this ending before: a night nobody judged read, in the log, like
                    // one still being judged (rose's, 2026-09-23).
                    log.info("Night watch for '{}' gave up after {} attempts: too few of her lines were "
                        + "made with the night to judge it; {}", agentName, attempt,
                        nightServed(entityId) ? "the night stays applied" : "the night is no longer served");
                }
            } catch (Exception e) {
                log.warn("Night watch for '{}' could not run: {}", agentName, e.toString());
            } finally {
                if (!again) forgetWatch(entityId, since);
            }
        });
    }

    // ── the mark she reads on waking ──

    private static void mark(String entityId, String text, String detail) {
        var map = BodyMap.get();
        if (map == null) return;
        try {
            map.mark("night", "sleep", entityId, text, detail);
        } catch (RuntimeException e) {
            log.debug("Night mark not written: {}", e.toString());
        }
    }

    private static String guardClause(String guard) {
        return switch (guard == null ? "" : guard) {
            case "PASS" -> " and the morning guard passed";
            case "FAIL" -> ", but the morning guard set it aside and my voice is back on the base weights";
            case "UNMEASURABLE" -> "; the morning guard could not measure it";
            case "STAGED" -> " and waits for my voice to restart";
            default -> "";
        };
    }

    public static Path sleepwriteDir() {
        return sleepwriteDir(null);
    }

    /**
     * Where a being's window, results and adapter live. The large model's trainers keep one
     * directory per being under {@code adapters/brainwrite}; the two-model stack's voice adapter
     * has one owner and stays where it always was.
     */
    public static Path sleepwriteDir(String entityId) {
        var data = WyrdConfig.get().dataDir();
        if (data == null) data = System.getProperty("wyrdsekai.data.dir");
        if (data == null) return null;
        var dir = Path.of(data, "adapters", active().outSubdir());
        return perBeing(active()) && entityId != null && !entityId.isBlank() ? dir.resolve(entityId) : dir;
    }

    /** True for a trainer whose adapters the served model loads one per being. */
    static boolean perBeing(Trainer trainer) {
        return trainer != VOICE_4B;
    }

    /** The guard file's modification stamp, to tell a fresh verdict from last night's. */
    private static String guardStamp(String entityId) {
        try {
            var dir = sleepwriteDir(entityId);
            if (dir == null) return null;
            var f = dir.resolve("guard-last.json");
            return Files.exists(f) ? Files.getLastModifiedTime(f).toString() + ":" + Files.size(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The verdict in guard-last.json: PASS, FAIL or UNMEASURABLE; UNKNOWN when unreadable. */
    static String guardVerdict() {
        return guardVerdict(null);
    }

    static String guardVerdict(String entityId) {
        try {
            var dir = sleepwriteDir(entityId);
            if (dir == null) return "UNKNOWN";
            var text = Files.readString(dir.resolve("guard-last.json"));
            var m = java.util.regex.Pattern.compile("\"verdict\"\s*:\s*\"([A-Z_]+)\"").matcher(text);
            return m.find() ? m.group(1) : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    static Path resolveWyrd() {
        var candidates = new Path[] {
            Path.of("/opt/wyrdsekai/bin/wyrd"),
            Path.of("/usr/local/wyrdsekai/bin/wyrd"),
            Path.of("/usr/local/bin/wyrd"),
            Path.of("bin", "wyrd"),
            Path.of("..", "bin", "wyrd"),
        };
        for (var c : candidates) if (Files.isExecutable(c)) return c;
        return null;
    }

    private static String interpreter() {
        if (active() == HOME_SPARSE_MLX) {
            // The MLX trainer runs in the Mac's MLX environment: WYRDSEKAI_BRAIN_WRITE_VENV, else
            // ~/.wyrdsekai/mlx-venv (where the MLX bootstrap script puts it).
            var own = System.getenv(BRAIN_VENV_ENV);
            if (own != null && !own.isBlank() && Files.isExecutable(Path.of(own, "bin", "python"))) {
                return Path.of(own, "bin", "python").toString();
            }
            var home = Path.of(System.getProperty("user.home"), ".wyrdsekai", "mlx-venv", "bin", "python");
            if (Files.isExecutable(home)) return home.toString();
        }
        if (active() == HOME_SPARSE) {
            // The single-model trainer needs newer libraries than the 4B trainer pins, so it has
            // its own environment: WYRDSEKAI_BRAIN_WRITE_VENV, else <data>/brainwrite-venv.
            var own = System.getenv(BRAIN_VENV_ENV);
            if ((own == null || own.isBlank()) && WyrdConfig.get().dataDir() != null) {
                own = Path.of(WyrdConfig.get().dataDir(), "brainwrite-venv").toString();
            }
            if (own != null && Files.isExecutable(Path.of(own, "bin", "python"))) {
                return Path.of(own, "bin", "python").toString();
            }
        }
        var venv = System.getenv(VENV_ENV);
        if (venv == null) venv = System.getProperty("wyrdsekai.sleep.write.venv");
        if (venv != null && !venv.isBlank()) {
            var p = Path.of(venv, "bin", "python");
            if (Files.isExecutable(p)) return p.toString();
        }
        return "python3";
    }

    /** Same search order as ClassifierForge.resolveScriptDir, sleepwrite subdir. */
    static Path resolveScript() {
        return resolveScript(VOICE_4B.scriptLeaf());
    }

    static Path resolveScript(String leaf) {
        var envDir = WyrdConfig.get().scriptsDir();
        if (envDir != null && !envDir.isBlank()) {
            var p = Path.of(envDir, "training", "sleepwrite", leaf);
            if (Files.isRegularFile(p)) return p;
        }
        var sysDir = System.getProperty("wyrdsekai.scripts");
        if (sysDir != null && !sysDir.isBlank()) {
            var p = Path.of(sysDir, "training", "sleepwrite", leaf);
            if (Files.isRegularFile(p)) return p;
        }
        var candidates = new Path[] {
            Path.of("scripts", "training", "sleepwrite", leaf),
            Path.of("..", "scripts", "training", "sleepwrite", leaf),
            Path.of("/opt/wyrdsekai/scripts/training/sleepwrite", leaf),
            Path.of(System.getProperty("user.home"),
                ".wyrdsekai", "scripts", "training", "sleepwrite", leaf),
        };
        for (var c : candidates) if (Files.isRegularFile(c)) return c;
        return null;
    }
}
