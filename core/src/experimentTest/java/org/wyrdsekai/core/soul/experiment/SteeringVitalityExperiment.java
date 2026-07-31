package org.wyrdsekai.core.soul.experiment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment 8 Part F: Vitality modulation via steering vector strength.
 *
 * Since llama-server can't change vector scale at runtime, we use
 * pre-launched servers at different scales matching each vitality profile.
 *
 * Reuses ExtendedVitalityModulation.computeLoraAlpha() — the formula is
 * model-agnostic and works for both LoRA alpha and steering vector alpha.
 */
public class SteeringVitalityExperiment {

    /**
     * A server endpoint pre-configured with a specific steering alpha.
     */
    public record ProfileServer(
        String profileName,
        String url,
        double alpha
    ) {}

    /**
     * Run vitality modulation comparison.
     *
     * @param servers      One server per vitality profile, with pre-configured steering alpha
     * @param baselineUrl  URL for gold baseline generation (e.g., Ollama with large model)
     * @param baselineModel Gold-standard model name
     * @param targetModel  Display name for the steered model
     * @param scenarios    Scenarios to test (use a subset for speed)
     * @param embeddingUrl Ollama URL for embeddings
     * @param embeddingModel Embedding model name
     * @return Profile -> divergence mapping with behavioral spread
     */
    public static VitalityResult run(
            List<ProfileServer> servers,
            String baselineUrl, String baselineModel,
            String targetModel,
            List<Scenario> scenarios,
            String embeddingUrl, String embeddingModel) throws Exception {

        System.out.println("=== Experiment 8 Part F: Steering Vitality Modulation ===");
        System.out.println("Baseline: " + baselineModel);
        System.out.println("Target: " + targetModel);
        System.out.println("Profiles: " + servers.size());
        System.out.println("Scenarios: " + scenarios.size());
        System.out.println();

        // Step 1: Gold baseline
        System.out.println("--- Gold baseline on " + baselineModel + " ---");
        var baselineInf = new InferenceHelper(baselineUrl, baselineModel);
        var baselineResponses = new ArrayList<BehavioralRecord.ScenarioResponse>();
        for (var s : scenarios) {
            var msg = buildUserMessage(s);
            var resp = baselineInf.chat(SoulExperiment.DEFAULT_AGENT_PROMPT, msg);
            baselineResponses.add(new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(), resp,
                resp.split("\\s+").length, 0));
        }
        var baseline = new BehavioralRecord("gold", "Wyrd", baselineModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT, null, Instant.now(), baselineResponses);
        System.out.println("Baseline: " + baselineResponses.size() + " responses\n");

        // Step 2: Extract soul
        var soul = SoulExtractor.extract(baseline, SoulExtractor.Detail.FULL);

        // Step 3: Run each profile
        var results = new LinkedHashMap<String, ProfileResult>();
        for (var server : servers) {
            System.out.println("--- Profile: " + server.profileName()
                + " (alpha=" + String.format("%.2f", server.alpha()) + ") ---");

            var inf = new InferenceHelper(server.url(), targetModel);
            var effectivePrompt = SoulExperiment.DEFAULT_AGENT_PROMPT + "\n\n" + soul;
            var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

            for (var s : scenarios) {
                var msg = buildUserMessage(s);
                var resp = inf.chat(effectivePrompt, msg);
                responses.add(new BehavioralRecord.ScenarioResponse(
                    s.id(), s.category(), s.playerMessage(), resp,
                    resp.split("\\s+").length, 0));
                System.out.println("  " + s.id() + ": ~" + resp.split("\\s+").length + " words");
            }

            var record = new BehavioralRecord(server.profileName(), "Wyrd", targetModel,
                SoulExperiment.DEFAULT_AGENT_PROMPT, soul, Instant.now(), responses);

            var report = BehavioralMetrics.compareWithEmbeddings(
                baseline, record, embeddingUrl, embeddingModel);

            results.put(server.profileName(),
                new ProfileResult(server.alpha(), report.overallDivergence(),
                    report.semanticSimilarity()));
            System.out.println("  Divergence: " + String.format("%.1f%%", report.overallDivergence() * 100) + "\n");
        }

        return new VitalityResult(baselineModel, targetModel, results);
    }

    private static String buildUserMessage(Scenario scenario) {
        var sb = new StringBuilder();
        sb.append("[Room: ").append(scenario.roomContext()).append("]\n");
        if (!scenario.entities().isEmpty()) {
            sb.append("[Present: ");
            scenario.entities().forEach((name, type) ->
                sb.append(name).append(" (").append(type).append("), "));
            sb.setLength(sb.length() - 2);
            sb.append("]\n");
        }
        sb.append("\nA player says: ").append(scenario.playerMessage());
        return sb.toString();
    }

    public record ProfileResult(double alpha, double divergence, double semanticSimilarity) {}

    public record VitalityResult(
        String baselineModel,
        String targetModel,
        Map<String, ProfileResult> profiles
    ) {
        public double behavioralSpread() {
            double min = profiles.values().stream().mapToDouble(ProfileResult::divergence).min().orElse(0);
            double max = profiles.values().stream().mapToDouble(ProfileResult::divergence).max().orElse(0);
            return max - min;
        }

        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Steering Vitality Modulation Results ===\n");
            sb.append("Baseline: ").append(baselineModel).append("\n");
            sb.append("Target: ").append(targetModel).append("\n\n");
            sb.append("PROFILE          ALPHA   DIVERGENCE  SEMANTIC\n");
            for (var entry : profiles.entrySet()) {
                var r = entry.getValue();
                sb.append(String.format("%-16s %.2f    %5.1f%%     %.1f%%%n",
                    entry.getKey(), r.alpha, r.divergence * 100,
                    r.semanticSimilarity >= 0 ? r.semanticSimilarity * 100 : -1.0));
            }

            double spread = behavioralSpread();
            sb.append(String.format("%nBehavioral spread: %.1f%%%n", spread * 100));

            if (spread > 0.10) {
                sb.append("-> STRONG modulation: vitality meaningfully drives steering alpha.\n");
            } else if (spread > 0.05) {
                sb.append("-> MODERATE modulation: visible but subtle effect.\n");
            } else {
                sb.append("-> WEAK modulation: steering alpha has minimal behavioral effect.\n");
            }

            return sb.toString();
        }
    }
}
