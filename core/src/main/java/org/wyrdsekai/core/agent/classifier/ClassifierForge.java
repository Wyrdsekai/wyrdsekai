package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sleep-cycle consolidation pass for classifier heads.
 *
 * <p>. During Forge, the arm's accumulated events
 * are merged into a per-agent training corpus as pseudo-labels. A retrain
 * subprocess can then be spawned to produce an updated ONNX model —
 * validated against a held-out slice and only installed if the regression
 * stays under the lineage floor.
 *
 * <p>The Java side is responsible for:
 * <ul>
 *   <li>Rotating the event log,</li>
 *   <li>Filtering high-confidence events (pseudo-labels),</li>
 *   <li>Merging with the shipped bootstrap corpus,</li>
 *   <li>Writing {@code corpus-merged.jsonl} to the per-agent dir,</li>
 *   <li>Optionally invoking {@code scripts/classifier/train_classifier.py}
 *       as a subprocess when {@code WYRDSEKAI_CLASSIFIER_RETRAIN=1}.</li>
 *   <li>Recording lineage (timestamps, sample counts, val-accuracy floor).</li>
 * </ul>
 *
 * <p>Kept deliberately fire-and-forget: consolidation failures never block
 * sleep completion. A broken Forge for one head logs a warning and leaves
 * the existing model in place — the agent is still reasonable with the
 * bootstrap weights.
 */
public final class ClassifierForge {

    private static final Logger log = LoggerFactory.getLogger(ClassifierForge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** High-confidence floor — below this, pseudo-labels aren't trusted. */
    public static final double PSEUDO_LABEL_FLOOR = 0.85;
    /** Maximum corpus size — older pseudo-labels dropped past this cap. */
    public static final int MAX_CORPUS_RECORDS = 20000;
    /** Env gate for actually spawning the retrain subprocess. */
    public static final String RETRAIN_ENV = "WYRDSEKAI_CLASSIFIER_RETRAIN";
    /**
     * Regression floor — if new val accuracy drops more than this below the
     * prior baseline, keep the old model. 5% matches the spec's tolerance:
     * small consolidation drift is OK, cliff-falls are not.
     */
    public static final double REGRESSION_TOLERANCE = 0.05;

    /** Outcome of a Forge consolidation for one head. */
    public record ConsolidationResult(
        String head,
        int eventsConsumed,
        int pseudoLabelsAdded,
        int corpusSize,
        boolean retrainAttempted,
        boolean retrainSucceeded,
        double priorAccuracy,
        double newAccuracy,
        String note
    ) {
        /** Shorthand for tests that don't care about accuracy fields. */
        static ConsolidationResult of(String head, int events, int added,
                                       int corpus, boolean attempted,
                                       boolean succeeded, String note) {
            return new ConsolidationResult(head, events, added, corpus,
                attempted, succeeded, -1, -1, note);
        }
    }

    /**
     * Consolidate all configured heads for one agent. Rotates the event log
     * once up front so concurrent classify calls keep writing to a fresh file.
     * Returns one {@link ConsolidationResult} per head (including heads with
     * no new events — those return a no-op result for auditing).
     */
    public static List<ConsolidationResult> consolidate(ClassifierArm arm) {
        if (arm == null) return List.of();
        var eventLog = arm.eventLog();
        if (eventLog == null) return List.of();

        var rotated = eventLog.rotate();
        if (rotated == null) {
            log.debug("Classifier Forge: no events to consume");
            return List.of();
        }

        var allEvents = ClassifierEventLog.read(rotated);
        log.info("Classifier Forge: {} events rotated from {}",
            allEvents.size(), rotated);

        // Group events by head name (head.name() e.g. REQUEST_TYPE)
        var byHead = new HashMap<String, List<ClassifierEventLog.Event>>();
        for (var e : allEvents) {
            byHead.computeIfAbsent(e.head(), k -> new ArrayList<>()).add(e);
        }

        var results = new ArrayList<ConsolidationResult>();
        for (var head : ClassifierHead.values()) {
            var events = byHead.getOrDefault(head.name(), List.of());
            results.add(consolidateOne(arm.perAgentDir(), head, events));
        }

        // Best-effort cleanup of the rotated file — keep it briefly for
        // post-mortem debugging; cron or operator can sweep old files later.
        return results;
    }

    private static ConsolidationResult consolidateOne(
            Path perAgentDir, ClassifierHead head,
            List<ClassifierEventLog.Event> events) {
        try {
            Files.createDirectories(perAgentDir);
        } catch (IOException e) {
            return ConsolidationResult.of(head.name(), events.size(), 0, 0,
                false, false, "dir create failed: " + e.getMessage());
        }

        // Start from the shipped bootstrap corpus — the base training set.
        var corpus = loadBootstrapCorpus(head);

        // Merge prior pseudo-labels (from previous Forge cycles), if any.
        var perAgentCorpus = perAgentDir.resolve(head.resourceName() + "-corpus.jsonl");
        if (Files.exists(perAgentCorpus)) {
            corpus = mergeCorpus(corpus, loadCorpus(perAgentCorpus));
        }

        // Add new pseudo-labels, deduped by text. Outcome-aware filter
        // POSITIVE events reinforce at any
        // confidence ≥ PSEUDO_LABEL_FLOOR (we know the routing decision
        // worked); NEGATIVE events are excluded from the corpus entirely
        // (we know the decision failed — reinforcing would make the
        // classifier worse); UNKNOWN events fall back to confidence-only
        // rules (the original circular behavior, but now a minority case).
        var added = 0;
        var known = new HashSet<String>();
        for (var rec : corpus) {
            known.add(normalize(rec.text()));
        }
        for (var e : events) {
            if (e.label() == null || e.label().isBlank()) continue;
            if (e.outcome() == ClassifierEventLog.Outcome.NEGATIVE) continue;
            // Confidence floor: POSITIVE events enter at PSEUDO_LABEL_FLOOR;
            // UNKNOWN events enter at a stricter bar (still PSEUDO_LABEL_FLOOR
            // today — upgrading once we collect enough outcome data).
            if (e.confidence() < PSEUDO_LABEL_FLOOR) continue;
            var key = normalize(e.text());
            if (key.isEmpty() || known.contains(key)) continue;
            var sourceTag = e.outcome() == ClassifierEventLog.Outcome.POSITIVE
                ? "pseudo-L1-confirmed" : "pseudo-L1";
            corpus.add(new CorpusRecord(e.label(), e.text(), sourceTag));
            known.add(key);
            added++;
        }

        // Cap corpus to MAX_CORPUS_RECORDS — drop oldest pseudo-labels first.
        if (corpus.size() > MAX_CORPUS_RECORDS) {
            corpus = truncateKeepingSeeds(corpus, MAX_CORPUS_RECORDS);
        }

        // Write the merged corpus for the retrain step (and for audit).
        try {
            writeCorpus(perAgentCorpus, corpus);
        } catch (IOException e) {
            return ConsolidationResult.of(head.name(), events.size(), added,
                corpus.size(), false, false,
                "corpus write failed: " + e.getMessage());
        }

        // Decide whether to retrain. Gated by env var so unit tests don't
        // spawn Python; operators flip the flag for real consolidation.
        var retrainEnv = System.getenv(RETRAIN_ENV);
        if (retrainEnv == null || !retrainEnv.equals("1")) {
            writeLineage(perAgentDir, head, corpus.size(), added,
                false, false, -1, -1, "retrain gated off");
            return ConsolidationResult.of(head.name(), events.size(), added,
                corpus.size(), false, false,
                "corpus merged; retrain env gate off");
        }

        // Attempt the retrain subprocess, with regression guard.
        var outcome = invokeRetrain(perAgentDir, head, perAgentCorpus);
        writeLineage(perAgentDir, head, corpus.size(), added, true,
            outcome.succeeded(), outcome.priorAccuracy(), outcome.newAccuracy(),
            outcome.reason());
        return new ConsolidationResult(head.name(), events.size(), added,
            corpus.size(), true, outcome.succeeded(),
            outcome.priorAccuracy(), outcome.newAccuracy(),
            outcome.reason());
    }

    // ── Corpus IO ────────────────────────────────────────────────────────

    public record CorpusRecord(String label, String text, String source) {}

    private static List<CorpusRecord> loadBootstrapCorpus(ClassifierHead head) {
        // Prefer expanded.jsonl over seeds.jsonl — expanded includes seeds.
        var resources = new String[] {
            "classifier/bootstrap/" + head.resourceName() + "/expanded.jsonl",
            "classifier/bootstrap/" + head.resourceName() + "/seeds.jsonl"
        };
        for (var path : resources) {
            try (var in = ClassifierForge.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) continue;
                var out = new ArrayList<CorpusRecord>();
                try (var reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    reader.lines().forEach(line -> {
                        var r = parseCorpusLine(line);
                        if (r != null) out.add(r);
                    });
                }
                return out;
            } catch (IOException e) {
                log.debug("Bootstrap corpus read ({}): {}", path, e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    private static List<CorpusRecord> loadCorpus(Path path) {
        if (!Files.exists(path)) return new ArrayList<>();
        var out = new ArrayList<CorpusRecord>();
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                var r = parseCorpusLine(line);
                if (r != null) out.add(r);
            });
        } catch (IOException e) {
            log.warn("Per-agent corpus read failed: {}", e.getMessage());
        }
        return out;
    }

    private static CorpusRecord parseCorpusLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            var node = MAPPER.readTree(line);
            var label = node.path("label").asText("");
            var text = node.path("text").asText("");
            var source = node.path("source").asText("seed");
            if (label.isEmpty() || text.isEmpty()) return null;
            return new CorpusRecord(label, text, source);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void writeCorpus(Path path, List<CorpusRecord> records)
            throws IOException {
        Files.createDirectories(path.getParent());
        var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (var rec : records) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("label", rec.label());
                payload.put("text", rec.text());
                payload.put("source", rec.source());
                writer.write(MAPPER.writeValueAsString(payload));
                writer.write("\n");
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    private static List<CorpusRecord> mergeCorpus(
            List<CorpusRecord> a, List<CorpusRecord> b) {
        var seen = new HashSet<String>();
        var out = new ArrayList<CorpusRecord>();
        for (var r : a) {
            var key = normalize(r.text());
            if (key.isEmpty() || seen.contains(key)) continue;
            seen.add(key);
            out.add(r);
        }
        for (var r : b) {
            var key = normalize(r.text());
            if (key.isEmpty() || seen.contains(key)) continue;
            seen.add(key);
            out.add(r);
        }
        return out;
    }

    private static List<CorpusRecord> truncateKeepingSeeds(
            List<CorpusRecord> records, int cap) {
        // Keep every seed/expanded-claude record, drop oldest pseudo records
        // from the end until we're at the cap.
        var out = new ArrayList<CorpusRecord>();
        var pseudos = new ArrayList<CorpusRecord>();
        for (var r : records) {
            if (r.source().startsWith("pseudo")) pseudos.add(r);
            else out.add(r);
        }
        int keepPseudos = Math.max(0, cap - out.size());
        // Keep the most recent pseudos (events flow in timestamp order, so
        // "most recent" = tail of the list).
        int start = Math.max(0, pseudos.size() - keepPseudos);
        for (int i = start; i < pseudos.size(); i++) out.add(pseudos.get(i));
        return out;
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    // ── Retrain + lineage ───────────────────────────────────────────────

    /** Outcome of an attempted retrain — includes accuracies for lineage. */
    record RetrainOutcome(
        boolean succeeded,
        double priorAccuracy,
        double newAccuracy,
        String reason
    ) {}

    private static RetrainOutcome invokeRetrain(Path perAgentDir, ClassifierHead head,
                                                 Path corpusPath) {
        var scriptDir = resolveScriptDir();
        if (scriptDir == null) {
            log.warn("Classifier Forge retrain: scripts/classifier not found in "
                + "WYRDSEKAI_SCRIPTS, installDir, or project root");
            return new RetrainOutcome(false, -1, -1,
                "scripts/classifier not found");
        }
        var trainScript = scriptDir.resolve("train_classifier.py");
        if (!Files.exists(trainScript)) {
            log.warn("Classifier Forge retrain: {} missing", trainScript);
            return new RetrainOutcome(false, -1, -1, "train script missing");
        }

        var modelOut = perAgentDir.resolve(head.resourceName() + ".onnx");
        var labelsOut = perAgentDir.resolve(head.resourceName() + ".labels.json");
        var accuracyOut = perAgentDir.resolve(head.resourceName() + ".val-accuracy.json");

        // Back up existing per-agent model + labels + accuracy (if present)
        // so we can roll back on regression. First-time retrain has no
        // backup — that's fine; we'll fall through to the shipped accuracy.
        var priorAccuracy = readPriorAccuracy(perAgentDir, head);
        backup(modelOut);
        backup(labelsOut);
        backup(accuracyOut);

        try {
            var pb = new ProcessBuilder(
                "python3", trainScript.toString(),
                "--corpus", corpusPath.toString(),
                "--output", modelOut.toString(),
                "--labels-output", labelsOut.toString());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var finished = proc.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                restoreBackup(modelOut);
                restoreBackup(labelsOut);
                restoreBackup(accuracyOut);
                return new RetrainOutcome(false, priorAccuracy, -1,
                    "retrain timed out");
            }
            if (proc.exitValue() != 0) {
                restoreBackup(modelOut);
                restoreBackup(labelsOut);
                restoreBackup(accuracyOut);
                return new RetrainOutcome(false, priorAccuracy, -1,
                    "retrain exit code " + proc.exitValue());
            }
            if (!Files.exists(modelOut) || !Files.exists(labelsOut)) {
                restoreBackup(modelOut);
                restoreBackup(labelsOut);
                restoreBackup(accuracyOut);
                return new RetrainOutcome(false, priorAccuracy, -1,
                    "retrain produced no model artifacts");
            }

            // Regression guard — compare val accuracies.
            var newAccuracy = readAccuracyFromFile(accuracyOut);
            if (priorAccuracy >= 0 && newAccuracy >= 0
                    && newAccuracy < priorAccuracy - REGRESSION_TOLERANCE) {
                log.warn("Classifier Forge: regression detected for {} "
                        + "(prior={}, new={}, tolerance={}); rolling back",
                    head.name(), priorAccuracy, newAccuracy, REGRESSION_TOLERANCE);
                restoreBackup(modelOut);
                restoreBackup(labelsOut);
                restoreBackup(accuracyOut);
                return new RetrainOutcome(false, priorAccuracy, newAccuracy,
                    "regression beyond tolerance; rolled back");
            }

            // Accepted — clean up backups.
            deleteBackup(modelOut);
            deleteBackup(labelsOut);
            deleteBackup(accuracyOut);
            return new RetrainOutcome(true, priorAccuracy, newAccuracy,
                priorAccuracy < 0 ? "first retrain" : "accepted");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Classifier Forge retrain failed for head {}: {}",
                head.name(), e.getMessage());
            restoreBackup(modelOut);
            restoreBackup(labelsOut);
            restoreBackup(accuracyOut);
            return new RetrainOutcome(false, priorAccuracy, -1,
                "exception: " + e.getMessage());
        }
    }

    /**
     * Read the prior val accuracy for this head — per-agent override first,
     * shipped baseline as fallback. Returns {@code -1} if nothing is found,
     * in which case the regression guard defers to the new value.
     */
    private static double readPriorAccuracy(Path perAgentDir, ClassifierHead head) {
        var perAgent = perAgentDir.resolve(head.resourceName() + ".val-accuracy.json");
        var fromPerAgent = readAccuracyFromFile(perAgent);
        if (fromPerAgent >= 0) return fromPerAgent;
        // Fall back to shipped baseline (packaged resource alongside the ONNX).
        try (var in = ClassifierForge.class.getClassLoader().getResourceAsStream(
                "classifier/pretrained/" + head.resourceName() + ".val-accuracy.json")) {
            if (in == null) return -1;
            var node = MAPPER.readTree(in);
            return node.path("accuracy").asDouble(-1);
        } catch (IOException e) {
            return -1;
        }
    }

    private static double readAccuracyFromFile(Path path) {
        if (path == null || !Files.exists(path)) return -1;
        try {
            var node = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            return node.path("accuracy").asDouble(-1);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void backup(Path p) {
        if (p == null || !Files.exists(p)) return;
        var bak = p.resolveSibling(p.getFileName() + ".backup");
        try { Files.move(p, bak, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException ignored) {}
    }

    /**
     * Restore the backup (rolling back a bad retrain) OR — if no backup
     * existed because this was a first-time retrain — delete the bad new
     * file so the runtime falls back to the shipped resources instead of
     * picking up the regressed per-agent override.
     */
    private static void restoreBackup(Path p) {
        if (p == null) return;
        var bak = p.resolveSibling(p.getFileName() + ".backup");
        try {
            if (Files.exists(bak)) {
                Files.move(bak, p, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {}
    }

    private static void deleteBackup(Path p) {
        if (p == null) return;
        var bak = p.resolveSibling(p.getFileName() + ".backup");
        try { Files.deleteIfExists(bak); } catch (IOException ignored) {}
    }

    private static Path resolveScriptDir() {
        // Search order: WyrdConfig (env/profile), wyrdsekai.scripts sysprop,
        // cwd/scripts, cwd/../scripts (gradle test working dir is module),
        // installed scripts/, user-home scripts/.
        var envDir = WyrdConfig.get().scriptsDir();
        if (envDir != null && !envDir.isBlank()) {
            var p = Path.of(envDir, "classifier");
            if (Files.isDirectory(p)) return p;
        }
        var sysDir = System.getProperty("wyrdsekai.scripts");
        if (sysDir != null && !sysDir.isBlank()) {
            var p = Path.of(sysDir, "classifier");
            if (Files.isDirectory(p)) return p;
        }
        var candidates = new Path[] {
            Path.of("scripts", "classifier"),
            Path.of("..", "scripts", "classifier"),
            Path.of("/opt/wyrdsekai/scripts/classifier"),
            Path.of(System.getProperty("user.home"), ".wyrdsekai", "scripts", "classifier"),
        };
        for (var c : candidates) if (Files.isDirectory(c)) return c;
        return null;
    }

    private static void writeLineage(Path perAgentDir, ClassifierHead head,
                                      int corpusSize, int pseudoLabelsAdded,
                                      boolean retrainAttempted, boolean retrainSucceeded,
                                      double priorAccuracy, double newAccuracy,
                                      String note) {
        var lineagePath = perAgentDir.resolve(head.resourceName() + ".lineage.jsonl");
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("head", head.name());
        entry.put("corpus_size", corpusSize);
        entry.put("pseudo_labels_added", pseudoLabelsAdded);
        entry.put("retrain_attempted", retrainAttempted);
        entry.put("retrain_succeeded", retrainSucceeded);
        if (priorAccuracy >= 0) entry.put("prior_accuracy", priorAccuracy);
        if (newAccuracy >= 0) entry.put("new_accuracy", newAccuracy);
        entry.put("note", note);
        try {
            Files.createDirectories(perAgentDir);
            Files.writeString(lineagePath,
                MAPPER.writeValueAsString(entry) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Lineage write failed: {}", e.getMessage());
        }
    }
}
