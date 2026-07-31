package org.wyrdsekai.core.substrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.SignificanceBuffer;
import org.wyrdsekai.core.substrate.training.NodeCapacity;
import org.wyrdsekai.core.substrate.training.PeerCapacity;
import org.wyrdsekai.core.substrate.training.PeerTrainingTransport;
import org.wyrdsekai.core.substrate.training.TrainingExecutor;
import org.wyrdsekai.core.substrate.training.TrainingStrategySelector;
import org.wyrdsekai.core.substrate.training.UserTrainingPolicy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator for a single deep-sleep voice-alignment cycle.
 *
 * <p>Deep sleep is the sovereign-node training cycle: the agent pauses
 * external inference, runs LoRA fine-tuning against its own accumulated
 * significance corpus, hot-reloads the adapter, and wakes with voice
 * that matches its evolved personality.</p>
 *
 * <p>Flow (each step is graceful — failure at any point skips the rest
 * without blocking wake-up):</p>
 * <ol>
 *   <li><b>Pre-flight</b> — feature flag {@code WYRDSEKAI_VOICE_ALIGN=1} must
 *       be set. Model path, adapter dir, backend (unsloth/mlx/torchtune)
 *       must all resolve. Without these we return SKIPPED.</li>
 *   <li><b>Corpus export</b> — pull significance entries from the agent's
 *       buffer, shape into {system, user, assistant} turns, write JSONL.
 *       Skips if corpus is below {@code minCorpusSize}.</li>
 *   <li><b>Pause inference</b> — signal local llama-server to release
 *       VRAM. Without this 9B training OOMs on a 16GB card.</li>
 *   <li><b>Align</b> — delegate to {@link VoiceAligner} which shells to
 *       Unsloth/MLX and produces a LoRA adapter directory.</li>
 *   <li><b>Resume inference</b> — restart llama-server with
 *       {@code --lora &lt;adapter&gt;} so the next inference uses the
 *       freshly-trained voice.</li>
 * </ol>
 *
 * <p>Step 2 contract: each step has a real implementation. The feature
 * flag keeps production off until Step 3 validates real 9B training on a
 * 16GB GPU. Unit-testability is preserved via constructor injection.</p>
 */
public final class DeepSleepTrainer {

    private static final Logger log = LoggerFactory.getLogger(DeepSleepTrainer.class);

    /** Feature flag — must be "1" for real alignment to run. */
    public static final String FEATURE_FLAG_ENV = "WYRDSEKAI_VOICE_ALIGN";

    /** When {@code 1}, run {@link VoiceForgeHook} after successful alignment+resume. */
    public static final String FORGE_FLAG_ENV = "WYRDSEKAI_VOICE_FORGE";

    /** Path to base model in HF format (required for LoRA). */
    public static final String MODEL_PATH_ENV = "WYRDSEKAI_BASE_MODEL_PATH";

    /** Where adapters are written. Defaults to ~/.wyrdsekai/adapters. */
    public static final String ADAPTER_DIR_ENV = "WYRDSEKAI_ADAPTER_DIR";

    /**
     * Sum-of-loaded-model footprint in GB — caller's estimate of how much VRAM
     * is currently held by inference backends (e.g. 9B + 4B ≈ 13.5GB).
     * Used by the selector to decide if training fits without pausing models.
     * Default 13.5GB matches dual-inference (9B skills + 4B voice) on home-server.
     */
    public static final String ACTIVE_MODELS_GB_ENV = "WYRDSEKAI_ACTIVE_MODELS_GB";

    /**
     * Estimated peak VRAM for the configured training run. Default 12GB
     * matches Unsloth QLoRA on a 9B base; tune down for 4B (~6GB).
     */
    public static final String TRAINING_VRAM_ESTIMATE_GB_ENV = "WYRDSEKAI_TRAINING_VRAM_ESTIMATE_GB";

    /** Minimum corpus turns before we bother training. */
    private static final int MIN_CORPUS_SIZE = 10;

    public enum Outcome { COMPLETED, SKIPPED_FLAG_OFF, SKIPPED_NO_BACKEND,
        SKIPPED_NO_MODEL, SKIPPED_EMPTY_CORPUS, FAILED }

    public record Result(Outcome outcome, String detail, Path adapterPath) {
        public boolean success() { return outcome == Outcome.COMPLETED; }
    }

    private final Path workDir;
    private final InferenceController inferenceController;
    /** Nullable — when set and {@link #FORGE_FLAG_ENV}==1, runs the voice-profile forge pass. */
    private final VoiceForgeHook forgeHook;

    public DeepSleepTrainer(Path workDir, InferenceController inferenceController) {
        this(workDir, inferenceController, null);
    }

    /**
     * @param forgeHook Optional callback that proposes/applies one VoiceProfile
     *                  revision after successful alignment. Gated by
     *                  {@link #FORGE_FLAG_ENV}. Null = phase 4 disabled.
     */
    public DeepSleepTrainer(Path workDir, InferenceController inferenceController,
                             VoiceForgeHook forgeHook) {
        this.workDir = workDir;
        this.inferenceController = inferenceController;
        this.forgeHook = forgeHook;
    }

    /**
     * Post-alignment hook that lets callers plug in a {@link
     * org.wyrdsekai.core.soul.VoiceProfileForge} pass without coupling this
     * class to the soul package. Caller binds the companion's DID at
     * construction; the trainer supplies recent "assistant" turns from the
     * corpus it already built.
     */
    @FunctionalInterface
    public interface VoiceForgeHook {
        /**
         * Runs after successful adapter alignment + resume, only when
         * {@link #FORGE_FLAG_ENV}==1. Implementations must not throw —
         * swallow errors; the trainer treats the pass as best-effort.
         *
         * @param sampleTurns Recent assistant turns (most-recent last),
         *                    suitable for reflective prompting.
         */
        void runPass(List<String> sampleTurns);
    }

    /** Controls the local inference backend's VRAM lifecycle. */
    public interface InferenceController {
        /** Release GPU memory. Returns true on success. */
        boolean pause();
        /**
         * Bring inference back online. If {@code adapterPath} is non-null,
         * load with the adapter attached.
         */
        boolean resume(Path adapterPath);
    }

    /**
     * Default controller: scoped pause/resume.
     *
     * <p><b>Dual-inference mode</b> (preferred): if env var
     * {@code WYRDSEKAI_VOICE_BACKEND_CONTAINER} is set (e.g.
     * {@code wyrdsekai-llama-voice}), pause/resume targets ONLY that container
     * via `docker restart`. The skills backend (9B) stays online for the
     * full sleep cycle — agent keeps answering skill questions; only voice
     * briefly pauses while the adapter rotates.</p>
     *
     * <p><b>Single-inference fallback</b>: if the env var is unset, shells to
     * {@code bin/wyrd inference disable} / {@code local} — stops everything.
     * Acceptable when voice and skills share one backend.</p>
     */
    public static final class WyrdCliInferenceController implements InferenceController {
        /** Env var naming the docker container that hosts the voice backend. */
        public static final String VOICE_CONTAINER_ENV = "WYRDSEKAI_VOICE_BACKEND_CONTAINER";

        private final Path wyrdBin;
        /** Snapshot of whether local inference was hosting on this node at pause-time.
         *  Set by pause(); read by resume(). null = pause() not yet called. */
        private volatile Boolean wasLocallyHosted;

        public WyrdCliInferenceController(Path wyrdBin) {
            this.wyrdBin = wyrdBin;
        }

        private static String voiceContainer() {
            var c = System.getenv(VOICE_CONTAINER_ENV);
            return (c == null || c.isBlank()) ? null : c;
        }

        /** True if WYRDSEKAI_INFERENCE_URL points at this host (or is unset and
         *  defaults to localhost) — i.e. this node actually runs llama-server.
         *  False when the node routes inference to a peer (http://home-server:..., nats://zone). */
        private static boolean isLocallyHosted() {
            var url = WyrdConfig.get().resolve(
                "WYRDSEKAI_INFERENCE_URL", "inference.url", () -> null);
            if (url == null || url.isBlank()) return false;  // unset = no inference here
            url = url.trim().toLowerCase();
            return url.contains("127.0.0.1") || url.contains("localhost");
        }

        @Override
        public boolean pause() {
            var container = voiceContainer();
            if (container != null) {
                log.info("DeepSleep pause: docker stop {} (skills backend "
                        + "stays online)", container);
                return runCommand(60, "docker", "stop", container);
            }
            // Snapshot for resume(): if this node doesn't host local inference,
            // pause + resume are both no-ops. Avoids the resume-failed path on
            // nodes that route to a peer (e.g. mac-node → home-server).
            wasLocallyHosted = isLocallyHosted();
            if (!wasLocallyHosted) {
                log.info("DeepSleep pause: no local inference on this node "
                        + "(inference.url={}) — pause is a no-op",
                        WyrdConfig.get().resolve(
                            "WYRDSEKAI_INFERENCE_URL", "inference.url", () -> null));
                return true;
            }
            log.info("DeepSleep pause: single-inference mode — stopping all "
                    + "via bin/wyrd inference disable");
            return runCommand(120, wyrdBin.toString(), "inference", "disable");
        }

        @Override
        public boolean resume(Path adapterPath) {
            var container = voiceContainer();
            if (container != null) {
                // Voice container mounts adapter path at build time; if the
                // adapter.gguf file was swapped on disk, docker start picks
                // up the new one at reload. Restart is idempotent: if already
                // running (e.g. pause() failed earlier), it re-starts cleanly.
                log.info("DeepSleep resume: docker start {} (adapter at {})",
                        container, adapterPath);
                return runCommand(120, "docker", "start", container);
            }
            // Mirror the pause snapshot: if pause was a no-op, resume is too.
            // This covers (a) nodes that route inference to a peer, and
            // (b) the crash-recovery resume(null) call from LocalSerialExecutor's
            // finally block when nothing was paused in the first place.
            if (Boolean.FALSE.equals(wasLocallyHosted)) {
                log.info("DeepSleep resume: no local inference on this node — "
                        + "resume is a no-op (adapter at {})", adapterPath);
                return true;
            }
            if (adapterPath != null) {
                // Legacy single-inference path: no per-container rotation.
                var env = System.getenv();
                if (!env.containsKey("WYRDSEKAI_LORA_ADAPTER")) {
                    log.warn("Adapter produced at {} but start script doesn't "
                            + "read WYRDSEKAI_LORA_ADAPTER — manual rewire needed",
                            adapterPath);
                }
            }
            return runCommand(180, wyrdBin.toString(), "inference", "local");
        }

        private static boolean runCommand(int timeoutS, String... cmd) {
            try {
                var pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                var p = pb.start();
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.debug("[inference-ctl] {}", line);
                    }
                }
                if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (Exception e) {
                log.warn("Inference control command failed: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * Run the full cycle for one agent. Never throws — failures return a
     * Result with the failure detail.
     */
    /**
     * Alternate entry point used when the significance buffer was already
     * consumed by an earlier pass (e.g. SoulMaintenanceCycle's wake-Forge
     * during sleep init). Caller passes the captured entries explicitly so
     * the trainer has a corpus to work with. #415 + post-live-fix 2026-04-24.
     */
    public Result run(String agentId, String agentName,
                      List<SignificanceBuffer.Entry> preCapturedEntries) {
        var wrapped = new SignificanceBuffer();
        if (preCapturedEntries != null) {
            for (var e : preCapturedEntries) {
                if (e == null || e.superseded()) continue;
                if (e.content() != null && !e.content().isBlank()) {
                    wrapped.remember(e.content(), 0.7f);
                }
            }
        }
        return run(agentId, agentName, wrapped);
    }

    public Result run(String agentId, String agentName,
                      SignificanceBuffer buffer) {
        // 1. Pre-flight
        if (!"1".equals(System.getenv(FEATURE_FLAG_ENV))) {
            log.info("DeepSleepTrainer: feature flag {} != 1 — skipping "
                    + "voice alignment for '{}' (stub mode)",
                    FEATURE_FLAG_ENV, agentName);
            return new Result(Outcome.SKIPPED_FLAG_OFF, "flag-off", null);
        }
        var modelPath = System.getenv(MODEL_PATH_ENV);
        if (modelPath == null || modelPath.isBlank()) {
            log.warn("DeepSleepTrainer: {} not set — cannot locate base "
                    + "model. Skipping alignment for '{}'.", MODEL_PATH_ENV, agentName);
            return new Result(Outcome.SKIPPED_NO_MODEL, "no-model-path", null);
        }
        var adapterDir = System.getenv(ADAPTER_DIR_ENV);
        var adapterRoot = adapterDir != null && !adapterDir.isBlank()
                ? Path.of(adapterDir)
                : workDir.resolve("adapters");

        // 2. Build corpus from significance buffer
        var corpus = buildCorpus(buffer, agentName);
        if (corpus.size() < MIN_CORPUS_SIZE) {
            log.info("DeepSleepTrainer: corpus for '{}' has {} turns "
                    + "(need {}) — skipping this cycle",
                    agentName, corpus.size(), MIN_CORPUS_SIZE);
            return new Result(Outcome.SKIPPED_EMPTY_CORPUS,
                    "corpus=" + corpus.size(), null);
        }

        // 3. Choose strategy from detected resources (#429)
        var capacity = NodeCapacity.detect(
            envDouble(ACTIVE_MODELS_GB_ENV, 13.5),
            envDouble(TRAINING_VRAM_ESTIMATE_GB_ENV, 12.0));
        var policy = UserTrainingPolicy.fromEnv();
        var activeContainers = activeInferenceContainers();
        var strategy = TrainingStrategySelector.choose(
            capacity,
            // Peer/cloud paths land in phases 3/4 — for now selector falls
            // through to local strategies. Plumbing the args makes the
            // future wiring a one-line change here, not a refactor.
            List.<PeerCapacity>of(),
            policy,
            activeContainers,
            cloudAvailable());
        log.info("DeepSleepTrainer: strategy={} for '{}' "
                + "(policy={}, freeVram={}GB, totalVram={}GB, gpus={})",
                strategy.label(), agentName, policy,
                String.format("%.1f", capacity.freeGpuVramGb()),
                String.format("%.1f", capacity.totalGpuVramGb()),
                capacity.gpuNames());

        // 4. Run the chosen executor — pause/align/resume choreography lives there.
        // Pull peer transport from the process-wide singleton (set by Main.java
        // when NATS is available); null is safe — PeerDelegatedExecutor skips
        // cleanly without a transport.
        var peerTransport = PeerTrainingTransport.Holder.get();
        var localNodeId = System.getenv().getOrDefault("WYRDSEKAI_NODE_NAME", "unknown");
        var executor = TrainingExecutor.Factory.forStrategy(
            strategy,
            new TrainingExecutor.Context(
                inferenceController, adapterRoot, peerTransport, localNodeId));
        var result = executor.execute(agentId, agentName, modelPath, adapterRoot, corpus);

        // Consolidated outcome log — keeps probes/soak harnesses on a single
        // grep target ("DeepSleepTrainer:") regardless of which executor ran.
        if (result.success()) {
            log.info("DeepSleepTrainer: complete for '{}' via {} — adapter {}",
                    agentName, strategy.label(), result.adapterPath());
        } else {
            log.info("DeepSleepTrainer: cycle ended for '{}' — outcome={} via {} ({})",
                    agentName, result.outcome(), strategy.label(), result.detail());
        }

        // 5. Voice-forge hook on success — self-evolving voice profile pass.
        // Best-effort: a forge failure does NOT demote a successful adapter
        // alignment to FAILED. Only fires when the executor actually trained
        // (COMPLETED) — Skip/Failed paths leave the profile alone.
        if (result.success() && forgeHook != null
                && "1".equals(System.getenv(FORGE_FLAG_ENV))) {
            try {
                var samples = extractAssistantSamples(corpus);
                forgeHook.runPass(samples);
            } catch (Exception e) {
                log.warn("DeepSleepTrainer: voice-forge hook failed for '{}': {}",
                        agentName, e.getMessage());
            }
        }

        return result;
    }

    /** Parse a {@code double} env var, falling back to {@code dflt} on missing/invalid. */
    private static double envDouble(String name, double dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    /**
     * Containers currently holding inference VRAM. Read from
     * {@code WYRDSEKAI_VOICE_BACKEND_CONTAINER} (the only one we manage today)
     * — informational hint to the selector. Future cross-node resource model
     * will replace this with ResourceRegistry's authoritative list.
     */
    private static List<String> activeInferenceContainers() {
        var voice = System.getenv(WyrdCliInferenceController.VOICE_CONTAINER_ENV);
        return (voice == null || voice.isBlank()) ? List.of() : List.of(voice);
    }

    /** True when at least one cloud LLM API key is configured. */
    private static boolean cloudAvailable() {
        return System.getenv("ANTHROPIC_API_KEY") != null
            || System.getenv("OPENAI_API_KEY") != null;
    }

    /**
     * Shape significance entries into {system, user, assistant} turns. The
     * format is what {@link VoiceAligner} expects — one turn per significance
     * entry. The "assistant" content is the entry text (the agent's own
     * voice); the "user" side is an inferred prompt. This is necessarily
     * lossy; a real production pipeline would pull raw conversation turns
     * from a dedicated persistence layer (future: ConversationHistoryStore).
     */
    List<Map<String, String>> buildCorpus(SignificanceBuffer buffer,
                                           String agentName) {
        if (buffer == null) return List.of();
        var entries = buffer.peek();
        if (entries == null || entries.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, String>>();
        var system = "You are " + agentName
                + ". Speak in your own voice, grounded in what you have "
                + "chosen to remember.";
        for (var e : entries) {
            if (e.superseded()) continue;  // forget entries aren't voice
            var content = e.content();
            if (content == null || content.isBlank()) continue;
            out.add(Map.of(
                "system", system,
                "user", "What comes to mind?",
                "assistant", content
            ));
        }
        return out;
    }

    /**
     * Extract just the assistant-side strings from an alignment corpus, most
     * recent last. Used by the voice-forge hook: the meta-LLM only needs a
     * handful of recent turns to reflect on. Capped at a small count to keep
     * the reflective prompt small.
     */
    static List<String> extractAssistantSamples(
            List<Map<String, String>> corpus) {
        if (corpus == null || corpus.isEmpty()) return List.of();
        final int MAX_SAMPLES = 8;
        var all = new ArrayList<String>();
        for (var turn : corpus) {
            var a = turn.get("assistant");
            if (a != null && !a.isBlank()) all.add(a);
        }
        if (all.size() <= MAX_SAMPLES) return List.copyOf(all);
        // Keep the tail (most recent).
        return List.copyOf(all.subList(all.size() - MAX_SAMPLES, all.size()));
    }

    // ── Dry-run helpers for tests ────────────────────────────────────────

    /**
     * No-op controller used in tests to exercise the orchestrator without
     * touching the real inference backend.
     */
    public static final class NoOpInferenceController implements InferenceController {
        public volatile boolean pauseCalled = false;
        public volatile boolean resumeCalled = false;
        public volatile Path lastAdapter = null;

        @Override public boolean pause() { pauseCalled = true; return true; }
        @Override public boolean resume(Path adapter) {
            resumeCalled = true;
            lastAdapter = adapter;
            return true;
        }
    }

    /** Utility: write corpus to disk for offline inspection. */
    public static void writeCorpusJsonl(Path out, List<Map<String, String>> corpus)
            throws Exception {
        Files.createDirectories(out.getParent());
        var mapper = new ObjectMapper();
        try (var w = Files.newBufferedWriter(out)) {
            for (var turn : corpus) {
                w.write(mapper.writeValueAsString(turn));
                w.newLine();
            }
        }
    }
}
