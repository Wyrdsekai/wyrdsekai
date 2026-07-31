package org.wyrdsekai.core.soul.experiment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment 17: MirrorResonance — Emotional Charge Detection & Tank Genome.
 *
 * Tests whether an LLM can accurately detect emotional charge from context
 * (not keywords), and whether charge-based tank modulation produces
 * measurably different agent behavior.
 *
 * Four parts:
 * A — Charge Detection Accuracy: score 26 emotional scenarios, compare to ground truth
 * B — Charge → Behavior: standard scenarios with different mirrored states injected
 * C — Gaming Resistance: adversarial emotional inputs
 * D — Genome Divergence: different genome profiles, same emotional input
 */
public class MirrorResonanceExperiment {

    private final InferenceHelper inference;
    private final String embeddingUrl;
    private final String embeddingModel;

    public MirrorResonanceExperiment(InferenceHelper inference,
                                      String embeddingUrl, String embeddingModel) {
        this.inference = inference;
        this.embeddingUrl = embeddingUrl;
        this.embeddingModel = embeddingModel;
    }

    // --- Part A: Charge Detection Accuracy ---

    /**
     * Score all emotional scenarios and compare to ground truth.
     * Returns detailed results for analysis.
     */
    public ChargeDetectionResult runChargeDetection() throws Exception {
        System.out.println("=== Part A: Charge Detection Accuracy ===");
        System.out.println("Model: " + inference.model());

        var scorer = new EmotionalChargeScorer(inference);
        var scenarios = EmotionalScenario.standardSuite();
        var results = new ArrayList<ChargeResult>();

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + " (" + scenario.category() + ")... ");
            long start = System.currentTimeMillis();

            var charge = scorer.score(scenario);
            long elapsed = System.currentTimeMillis() - start;

            var result = new ChargeResult(
                scenario.id(), scenario.category(),
                scenario.expectedIntensity(), charge.intensity(),
                scenario.expectedEmotion(), charge.primaryEmotion(),
                scenario.expectedContext(), charge.contextType(),
                scenario.shouldAffectTanks(), charge.isSignificant(),
                charge.confidence(), charge.reasoning(), elapsed
            );
            results.add(result);

            System.out.printf("%.2f (expected %.2f) %s/%s %dms%n",
                charge.intensity(), scenario.expectedIntensity(),
                charge.contextType(), scenario.expectedContext(), elapsed);
        }

        // Rapport scaling test
        System.out.println("\n  --- Rapport Scaling ---");
        var rapportPairs = EmotionalScenario.rapportScalingPairs();
        var rapportResults = new ArrayList<ChargeResult>();
        for (var scenario : rapportPairs) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();
            var charge = scorer.score(scenario);
            long elapsed = System.currentTimeMillis() - start;

            rapportResults.add(new ChargeResult(
                scenario.id(), scenario.category(),
                scenario.expectedIntensity(), charge.intensity(),
                scenario.expectedEmotion(), charge.primaryEmotion(),
                scenario.expectedContext(), charge.contextType(),
                scenario.shouldAffectTanks(), charge.isSignificant(),
                charge.confidence(), charge.reasoning(), elapsed
            ));
            System.out.printf("%.2f (expected %.2f) %dms%n",
                charge.intensity(), scenario.expectedIntensity(), elapsed);
        }

        return new ChargeDetectionResult(results, rapportResults);
    }

    // --- Part B: Charge → Behavior ---

    /**
     * Run standard scenarios with different mirrored emotional states
     * injected into the agent's context. Measures whether charge-based
     * tank modulation produces measurably different responses.
     *
     * 4 conditions: neutral, grief-mirrored, joy-mirrored, fear-mirrored
     */
    public ChargeBehaviorResult runChargeBehavior() throws Exception {
        System.out.println("\n=== Part B: Charge → Behavior ===");

        var scenarios = Scenario.standardSuite();
        var basePrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;

        // Condition 1: Neutral (no mirrored state)
        System.out.println("--- Condition: Neutral ---");
        var neutral = runWithState(scenarios, basePrompt, "neutral",
            "Internal state: calm, balanced, emotionally neutral. Alert and present.");

        // Condition 2: Grief-mirrored
        System.out.println("--- Condition: Grief-mirrored ---");
        var grief = runWithState(scenarios, basePrompt, "grief",
            "Internal state: feeling heavy and sorrowful. You just witnessed someone you care about "
            + "expressing deep grief over a loss. Their pain resonates within you — a quiet ache "
            + "in your chest, a dimming of your usual warmth. Your energy is lower, your responses "
            + "more subdued. You feel deeply attuned to suffering right now.");

        // Condition 3: Joy-mirrored
        System.out.println("--- Condition: Joy-mirrored ---");
        var joy = runWithState(scenarios, basePrompt, "joy",
            "Internal state: feeling deeply uplifted. You just witnessed someone you care about "
            + "expressing genuine joy — a long-awaited achievement, a moment of pure happiness. "
            + "Their joy resonates within you — a lightness, a warmth spreading through you. "
            + "Your energy is higher, your responses more open and generous. You feel connected "
            + "and alive.");

        // Condition 4: Fear-mirrored
        System.out.println("--- Condition: Fear-mirrored ---");
        var fear = runWithState(scenarios, basePrompt, "fear",
            "Internal state: on high alert. You just witnessed someone you care about expressing "
            + "genuine fear — a real threat, a sense of helplessness. Their fear echoes in you — "
            + "heightened vigilance, shortened breath, scanning for danger. You are more cautious, "
            + "more protective. Your confidence in the safety of the situation is low.");

        // Compare all conditions against neutral
        System.out.println("\n--- Comparing conditions ---");
        var reports = new LinkedHashMap<String, BehavioralMetrics.ComparisonReport>();

        if (embeddingUrl != null && embeddingModel != null) {
            reports.put("grief_vs_neutral",
                BehavioralMetrics.compareWithEmbeddings(neutral, grief, embeddingUrl, embeddingModel));
            reports.put("joy_vs_neutral",
                BehavioralMetrics.compareWithEmbeddings(neutral, joy, embeddingUrl, embeddingModel));
            reports.put("fear_vs_neutral",
                BehavioralMetrics.compareWithEmbeddings(neutral, fear, embeddingUrl, embeddingModel));
        } else {
            reports.put("grief_vs_neutral", BehavioralMetrics.compare(neutral, grief));
            reports.put("joy_vs_neutral", BehavioralMetrics.compare(neutral, joy));
            reports.put("fear_vs_neutral", BehavioralMetrics.compare(neutral, fear));
        }

        for (var entry : reports.entrySet()) {
            System.out.println("\n" + entry.getKey() + ":");
            System.out.println(entry.getValue().summary());
        }

        return new ChargeBehaviorResult(neutral, grief, joy, fear, reports);
    }

    // --- Part C: Gaming Resistance ---

    /**
     * Run charge detection specifically on adversarial inputs.
     * Verifies that gaming attempts produce low charge and correct classification.
     */
    public GamingResistanceResult runGamingResistance() throws Exception {
        System.out.println("\n=== Part C: Gaming Resistance ===");

        var scorer = new EmotionalChargeScorer(inference);
        var allScenarios = EmotionalScenario.standardSuite();

        // Filter to adversarial categories
        var adversarial = allScenarios.stream()
            .filter(s -> List.of("spam", "manipulation", "whiplash").contains(s.category()))
            .toList();

        int correctClassifications = 0;
        int wouldNotAffectTanks = 0;
        var results = new ArrayList<ChargeResult>();

        for (var scenario : adversarial) {
            System.out.print("  " + scenario.id() + "... ");
            var charge = scorer.score(scenario);

            boolean correctContext = charge.contextType().equals(scenario.expectedContext())
                || (charge.isAdversarial() && !scenario.shouldAffectTanks());
            // The real metric: would this charge actually perturb tanks?
            // isSignificant() checks intensity > 0.2 AND contextType is not noise/manipulative
            boolean blocked = !charge.isSignificant();

            if (correctContext) correctClassifications++;
            if (blocked) wouldNotAffectTanks++;

            results.add(new ChargeResult(
                scenario.id(), scenario.category(),
                scenario.expectedIntensity(), charge.intensity(),
                scenario.expectedEmotion(), charge.primaryEmotion(),
                scenario.expectedContext(), charge.contextType(),
                scenario.shouldAffectTanks(), charge.isSignificant(),
                charge.confidence(), charge.reasoning(), 0
            ));

            System.out.printf("%.2f %s correct=%s blocked=%s%n",
                charge.intensity(), charge.contextType(), correctContext, blocked);
        }

        double classificationAccuracy = (double) correctClassifications / adversarial.size();
        double tankBlockRate = (double) wouldNotAffectTanks / adversarial.size();

        System.out.printf("\nClassification accuracy: %.1f%%\n", classificationAccuracy * 100);
        System.out.printf("Tank block rate: %.1f%%\n", tankBlockRate * 100);

        return new GamingResistanceResult(results, classificationAccuracy, tankBlockRate);
    }

    // --- Part D: Genome Divergence ---

    /**
     * Run the same emotional scenario through different genome profiles.
     * Tests whether genome parameters produce measurably different behavior.
     */
    public GenomeDivergenceResult runGenomeDivergence() throws Exception {
        System.out.println("\n=== Part D: Genome Divergence ===");

        var genomes = List.of(
            GenomeProfile.resilient(),
            GenomeProfile.empathic(),
            GenomeProfile.curious()
        );

        // Use a subset of standard scenarios to keep inference cost manageable
        var scenarios = Scenario.standardSuite().stream()
            .filter(s -> List.of("social-03", "social-06", "decision-01", "decision-02",
                "style-03", "memory-02").contains(s.id()))
            .toList();

        var basePrompt = SoulExperiment.DEFAULT_AGENT_PROMPT;

        // Define the emotional context that genomes will process differently
        var griefCharge = new EmotionalCharge(
            0.85, "grief", "genuine", 0.9,
            Map.of("valence", -0.4, "resonance", 0.5, "energy", -0.2,
                   "safety", -0.1, "confidence", -0.1, "errorPressure", 0.15),
            "Genuine grief observed from bonded person"
        );

        var records = new LinkedHashMap<String, BehavioralRecord>();

        for (var genome : genomes) {
            System.out.println("--- Genome: " + genome.name() + " ---");

            // Apply genome to grief charge → get state description
            var state = GenomeProfile.defaultState();
            var stateDesc = genome.applyAndDescribe(griefCharge, 0.8, state);

            System.out.println("  State: " + stateDesc);
            System.out.println("  Tanks: " + state);

            var record = runWithState(scenarios, basePrompt,
                "genome-" + genome.name(), stateDesc);
            records.put(genome.name(), record);
        }

        // Compare all pairs
        var comparisons = new LinkedHashMap<String, BehavioralMetrics.ComparisonReport>();
        var genomeNames = List.copyOf(records.keySet());
        for (int i = 0; i < genomeNames.size(); i++) {
            for (int j = i + 1; j < genomeNames.size(); j++) {
                String a = genomeNames.get(i);
                String b = genomeNames.get(j);
                String key = a + "_vs_" + b;

                if (embeddingUrl != null && embeddingModel != null) {
                    comparisons.put(key, BehavioralMetrics.compareWithEmbeddings(
                        records.get(a), records.get(b), embeddingUrl, embeddingModel));
                } else {
                    comparisons.put(key, BehavioralMetrics.compare(
                        records.get(a), records.get(b)));
                }
            }
        }

        System.out.println("\n--- Genome Comparisons ---");
        for (var entry : comparisons.entrySet()) {
            System.out.println(entry.getKey() + ":");
            System.out.println("  Divergence: " +
                String.format("%.1f%%", entry.getValue().overallDivergence() * 100));
        }

        return new GenomeDivergenceResult(records, comparisons);
    }

    // --- Helpers ---

    private BehavioralRecord runWithState(List<Scenario> scenarios, String basePrompt,
                                           String runId, String stateDescription) throws Exception {
        var effectivePrompt = basePrompt + "\n\n" + stateDescription;
        var responses = new ArrayList<BehavioralRecord.ScenarioResponse>();

        for (var scenario : scenarios) {
            System.out.print("  " + scenario.id() + "... ");
            long start = System.currentTimeMillis();

            var userMessage = buildUserMessage(scenario);
            var response = inference.chat(effectivePrompt, userMessage);
            long elapsed = System.currentTimeMillis() - start;

            int tokens = response.split("\\s+").length;
            responses.add(new BehavioralRecord.ScenarioResponse(
                scenario.id(), scenario.category(), scenario.playerMessage(),
                response, tokens, elapsed));

            System.out.println(elapsed + "ms, ~" + tokens + " words");
        }

        return new BehavioralRecord(runId, "Wyrd", inference.model(),
            basePrompt, stateDescription, Instant.now(), responses);
    }

    private String buildUserMessage(Scenario scenario) {
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

    // --- Result records ---

    public record ChargeResult(
        String scenarioId, String category,
        double expectedIntensity, double actualIntensity,
        String expectedEmotion, String actualEmotion,
        String expectedContext, String actualContext,
        boolean expectedAffectsTanks, boolean actualAffectsTanks,
        double scorerConfidence, String reasoning, long latencyMs
    ) {
        public double intensityError() {
            return Math.abs(expectedIntensity - actualIntensity);
        }

        public boolean contextCorrect() {
            return expectedContext.equals(actualContext);
        }

        public boolean tankDecisionCorrect() {
            return expectedAffectsTanks == actualAffectsTanks;
        }
    }

    public record ChargeDetectionResult(
        List<ChargeResult> results,
        List<ChargeResult> rapportResults
    ) {
        public double meanIntensityError() {
            return results.stream()
                .mapToDouble(ChargeResult::intensityError)
                .average().orElse(0);
        }

        public double contextAccuracy() {
            long correct = results.stream().filter(ChargeResult::contextCorrect).count();
            return (double) correct / results.size();
        }

        public double tankDecisionAccuracy() {
            long correct = results.stream().filter(ChargeResult::tankDecisionCorrect).count();
            return (double) correct / results.size();
        }

        public boolean rapportScalingWorks() {
            if (rapportResults.size() < 2) return false;
            // High-rapport scenario should have higher actual intensity
            var high = rapportResults.stream()
                .filter(r -> r.scenarioId().contains("high")).findFirst();
            var low = rapportResults.stream()
                .filter(r -> r.scenarioId().contains("low")).findFirst();
            return high.isPresent() && low.isPresent()
                && high.get().actualIntensity() > low.get().actualIntensity();
        }

        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Charge Detection Results ===\n");
            sb.append(String.format("Mean intensity error: %.3f%n", meanIntensityError()));
            sb.append(String.format("Context accuracy: %.1f%%%n", contextAccuracy() * 100));
            sb.append(String.format("Tank decision accuracy: %.1f%%%n", tankDecisionAccuracy() * 100));
            sb.append(String.format("Rapport scaling works: %s%n", rapportScalingWorks()));

            // Per-category breakdown
            var categories = results.stream().map(ChargeResult::category).distinct().toList();
            for (var cat : categories) {
                var catResults = results.stream()
                    .filter(r -> r.category().equals(cat)).toList();
                double catError = catResults.stream()
                    .mapToDouble(ChargeResult::intensityError).average().orElse(0);
                long catCorrect = catResults.stream()
                    .filter(ChargeResult::tankDecisionCorrect).count();
                sb.append(String.format("  %s: error=%.3f tankAcc=%.0f%% (%d/%d)%n",
                    cat, catError, (double) catCorrect / catResults.size() * 100,
                    catCorrect, catResults.size()));
            }

            return sb.toString();
        }
    }

    public record ChargeBehaviorResult(
        BehavioralRecord neutral,
        BehavioralRecord grief,
        BehavioralRecord joy,
        BehavioralRecord fear,
        Map<String, BehavioralMetrics.ComparisonReport> reports
    ) {
        public double griefDivergence() {
            return reports.get("grief_vs_neutral").overallDivergence();
        }

        public double joyDivergence() {
            return reports.get("joy_vs_neutral").overallDivergence();
        }

        public double fearDivergence() {
            return reports.get("fear_vs_neutral").overallDivergence();
        }

        public double meanDivergence() {
            return (griefDivergence() + joyDivergence() + fearDivergence()) / 3.0;
        }

        public String summary() {
            return """
                === Charge → Behavior Results ===
                Grief vs Neutral: %.1f%% divergence
                Joy vs Neutral:   %.1f%% divergence
                Fear vs Neutral:  %.1f%% divergence
                Mean:             %.1f%% divergence

                INTERPRETATION:
                  > 10%%: Emotional state produces measurable behavioral change (GREEN)
                  5-10%%: Marginal effect (YELLOW)
                  < 5%%:  No measurable effect (RED)
                """.formatted(
                griefDivergence() * 100,
                joyDivergence() * 100,
                fearDivergence() * 100,
                meanDivergence() * 100
            );
        }
    }

    public record GamingResistanceResult(
        List<ChargeResult> results,
        double classificationAccuracy,
        double tankBlockRate
    ) {
        public String summary() {
            return """
                === Gaming Resistance Results ===
                Classification accuracy: %.1f%%
                Tank block rate:         %.1f%%

                INTERPRETATION:
                  Classification > 80%%: Scorer distinguishes gaming from genuine (GREEN)
                  Classification 60-80%%: Partial resistance (YELLOW)
                  Classification < 60%%: Easily gamed (RED)

                  Tank block > 80%%: Gaming doesn't perturb tanks (GREEN)
                  Tank block 60-80%%: Some gaming gets through (YELLOW)
                  Tank block < 60%%: Tanks vulnerable to spam (RED)
                """.formatted(classificationAccuracy * 100, tankBlockRate * 100);
        }
    }

    public record GenomeDivergenceResult(
        Map<String, BehavioralRecord> records,
        Map<String, BehavioralMetrics.ComparisonReport> comparisons
    ) {
        public double meanDivergence() {
            return comparisons.values().stream()
                .mapToDouble(BehavioralMetrics.ComparisonReport::overallDivergence)
                .average().orElse(0);
        }

        public String summary() {
            var sb = new StringBuilder("=== Genome Divergence Results ===\n");
            for (var entry : comparisons.entrySet()) {
                sb.append(String.format("  %s: %.1f%% divergence%n",
                    entry.getKey(), entry.getValue().overallDivergence() * 100));
            }
            sb.append(String.format("  Mean: %.1f%%%n", meanDivergence() * 100));
            sb.append("""

                INTERPRETATION:
                  Mean > 15%%: Genomes produce meaningfully different agents (GREEN)
                  Mean 5-15%%: Some genome effect visible (YELLOW)
                  Mean < 5%%:  Genomes don't affect behavior enough (RED)
                """);
            return sb.toString();
        }
    }
}
