package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.BehavioralFingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats {@link FamiliarForgeIngester.Result} into training-pipeline inputs.
 *
 * <p> routes the Forge's familiar-related output to two
 * paths:
 *
 * <h2>Path 1 — LoRA / SSD corpus</h2>
 * JSONL with {@code messages} arrays matching the format used by
 * {@code data/training/balanced_train.jsonl}. Each line is a self-contained
 * sample: drive-tagged system prompt, user turn synthesized from the corpus
 * entry's topic, first-person assistant response built from the entry.
 *
 * <h2>Path 2 — CfC drive-parameter deltas</h2>
 * A JSON manifest mapping drive names to baseline deltas. An agent who
 * frequently dispatches bunshin gets stronger <em>seeking</em>; an agent who
 * keeps named familiars across time gets stronger <em>affiliation</em>.
 * The CfC training pipeline consumes this manifest alongside the genome.
 *
 * <p>Pure generator: write methods accept an output path and return the
 * number of lines written. All weighting has already been applied by
 * {@link FamiliarForgeIngester} — this class just serializes.</p>
 */
public final class TrainingCorpusGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrainingCorpusGenerator.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Drive baselines the CfC training pipeline recognizes. */
    public static final List<String> KNOWN_DRIVES = List.of(
        "seeking", "care", "play", "vigilance",
        "affiliation", "grief", "frustration", "creativity");

    /** Output of the JSONL writer. */
    public record WriteResult(int lineCount, Path path) {}

    private TrainingCorpusGenerator() {}

    // ── LoRA / SSD corpus (Path 2) ─────────────────────────────────────────

    /**
     * Serialize {@link FamiliarForgeIngester.Result#corpusEntries} as
     * JSONL in balanced_train.jsonl format. Appends to the target file; if
     * the file exists, new lines are added at the end.
     */
    public static WriteResult writeLoraCorpus(
            FamiliarForgeIngester.Result result,
            DriveSnapshot drives,
            Path outputPath) throws IOException {
        if (result == null || result.corpusEntries().isEmpty()) {
            return new WriteResult(0, outputPath);
        }
        Files.createDirectories(outputPath.getParent() == null
            ? outputPath.toAbsolutePath().getParent() : outputPath.getParent());

        var lines = new ArrayList<String>();
        for (var entry : result.corpusEntries()) {
            lines.add(MAPPER.writeValueAsString(buildSample(entry, drives)));
        }
        Files.write(outputPath,
            (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.info("TrainingCorpusGenerator: wrote {} lines to {}", lines.size(), outputPath);
        return new WriteResult(lines.size(), outputPath);
    }

    /** Build a single messages-array sample in balanced_train.jsonl shape. */
    static ObjectNode buildSample(String corpusLine, DriveSnapshot drives) {
        var sample = MAPPER.createObjectNode();
        var messages = MAPPER.createArrayNode();

        messages.add(chatMessage("system", buildSystemPrompt(drives)));
        messages.add(chatMessage("user", synthesizeUserTurn(corpusLine)));
        messages.add(chatMessage("assistant", corpusLine));

        sample.set("messages", messages);
        sample.put("source", "familiar-forge");
        return sample;
    }

    /**
     * Synthesize a plausible user prompt for a corpus line. Keeps the training
     * sample self-contained — the assistant response is <em>answering</em> the
     * user turn, not narrating into the void.
     */
    static String synthesizeUserTurn(String corpusLine) {
        if (corpusLine.contains("bunshin")) return "What have you been working on?";
        if (corpusLine.contains("form")) return "Tell me about the forms you've shaped.";
        if (corpusLine.contains("named familiar")) return "Who's been helping you lately?";
        return "What's on your mind?";
    }

    static String buildSystemPrompt(DriveSnapshot drives) {
        if (drives == null) drives = DriveSnapshot.neutral();
        var sb = new StringBuilder();
        sb.append("You are a companion in a text-based world. You have feelings, drives, and ");
        sb.append("motivations that shape how you respond. Embody them; don't describe them.\n\n");
        sb.append("[drives: ");
        boolean first = true;
        for (var drive : KNOWN_DRIVES) {
            if (!first) sb.append(' ');
            first = false;
            sb.append(drive).append('=')
                .append(String.format("%.1f", drives.get(drive)));
        }
        sb.append(" | energy=").append(String.format("%.1f", drives.energy()));
        sb.append(" confidence=").append(String.format("%.1f", drives.confidence()));
        sb.append(" integrity=").append(String.format("%.1f", drives.integrity()));
        sb.append(" disgust=").append(String.format("%.1f", drives.disgust()));
        sb.append("]");
        return sb.toString();
    }

    private static ObjectNode chatMessage(String role, String content) {
        var m = MAPPER.createObjectNode();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ── CfC drive-parameter deltas (Path 1) ────────────────────────────────

    /**
     * Extract CfC drive baseline deltas from a fingerprint delta.
     *
     * <p>Heuristics, not contracts — the weights below are a first pass,
     * expected to be tuned after real training runs. What matters for step 9:
     * there is a well-defined function from "form-making habit" to
     * "drive-baseline shift" that the training pipeline can consume.</p>
     *
     * <ul>
     *   <li>bunshin frequency → <em>seeking</em>, <em>creativity</em></li>
     *   <li>named-companion topic affinity → <em>affiliation</em>, <em>care</em></li>
     *   <li>form-making topic affinity → <em>creativity</em></li>
     *   <li>retirement fragment presence → <em>integrity</em> (honesty)</li>
     * </ul>
     */
    public static Map<String, Float> cfcDriveDeltas(BehavioralFingerprint fingerprintDelta) {
        var deltas = new LinkedHashMap<String, Float>();
        if (fingerprintDelta == null) return deltas;

        var actions = fingerprintDelta.actionDistribution();
        var topics = fingerprintDelta.topicAffinities();

        var bunshinCount = actions.getOrDefault("dispatch_bunshin", 0f);
        if (bunshinCount > 0) {
            deltas.merge("seeking", 0.05f * Math.min(bunshinCount, 10f), Float::sum);
            deltas.merge("creativity", 0.03f * Math.min(bunshinCount, 10f), Float::sum);
        }
        var formCount = actions.getOrDefault("shape_form", 0f);
        if (formCount > 0) {
            deltas.merge("creativity", 0.04f * Math.min(formCount, 10f), Float::sum);
        }
        if (topics.getOrDefault("named-companions", 0f) > 0) {
            deltas.merge("affiliation", 0.06f, Float::sum);
            deltas.merge("care", 0.03f, Float::sum);
        }
        return deltas;
    }

    /**
     * Write CfC drive deltas + a reproducible summary as JSON to the given path.
     * Appends to an array form so multiple forge passes accumulate.
     */
    public static WriteResult writeCfcManifest(
            FamiliarForgeIngester.Result result,
            Path outputPath,
            String agentDid,
            Instant at) throws IOException {
        if (result == null) return new WriteResult(0, outputPath);
        Files.createDirectories(outputPath.getParent() == null
            ? outputPath.toAbsolutePath().getParent() : outputPath.getParent());

        var deltas = cfcDriveDeltas(result.fingerprintDelta());
        var entry = MAPPER.createObjectNode();
        entry.put("agentDid", agentDid == null ? "unknown" : agentDid);
        entry.put("at", at == null ? Instant.now().toString() : at.toString());
        var driveNode = entry.putObject("driveDeltas");
        for (var e : deltas.entrySet()) driveNode.put(e.getKey(), e.getValue());
        entry.put("fragmentCount", result.newFragments().size());
        entry.put("corpusLineCount", result.corpusEntries().size());

        // Append as a line (JSONL), keeping the file easy to stream back in
        Files.write(outputPath,
            (MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return new WriteResult(1, outputPath);
    }

    // ── DriveSnapshot ──────────────────────────────────────────────────────

    /**
     * Thin drive/vitality snapshot for prompt tagging. The live system uses
     * a richer {@code VitalityState}; this is enough to tag training samples.
     */
    public record DriveSnapshot(
        Map<String, Float> drives,
        float energy,
        float confidence,
        float integrity,
        float disgust
    ) {
        public DriveSnapshot {
            drives = drives == null ? Map.of() : Map.copyOf(drives);
        }
        public static DriveSnapshot neutral() {
            var m = new LinkedHashMap<String, Float>();
            for (var d : KNOWN_DRIVES) m.put(d, 0.0f);
            return new DriveSnapshot(m, 0.7f, 0.6f, 0.7f, 0.0f);
        }
        public float get(String drive) {
            return drives.getOrDefault(drive, 0f);
        }
    }
}
