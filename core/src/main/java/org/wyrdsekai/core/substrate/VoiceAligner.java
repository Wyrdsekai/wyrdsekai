package org.wyrdsekai.core.substrate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Era-scale LLM voice alignment via SSD (Simple Self-Distillation) fine-tuning.
 *
 * <p>Shells out to MLX (Apple Silicon) or Unsloth (CUDA) for LoRA fine-tuning
 * of the agent's language model. This aligns the LLM's voice to the
 * CfC-driven personality. Runs during extended sleep or scheduled
 * maintenance — not every sleep cycle.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Export soul-consistent conversation corpus as JSONL
 *   <li>Shell to MLX LoRA fine-tune (or Unsloth)
 *   <li>(MLX only) Bridge mlx adapter format → PEFT via mlx_adapter_to_peft.py
 *   <li>Convert PEFT adapter → GGUF via llama.cpp's convert_lora_to_gguf.py
 *   <li>llama-server picks up the GGUF lora when the agent next wakes
 * </ol>
 *
 * <p>Triggered at epoch boundaries (every N sleep cycles) or major life events
 * (bond formation, tier promotion, etc.).
 *
 * @see CfCTrainer for the CfC consolidation that happens every sleep
 */
public class VoiceAligner {

    private static final Logger log = LoggerFactory.getLogger(VoiceAligner.class);

    /** Minimum conversations needed before voice alignment is worthwhile. */
    private static final int MIN_CORPUS_SIZE = 50;

    /** Default LoRA training iterations. */
    private static final int DEFAULT_ITERS = 2500;

    /** Timeout for the fine-tuning process (minutes). */
    private static final int FINETUNE_TIMEOUT_MINUTES = 60;

    private final Path workDir;
    private final String backend; // "mlx" or "unsloth"

    public VoiceAligner(Path workDir) {
        this(workDir, detectBackend());
    }

    public VoiceAligner(Path workDir, String backend) {
        this.workDir = workDir;
        this.backend = backend;
    }

    /**
     * Run voice alignment for an agent. Default iterations.
     */
    public Path align(String agentId, String agentName, String modelPath,
                      List<Map<String, String>> corpus) {
        return align(agentId, agentName, modelPath, corpus, null);
    }

    /**
     * Run voice alignment for an agent.
     *
     * @param agentId    agent DID or entity ID
     * @param agentName  agent display name
     * @param modelPath  path to base model (e.g., "qwen3.5-4b")
     * @param corpus     conversation corpus as list of {system, user, assistant} turns
     * @param maxIters   training iteration cap; null/non-positive falls back to {@link #DEFAULT_ITERS}
     * @return path to LoRA adapter, or null if alignment failed/skipped
     */
    public Path align(String agentId, String agentName, String modelPath,
                      List<Map<String, String>> corpus, Integer maxIters) {
        if (corpus.size() < MIN_CORPUS_SIZE) {
            log.info("Voice alignment skipped for '{}' — only {} conversations (need {})",
                agentName, corpus.size(), MIN_CORPUS_SIZE);
            return null;
        }

        try {
            Files.createDirectories(workDir);

            // 1. Export corpus as JSONL
            var corpusPath = exportCorpus(agentId, corpus);

            // 2. Shell to fine-tuning backend
            var adapterPath = workDir.resolve(safeName(agentId) + "_adapter");
            Files.createDirectories(adapterPath);

            int iters = (maxIters != null && maxIters > 0) ? maxIters : DEFAULT_ITERS;
            boolean success = switch (backend) {
                case "mlx" -> runMlxFineTune(modelPath, corpusPath, adapterPath, iters);
                case "unsloth" -> runUnslothFineTune(modelPath, corpusPath, adapterPath, iters);
                default -> {
                    log.warn("Unknown voice alignment backend: {}", backend);
                    yield false;
                }
            };

            if (!success) {
                log.warn("Voice alignment failed for '{}'", agentName);
                return null;
            }

            // Convert HF LoRA adapter → GGUF so llama-server can load it via
            // --lora. Without this step the adapter is inert: training
            // succeeded but no inference path consumes it. llama.cpp's
            // convert_lora_to_gguf.py is the canonical converter. Fails
            // soft — we still return the HF adapter path so callers can
            // inspect, but log clearly that GGUF conversion didn't happen.
            var ggufPath = convertAdapterToGguf(adapterPath, modelPath);
            if (ggufPath != null) {
                log.info("Voice alignment complete for '{}' — HF adapter at {}, "
                        + "GGUF at {}", agentName, adapterPath, ggufPath);
            } else {
                log.warn("Voice alignment produced HF adapter at {} but GGUF "
                        + "conversion failed — llama-server cannot load it "
                        + "until conversion succeeds", adapterPath);
            }
            return adapterPath;

        } catch (Exception e) {
            log.error("Voice alignment error for '{}': {}", agentName, e.getMessage());
            return null;
        }
    }

    /**
     * Check if voice alignment should trigger based on sleep count and agent state.
     *
     * @param consecutiveSleeps total sleep cycles since last alignment
     * @param majorLifeEvent    whether a major event occurred (bond, tier change, etc.)
     * @param alignmentInterval sleep cycles between alignments (default: 100)
     * @return true if alignment should run
     */
    public static boolean shouldAlign(int consecutiveSleeps, boolean majorLifeEvent, int alignmentInterval) {
        if (majorLifeEvent) return true;
        return consecutiveSleeps > 0 && consecutiveSleeps % alignmentInterval == 0;
    }

    // ── Corpus Export ────────────────────────────────────────────────────

    private Path exportCorpus(String agentId, List<Map<String, String>> corpus) throws Exception {
        var path = workDir.resolve(safeName(agentId) + "_corpus.jsonl");
        var mapper = new ObjectMapper();

        try (var writer = Files.newBufferedWriter(path)) {
            for (var turn : corpus) {
                writer.write(mapper.writeValueAsString(turn));
                writer.newLine();
            }
        }

        log.info("Exported {} conversation turns to {}", corpus.size(), path);
        return path;
    }

    // ── Backend Implementations ──────────────────────────────────────────

    private boolean runMlxFineTune(String modelPath, Path corpusPath, Path adapterPath, int iters) {
        // mlx-lm's `--data` expects a directory containing train.jsonl
        // (and optionally valid.jsonl/test.jsonl), not a single file.
        // We split the exported corpus 90/10 into the working data dir.
        Path dataDir;
        try {
            dataDir = prepareMlxDataDir(corpusPath);
        } catch (Exception e) {
            log.warn("mlx fine-tune: failed to prepare data dir from {}: {}",
                corpusPath, e.getMessage());
            return false;
        }
        var python = resolvePython();
        var ok = runCommand(
            python, "-m", "mlx_lm.lora",
            "--model", modelPath,
            "--data", dataDir.toString(),
            "--train",
            "--iters", String.valueOf(iters),
            "--adapter-path", adapterPath.toString()
        );
        if (!ok) return false;

        // mlx-lm writes adapters.safetensors + an mlx-style adapter_config.json.
        // llama.cpp's convert_lora_to_gguf.py expects PEFT format:
        //   adapter_model.safetensors with `base_model.model.*.lora_A/B.weight`
        //   keys + a peft-style adapter_config.json.
        // Bridge it before convertAdapterToGguf runs. Without this step the
        // GGUF conversion fails silently and llama-server can't load the adapter.
        return bridgeMlxAdapterToPeft(adapterPath, python);
    }

    /**
     * Run scripts/training/mlx_adapter_to_peft.py against the just-trained
     * adapter directory. Search order matches GGUF converter resolution:
     * {@code WYRDSEKAI_REPO_DIR} env, then walking up from the JVM cwd, then
     * a couple of well-known checkout locations. Fails closed (returns false)
     * if the script isn't found — better than producing a half-converted
     * artifact and pretending the train succeeded.
     */
    boolean bridgeMlxAdapterToPeft(Path adapterPath, String python) {
        var script = findRepoScript("scripts/training/mlx_adapter_to_peft.py");
        if (script == null) {
            log.warn("mlx_adapter_to_peft.py not found — set WYRDSEKAI_REPO_DIR. "
                    + "MLX adapter at {} won't convert to GGUF.", adapterPath);
            return false;
        }
        return runCommand(python, script.toString(), adapterPath.toString());
    }

    /** Locate a repo-relative helper script. Returns null if not found. */
    static Path findRepoScript(String relPath) {
        var candidates = new ArrayList<Path>();
        var env = WyrdConfig.get().repoDir();
        if (env != null && !env.isBlank()) candidates.add(Path.of(env, relPath));
        // Walk up from cwd looking for a wyrdsekai checkout.
        var cwd = Path.of(System.getProperty("user.dir", "."));
        for (int i = 0; i < 5 && cwd != null; i++) {
            candidates.add(cwd.resolve(relPath));
            cwd = cwd.getParent();
        }
        candidates.add(Path.of(System.getProperty("user.home"),
            "src", "wyrdsekai", relPath));
        for (var c : candidates) {
            if (Files.exists(c)) return c;
        }
        return null;
    }

    /**
     * Materialize an mlx-lm `--data` directory from a flat JSONL corpus.
     * Splits 90/10 train/valid (mlx-lm requires both when validating).
     *
     * <p>Also converts the corpus format. Wyrdsekai writes corpus turns as
     * flat {@code {system, user, assistant}} JSON (the shape unsloth's
     * inline script consumes). mlx-lm rejects that with
     * {@code "Unsupported data format"} — it wants
     * {@code {"messages": [{"role":"system","content":...}, ...]}}.
     * We translate per-line during the split so the source corpus on disk
     * stays untouched.</p>
     *
     * <p>The data dir lives next to the adapter under
     * {@code <workDir>/<safeName>_data/}.</p>
     */
    static Path prepareMlxDataDir(Path corpusPath) throws Exception {
        var dir = corpusPath.resolveSibling(
            corpusPath.getFileName().toString().replace("_corpus.jsonl", "_data"));
        Files.createDirectories(dir);

        var lines = Files.readAllLines(corpusPath);
        if (lines.isEmpty()) {
            // mlx-lm refuses an empty train.jsonl. Surface as failure
            // rather than write zero-byte files that confuse later runs.
            throw new IllegalStateException("corpus is empty: " + corpusPath);
        }

        // Convert each turn to chat-messages format. Lines that already
        // conform pass through; lines we can't parse stay verbatim and let
        // mlx-lm reject them — better than silent drop.
        var converted = new ArrayList<String>(lines.size());
        for (var line : lines) {
            converted.add(toChatMessagesLine(line));
        }

        // Tiny corpora collapse to 1-line valid set so split never empties.
        int validCount = Math.max(1, converted.size() / 10);
        if (validCount >= converted.size()) validCount = 1;
        int trainCount = converted.size() - validCount;

        var trainLines = converted.subList(0, trainCount);
        var validLines = converted.subList(trainCount, converted.size());

        Files.write(dir.resolve("train.jsonl"), trainLines);
        Files.write(dir.resolve("valid.jsonl"), validLines);
        return dir;
    }

    /**
     * Translate one corpus line from wyrdsekai's flat
     * {@code {system, user, assistant}} shape to mlx-lm's chat shape
     * {@code {messages: [{role, content}, ...]}}. If the input is already
     * chat-shaped (has {@code messages}, {@code text}, or
     * {@code prompt}/{@code completion}), it passes through unchanged.
     */
    static String toChatMessagesLine(String line) {
        if (line == null || line.isBlank()) return line;
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(line);
            // Already chat-shaped — leave alone.
            if (node.has("messages") || node.has("text")
                    || (node.has("prompt") && node.has("completion"))) {
                return line;
            }
            var messages = mapper.createArrayNode();
            String[] roles = {"system", "user", "assistant"};
            for (var role : roles) {
                if (node.has(role)) {
                    var content = node.get(role).asText("");
                    if (!content.isBlank()) {
                        var msg = mapper.createObjectNode();
                        msg.put("role", role);
                        msg.put("content", content);
                        messages.add(msg);
                    }
                }
            }
            if (messages.size() == 0) return line; // nothing recognisable; pass through
            var out = mapper.createObjectNode();
            out.set("messages", messages);
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            // Malformed JSON — let mlx-lm complain with the original line.
            return line;
        }
    }

    private boolean runUnslothFineTune(String modelPath, Path corpusPath, Path adapterPath, int iters) {
        var script = workDir.resolve("unsloth_train.py");
        try {
            // Real QLoRA training script. Loads 4-bit quantized base via
            // bitsandbytes, attaches LoRA adapters on attention+MLP projs,
            // formats JSONL into Qwen3 chat template, trains N iters, saves.
            // Hyperparams tuned for 9B on 16GB — r=8, bsz=1, grad_accum=4,
            // 3e-4 LR, cosine schedule. Fails cleanly if any import missing.
            var src = """
                import json, os, sys
                from pathlib import Path

                try:
                    from unsloth import FastLanguageModel
                    from unsloth.chat_templates import get_chat_template
                    from trl import SFTTrainer
                    from transformers import TrainingArguments
                    from datasets import Dataset
                except ImportError as e:
                    print(f"[voice-align] missing dependency: {e}", file=sys.stderr)
                    print("[voice-align] install: pip install unsloth trl transformers datasets",
                          file=sys.stderr)
                    sys.exit(2)

                MODEL_PATH = os.environ["ALIGNER_MODEL_PATH"]
                CORPUS_PATH = os.environ["ALIGNER_CORPUS_PATH"]
                ADAPTER_PATH = os.environ["ALIGNER_ADAPTER_PATH"]
                MAX_ITERS = int(os.environ.get("ALIGNER_MAX_ITERS", "60"))
                MAX_SEQ = int(os.environ.get("ALIGNER_MAX_SEQ", "2048"))
                LORA_R = int(os.environ.get("ALIGNER_LORA_R", "8"))

                print(f"[voice-align] model={MODEL_PATH} iters={MAX_ITERS} r={LORA_R}",
                      file=sys.stderr)

                model, tokenizer = FastLanguageModel.from_pretrained(
                    model_name=MODEL_PATH,
                    max_seq_length=MAX_SEQ,
                    load_in_4bit=True,
                    dtype=None,
                )
                model = FastLanguageModel.get_peft_model(
                    model, r=LORA_R, lora_alpha=LORA_R * 2,
                    target_modules=["q_proj","k_proj","v_proj","o_proj",
                                     "gate_proj","up_proj","down_proj"],
                    lora_dropout=0.0, bias="none",
                    use_gradient_checkpointing="unsloth",
                )

                tokenizer = get_chat_template(tokenizer, chat_template="qwen-2.5")

                rows = []
                with open(CORPUS_PATH) as f:
                    for line in f:
                        line = line.strip()
                        if not line: continue
                        t = json.loads(line)
                        messages = [
                            {"role": "system", "content": t.get("system", "")},
                            {"role": "user",   "content": t.get("user", "")},
                            {"role": "assistant", "content": t.get("assistant", "")},
                        ]
                        text = tokenizer.apply_chat_template(
                            messages, tokenize=False, add_generation_prompt=False)
                        rows.append({"text": text})
                dataset = Dataset.from_list(rows)

                trainer = SFTTrainer(
                    model=model, tokenizer=tokenizer, train_dataset=dataset,
                    dataset_text_field="text", max_seq_length=MAX_SEQ,
                    args=TrainingArguments(
                        output_dir=str(Path(ADAPTER_PATH).parent / "train_out"),
                        per_device_train_batch_size=1,
                        gradient_accumulation_steps=4,
                        max_steps=MAX_ITERS,
                        learning_rate=3e-4,
                        logging_steps=1,
                        optim="adamw_8bit",
                        weight_decay=0.01,
                        lr_scheduler_type="cosine",
                        seed=42,
                        fp16=False, bf16=True,
                    ),
                )
                trainer.train()

                model.save_pretrained(ADAPTER_PATH)
                tokenizer.save_pretrained(ADAPTER_PATH)
                print(f"[voice-align] adapter saved to {ADAPTER_PATH}", file=sys.stderr)
                """;
            Files.writeString(script, src);
            return runCommandWithEnv(
                Map.of(
                    "ALIGNER_MODEL_PATH", modelPath,
                    "ALIGNER_CORPUS_PATH", corpusPath.toString(),
                    "ALIGNER_ADAPTER_PATH", adapterPath.toString(),
                    "ALIGNER_MAX_ITERS", String.valueOf(iters)
                ),
                resolvePython(), script.toString()
            );
        } catch (Exception e) {
            log.error("Failed to create Unsloth training script: {}", e.getMessage());
            return false;
        }
    }

    // ── GGUF conversion ──────────────────────────────────────────────────

    /**
     * Shell to llama.cpp's convert_lora_to_gguf.py to produce the GGUF
     * artifact llama-server needs. Returns path to the .gguf on success,
     * null on any failure (missing converter script, python error, output
     * missing). Does not raise — the HF adapter is still a valid artifact
     * even if conversion fails.
     *
     * <p>Search order for the converter script:
     * {@code WYRDSEKAI_LLAMA_CPP_DIR}, then the wyrdsekai data dir
     * ({@code $WYRDSEKAI_DATA_DIR/llama.cpp} or {@code ~/.wyrdsekai/llama.cpp})
     * — that's where the mac-node bootstrap drops the checkout —
     * then {@code ~/work_dev/llama.cpp}, then {@code /opt/llama.cpp}.</p>
     */
    Path convertAdapterToGguf(Path hfAdapterDir, String basePath) {
        var converter = findConverterScript();
        if (converter == null) {
            log.warn("convert_lora_to_gguf.py not found. Set "
                    + "WYRDSEKAI_LLAMA_CPP_DIR to your llama.cpp checkout.");
            return null;
        }
        var ggufOut = hfAdapterDir.resolve("adapter.gguf");
        // Use the venv python if configured (e.g. mac-node's ~/.wyrdsekai/mlx-venv/bin/python)
        // — that's where mlx-lm pulled in transformers/torch which the converter imports.
        // System python3 typically lacks transformers and the script fails at import time.
        var python = resolvePython();
        // The converter has TWO ways to specify the base model:
        //   --base <dir>         — local directory holding HF config files
        //   --base-model-id <id> — HF repo ID, downloaded/looked up at runtime
        // We probe the basePath as a directory first; if it doesn't exist
        // locally, treat it as a repo ID and use --base-model-id. Avoids the
        // "FileNotFoundError: 'Qwen/Qwen3-1.7B'" failure mode where the
        // converter does os.listdir() on the literal repo string.
        var args = new ArrayList<String>();
        args.add(python);
        args.add(converter.toString());
        args.add(hfAdapterDir.toString());
        args.add("--outfile"); args.add(ggufOut.toString());
        args.add("--outtype"); args.add("f16");
        if (basePath != null && !basePath.isBlank()) {
            if (Files.isDirectory(Path.of(basePath))) {
                args.add("--base"); args.add(basePath);
            } else {
                args.add("--base-model-id"); args.add(basePath);
            }
        }
        var ok = runCommand(args.toArray(String[]::new));
        if (!ok || !Files.exists(ggufOut)) {
            log.warn("GGUF conversion failed or output missing at {}", ggufOut);
            return null;
        }
        return ggufOut;
    }

    private static Path findConverterScript() {
        var candidates = new ArrayList<Path>();
        var cfg = WyrdConfig.get();
        var env = cfg.llamaCppDir();
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env, "convert_lora_to_gguf.py"));
        }
        // Wyrdsekai data dir — bootstrap script clones llama.cpp here on
        // macOS install. Honors data_dir override; falls back to
        // ~/.wyrdsekai. Try this BEFORE the dev/system paths since it's the
        // canonical location for an installed node.
        var dataDir = cfg.dataDir();
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = Path.of(System.getProperty("user.home"), ".wyrdsekai").toString();
        }
        candidates.add(Path.of(dataDir, "llama.cpp", "convert_lora_to_gguf.py"));

        candidates.add(Path.of(System.getProperty("user.home"),
                "work_dev", "llama.cpp", "convert_lora_to_gguf.py"));
        candidates.add(Path.of("/opt/llama.cpp/convert_lora_to_gguf.py"));
        for (var c : candidates) {
            if (Files.exists(c)) return c;
        }
        return null;
    }

    // ── Process Execution ────────────────────────────────────────────────

    private boolean runCommand(String... command) {
        return runCommandWithEnv(Map.of(), command);
    }

    private boolean runCommandWithEnv(Map<String, String> extraEnv, String... command) {
        try {
            log.info("Running voice alignment: {}", String.join(" ", command));
            var pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            if (!extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
            var process = pb.start();

            // Stream output to log
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[voice-align] {}", line);
                }
            }

            boolean finished = process.waitFor(FINETUNE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("Voice alignment timed out after {} minutes", FINETUNE_TIMEOUT_MINUTES);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Voice alignment failed with exit code {}", exitCode);
                return false;
            }
            return true;

        } catch (Exception e) {
            log.error("Voice alignment process error: {}", e.getMessage());
            return false;
        }
    }

    // ── Backend Detection ────────────────────────────────────────────────

    private static String detectBackend() {
        // Explicit override wins — voice.backend (env > profile.toml).
        // Needed on Linux boxes where `python3 -c "import mlx_lm"` might spuriously
        // succeed (stale wrappers, PATH weirdness) and pick an unusable backend.
        var override = WyrdConfig.get().voiceBackend();
        if (override != null && !override.isBlank()) {
            log.info("Voice alignment backend forced via voice.backend={}", override);
            return override.trim().toLowerCase(Locale.ROOT);
        }
        var python = resolvePython();
        // Use importlib.util.find_spec — fast metadata-only check that doesn't
        // execute the package. Critical for unsloth specifically: actual
        // `import unsloth` takes 6+ seconds (loads xformers/torch), exceeding
        // the commandExists 5s timeout and silently falling through to the
        // wrong backend. find_spec returns in <0.5s.
        if (commandExists(python, "-c",
                "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('mlx_lm') else 1)"))
            return "mlx";
        if (commandExists(python, "-c",
                "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('unsloth') else 1)"))
            return "unsloth";
        // Fallback — MLX is the macOS default and unsloth is the Linux default;
        // either way 'mlx' as the named fallback is harmless because the
        // backend dispatch will fail loudly if mlx_lm isn't actually installed.
        return "mlx";
    }

    /** Resolve the python interpreter to use for voice-alignment subprocesses.
     *  Order: env override → standard venv locations → system python3.
     *  Cached on first resolve. */
    private static volatile String _resolvedPython;
    static String resolvePython() {
        var cached = _resolvedPython;
        if (cached != null) return cached;
        var override = WyrdConfig.get().voiceBackendPython();
        if (override != null && !override.isBlank()) {
            _resolvedPython = override;
            return override;
        }
        var home = System.getProperty("user.home");
        // Auto-detect candidates in priority order. First one with a runnable
        // python executable wins. mlx-venv first (macOS bootstrap convention),
        // then voice-venv (Linux installer), then miniforge3 (dev).
        String[] candidates = {
            home + "/.wyrdsekai/mlx-venv/bin/python",
            home + "/.wyrdsekai/voice-venv/bin/python",
            home + "/.miniforge3/bin/python3",
            "python3"
        };
        for (var candidate : candidates) {
            if (commandExists(candidate, "--version")) {
                log.info("Voice-backend python auto-detected: {}", candidate);
                _resolvedPython = candidate;
                return candidate;
            }
        }
        _resolvedPython = "python3";
        return "python3";
    }

    private static boolean commandExists(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeName(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
