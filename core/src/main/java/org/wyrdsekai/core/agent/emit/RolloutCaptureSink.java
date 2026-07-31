package org.wyrdsekai.core.agent.emit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * the zero-skew rollout-bank capture seam.
 *
 * <p>The RFT (GRPO) loop needs a distribution of the EXACT own-time prompts +
 * tool menus the drive model serves on, paired with the drive signals that produced
 * them. Rather than reconstruct that prompt outside the actor (which drifts from the
 * live assembly), {@link org.wyrdsekai.core.agent.CompanionActor#triggerAutonomousInference}
 * — when a sink is set — hands the already-assembled {@code messages} + {@code tools}
 * straight here. The captured prompt IS the served prompt, so training and serving can
 * never diverge.</p>
 *
 * <p>Set only by the offline capture harness (driven by {@code ForceGenerativeImpetus}
 * over a fixture sweep of seeded {@code DriveState}/{@code VitalityState}/{@code gapKey}).
 * Null in production → zero overhead, zero behaviour change. {@link #captureOnly()}
 * lets the harness skip the real inference call after capture, so the bank can be built
 * with no live model attached.</p>
 */
public interface RolloutCaptureSink {

    /**
     * Record one own-time rollout prompt. {@code generativity} is the seeded drive
     * level for this turn; the reward fn maps it + the model's
     * generation to the act-vs-rest decision — so capture the signal, not a verdict.
     */
    void capture(List<ChatMessage> messages, List<ToolDefinition> tools,
                 Map<String, Double> driveLevels, double generativity, double equanimity,
                 String gapKey);

    /** When true, the actor skips the live inference call after capture (no model needed). */
    default boolean captureOnly() { return true; }

    /**
     * Appends one self-contained JSON object per captured rollout to a JSONL file:
     * {@code {generativity, equanimity, gap_key, drives, messages, tools}}. Each line
     * is an OpenAI-style chat-with-tools sample the GRPO rollout consumes directly.
     */
    final class JsonlFileSink implements RolloutCaptureSink {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path out;
        private final boolean captureOnly;
        private int count;

        public JsonlFileSink(Path out, boolean captureOnly) {
            this.out = out;
            this.captureOnly = captureOnly;
            try {
                if (out.getParent() != null) Files.createDirectories(out.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public synchronized void capture(List<ChatMessage> messages, List<ToolDefinition> tools,
                                         Map<String, Double> driveLevels, double generativity,
                                         double equanimity, String gapKey) {
            var row = new LinkedHashMap<String, Object>();
            row.put("generativity", generativity);
            row.put("equanimity", equanimity);
            row.put("gap_key", gapKey);
            row.put("drives", driveLevels);
            row.put("messages", messages);
            row.put("tools", tools);
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(JSON.writeValueAsString(row));
                w.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            count++;
        }

        @Override
        public boolean captureOnly() { return captureOnly; }

        public int count() { return count; }
    }
}
