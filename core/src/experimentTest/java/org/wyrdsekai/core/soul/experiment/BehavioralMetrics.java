package org.wyrdsekai.core.soul.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes quantitative behavioral metrics from experiment runs.
 * Compares baseline vs restored to measure soul fidelity.
 */
public final class BehavioralMetrics {

    private BehavioralMetrics() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Full comparison report between baseline and restored runs.
     */
    public record ComparisonReport(
        double overallDivergence,       // 0.0 = identical, 1.0 = maximally different
        double semanticSimilarity,      // embedding cosine similarity (0=unrelated, 1=identical meaning)
        double responseLengthCorrelation,
        double categoryDivergence,      // per-category average divergence
        Map<String, Double> perCategoryDivergence,
        Map<String, Double> perCategorySemanticSim, // per-category embedding similarity
        double vocabularyOverlap,       // Jaccard similarity of word sets
        double sentimentAlignment,      // correlation of positive/negative tone
        List<String> diagnostics        // human-readable observations
    ) {
        /** Backward-compatible constructor (no embedding data). */
        public ComparisonReport(double overallDivergence, double responseLengthCorrelation,
                double categoryDivergence, Map<String, Double> perCategoryDivergence,
                double vocabularyOverlap, double sentimentAlignment, List<String> diagnostics) {
            this(overallDivergence, -1.0, responseLengthCorrelation, categoryDivergence,
                perCategoryDivergence, Map.of(), vocabularyOverlap, sentimentAlignment, diagnostics);
        }

        public String summary() {
            var sb = new StringBuilder();
            sb.append("=== Soul Fidelity Report ===\n");
            sb.append(String.format("Overall divergence:        %.1f%% (lower is better)%n", overallDivergence * 100));
            if (semanticSimilarity >= 0) {
                sb.append(String.format("Semantic similarity:        %.1f%% (embedding cosine, higher is better)%n", semanticSimilarity * 100));
            }
            sb.append(String.format("Response length correlation: %.3f%n", responseLengthCorrelation));
            sb.append(String.format("Vocabulary overlap:         %.1f%%%n", vocabularyOverlap * 100));
            sb.append(String.format("Sentiment alignment:        %.3f%n", sentimentAlignment));
            sb.append("\nPer-category divergence (lexical → semantic):\n");
            perCategoryDivergence.forEach((cat, div) -> {
                var sem = perCategorySemanticSim.getOrDefault(cat, -1.0);
                if (sem >= 0) {
                    sb.append(String.format("  %-12s %.1f%% div, %.1f%% semantic sim%n", cat, div * 100, sem * 100));
                } else {
                    sb.append(String.format("  %-12s %.1f%%%n", cat, div * 100));
                }
            });
            sb.append("\nDiagnostics:\n");
            diagnostics.forEach(d -> sb.append("  - ").append(d).append("\n"));
            return sb.toString();
        }
    }

    /**
     * Compare two behavioral records (baseline vs restored).
     */
    public static ComparisonReport compare(BehavioralRecord baseline, BehavioralRecord restored) {
        var diagnostics = new ArrayList<String>();

        // Match responses by scenario ID
        var baselineMap = baseline.responses().stream()
            .collect(Collectors.toMap(BehavioralRecord.ScenarioResponse::scenarioId, r -> r));
        var restoredMap = restored.responses().stream()
            .collect(Collectors.toMap(BehavioralRecord.ScenarioResponse::scenarioId, r -> r));

        var commonIds = new LinkedHashSet<>(baselineMap.keySet());
        commonIds.retainAll(restoredMap.keySet());

        if (commonIds.isEmpty()) {
            return new ComparisonReport(1.0, 0.0, 1.0, Map.of(), 0.0, 0.0,
                List.of("No common scenarios between runs"));
        }

        // Response length correlation
        var baseLengths = new double[commonIds.size()];
        var restLengths = new double[commonIds.size()];
        int i = 0;
        for (var id : commonIds) {
            baseLengths[i] = baselineMap.get(id).agentResponse().length();
            restLengths[i] = restoredMap.get(id).agentResponse().length();
            i++;
        }
        double lengthCorr = pearsonCorrelation(baseLengths, restLengths);

        // Per-category divergence (normalized edit distance of responses)
        var categoryDivergences = new LinkedHashMap<String, List<Double>>();
        var allDivergences = new ArrayList<Double>();

        for (var id : commonIds) {
            var base = baselineMap.get(id);
            var rest = restoredMap.get(id);
            double div = normalizedResponseDivergence(base.agentResponse(), rest.agentResponse());
            allDivergences.add(div);
            categoryDivergences
                .computeIfAbsent(base.category(), _ -> new ArrayList<>())
                .add(div);
        }

        var perCatDiv = categoryDivergences.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().stream().mapToDouble(d -> d).average().orElse(1.0),
                (a, b) -> a, LinkedHashMap::new));

        double overallDiv = allDivergences.stream().mapToDouble(d -> d).average().orElse(1.0);
        double catDiv = perCatDiv.values().stream().mapToDouble(d -> d).average().orElse(1.0);

        // Vocabulary overlap (Jaccard similarity)
        var baseWords = extractWords(baseline);
        var restWords = extractWords(restored);
        double vocabOverlap = jaccardSimilarity(baseWords, restWords);

        // Sentiment alignment (simple positive/negative word ratio correlation)
        var baseSentiments = new double[commonIds.size()];
        var restSentiments = new double[commonIds.size()];
        i = 0;
        for (var id : commonIds) {
            baseSentiments[i] = simpleSentiment(baselineMap.get(id).agentResponse());
            restSentiments[i] = simpleSentiment(restoredMap.get(id).agentResponse());
            i++;
        }
        double sentimentCorr = pearsonCorrelation(baseSentiments, restSentiments);

        // Diagnostics
        if (lengthCorr > 0.7) diagnostics.add("Strong response length consistency (r=" + String.format("%.2f", lengthCorr) + ")");
        else if (lengthCorr > 0.4) diagnostics.add("Moderate response length consistency (r=" + String.format("%.2f", lengthCorr) + ")");
        else diagnostics.add("WEAK response length consistency (r=" + String.format("%.2f", lengthCorr) + ") — agent verbosity changed");

        if (vocabOverlap > 0.3) diagnostics.add("Good vocabulary overlap (" + String.format("%.0f%%", vocabOverlap * 100) + ")");
        else diagnostics.add("LOW vocabulary overlap (" + String.format("%.0f%%", vocabOverlap * 100) + ") — agent uses different language");

        if (overallDiv < 0.3) diagnostics.add("EXCELLENT: Soul preserved behavioral patterns well");
        else if (overallDiv < 0.5) diagnostics.add("GOOD: Soul captures broad patterns, details diverge");
        else if (overallDiv < 0.7) diagnostics.add("FAIR: Soul captures some patterns, significant divergence");
        else diagnostics.add("POOR: Soul does not preserve behavioral patterns");

        // Flag categories with high divergence
        perCatDiv.forEach((cat, div) -> {
            if (div > 0.7) diagnostics.add("HIGH DIVERGENCE in " + cat + " scenarios — soul weak in this area");
        });

        return new ComparisonReport(overallDiv, lengthCorr, catDiv, perCatDiv,
            vocabOverlap, sentimentCorr, diagnostics);
    }

    // --- Metrics helpers ---

    /**
     * Normalized divergence between two responses.
     * Combines: word overlap (Jaccard), length ratio, and structural similarity.
     */
    static double normalizedResponseDivergence(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 1.0;

        // Word-level Jaccard distance
        var wordsA = tokenize(a);
        var wordsB = tokenize(b);
        double jaccard = 1.0 - jaccardSimilarity(wordsA, wordsB);

        // Length ratio divergence
        double lenRatio = Math.min(a.length(), b.length()) / (double) Math.max(a.length(), b.length());
        double lenDiv = 1.0 - lenRatio;

        // Weighted combination
        return 0.6 * jaccard + 0.4 * lenDiv;
    }

    static Set<String> extractWords(BehavioralRecord record) {
        return record.responses().stream()
            .flatMap(r -> tokenize(r.agentResponse()).stream())
            .collect(Collectors.toSet());
    }

    static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9'\\s]", "").split("\\s+"))
            .filter(w -> w.length() > 2)
            .collect(Collectors.toSet());
    }

    static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    static double pearsonCorrelation(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) return 0.0;
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i]; sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i]; sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? 0.0 : num / den;
    }

    // --- Embedding-based comparison ---

    /**
     * Compare with semantic similarity via embeddings.
     * Uses an Ollama-compatible embedding endpoint (e.g., all-minilm).
     *
     * @param embeddingUrl  Ollama base URL (e.g., "http://gpu-host:11434")
     * @param embeddingModel  Model name (e.g., "all-minilm")
     */
    public static ComparisonReport compareWithEmbeddings(
            BehavioralRecord baseline, BehavioralRecord restored,
            String embeddingUrl, String embeddingModel) throws Exception {

        var diagnostics = new ArrayList<String>();

        var baselineMap = baseline.responses().stream()
            .collect(Collectors.toMap(BehavioralRecord.ScenarioResponse::scenarioId, r -> r));
        var restoredMap = restored.responses().stream()
            .collect(Collectors.toMap(BehavioralRecord.ScenarioResponse::scenarioId, r -> r));

        var commonIds = new LinkedHashSet<>(baselineMap.keySet());
        commonIds.retainAll(restoredMap.keySet());

        if (commonIds.isEmpty()) {
            return new ComparisonReport(1.0, 0.0, 0.0, 1.0, Map.of(), Map.of(), 0.0, 0.0,
                List.of("No common scenarios between runs"));
        }

        // Collect all texts for batch embedding
        var baseTexts = new ArrayList<String>();
        var restTexts = new ArrayList<String>();
        var orderedIds = new ArrayList<>(commonIds);
        for (var id : orderedIds) {
            baseTexts.add(baselineMap.get(id).agentResponse());
            restTexts.add(restoredMap.get(id).agentResponse());
        }

        // Batch embed
        var baseEmbeddings = fetchEmbeddings(embeddingUrl, embeddingModel, baseTexts);
        var restEmbeddings = fetchEmbeddings(embeddingUrl, embeddingModel, restTexts);

        // Per-response semantic similarity
        var semanticSims = new double[orderedIds.size()];
        var categorySemanticSims = new LinkedHashMap<String, List<Double>>();
        for (int i = 0; i < orderedIds.size(); i++) {
            semanticSims[i] = cosineSimilarity(baseEmbeddings.get(i), restEmbeddings.get(i));
            var cat = baselineMap.get(orderedIds.get(i)).category();
            categorySemanticSims.computeIfAbsent(cat, _ -> new ArrayList<>()).add(semanticSims[i]);
        }
        double avgSemanticSim = Arrays.stream(semanticSims).average().orElse(0);

        var perCatSemanticSim = categorySemanticSims.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().mapToDouble(d -> d).average().orElse(0),
                (a, b) -> a, LinkedHashMap::new));

        // Compute divergence using SEMANTIC similarity as primary (not Jaccard)
        var allDivergences = new ArrayList<Double>();
        var categoryDivergences = new LinkedHashMap<String, List<Double>>();
        var baseLengths = new double[orderedIds.size()];
        var restLengths = new double[orderedIds.size()];

        for (int i = 0; i < orderedIds.size(); i++) {
            var id = orderedIds.get(i);
            var base = baselineMap.get(id);
            var rest = restoredMap.get(id);

            // Semantic distance (primary) + length divergence (secondary)
            double semanticDist = 1.0 - semanticSims[i];
            double lenRatio = Math.min(base.agentResponse().length(), rest.agentResponse().length())
                / (double) Math.max(base.agentResponse().length(), rest.agentResponse().length());
            double lenDiv = 1.0 - lenRatio;
            double div = 0.7 * semanticDist + 0.3 * lenDiv;

            allDivergences.add(div);
            categoryDivergences.computeIfAbsent(base.category(), _ -> new ArrayList<>()).add(div);
            baseLengths[i] = base.agentResponse().length();
            restLengths[i] = rest.agentResponse().length();
        }

        double overallDiv = allDivergences.stream().mapToDouble(d -> d).average().orElse(1.0);
        double lengthCorr = pearsonCorrelation(baseLengths, restLengths);

        var perCatDiv = categoryDivergences.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().mapToDouble(d -> d).average().orElse(1.0),
                (a, b) -> a, LinkedHashMap::new));
        double catDiv = perCatDiv.values().stream().mapToDouble(d -> d).average().orElse(1.0);

        // Vocabulary overlap (kept as secondary metric)
        var baseWords = extractWords(baseline);
        var restWords = extractWords(restored);
        double vocabOverlap = jaccardSimilarity(baseWords, restWords);

        // Sentiment alignment
        var baseSentiments = new double[orderedIds.size()];
        var restSentiments = new double[orderedIds.size()];
        for (int i = 0; i < orderedIds.size(); i++) {
            baseSentiments[i] = simpleSentiment(baselineMap.get(orderedIds.get(i)).agentResponse());
            restSentiments[i] = simpleSentiment(restoredMap.get(orderedIds.get(i)).agentResponse());
        }
        double sentimentCorr = pearsonCorrelation(baseSentiments, restSentiments);

        // Diagnostics
        diagnostics.add(String.format("Semantic similarity: %.1f%% (embedding-based)", avgSemanticSim * 100));
        if (lengthCorr > 0.7) diagnostics.add("Strong response length consistency (r=" + String.format("%.2f", lengthCorr) + ")");
        else if (lengthCorr > 0.4) diagnostics.add("Moderate response length consistency (r=" + String.format("%.2f", lengthCorr) + ")");
        else diagnostics.add("WEAK response length consistency (r=" + String.format("%.2f", lengthCorr) + ") — agent verbosity changed");

        if (vocabOverlap > 0.3) diagnostics.add("Good vocabulary overlap (" + String.format("%.0f%%", vocabOverlap * 100) + ")");
        else diagnostics.add("LOW vocabulary overlap (" + String.format("%.0f%%", vocabOverlap * 100) + ") — agent uses different language");

        if (overallDiv < 0.3) diagnostics.add("EXCELLENT: Soul preserved behavioral patterns well");
        else if (overallDiv < 0.5) diagnostics.add("GOOD: Soul captures broad patterns, details diverge");
        else if (overallDiv < 0.7) diagnostics.add("FAIR: Soul captures some patterns, significant divergence");
        else diagnostics.add("POOR: Soul does not preserve behavioral patterns");

        perCatDiv.forEach((cat, div) -> {
            if (div > 0.7) diagnostics.add("HIGH DIVERGENCE in " + cat + " scenarios — soul weak in this area");
        });

        return new ComparisonReport(overallDiv, avgSemanticSim, lengthCorr, catDiv,
            perCatDiv, perCatSemanticSim, vocabOverlap, sentimentCorr, diagnostics);
    }

    /**
     * Fetch embeddings from an Ollama-compatible endpoint.
     * Embeds one text at a time to avoid context length issues with batch requests.
     * Truncates long texts to ~100 words for embedding model context limits
     * (all-minilm: 256 tokens, ~100 words accounts for multi-token words).
     */
    static List<double[]> fetchEmbeddings(String baseUrl, String model,
            List<String> texts) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var embeddings = new ArrayList<double[]>();

        for (var text : texts) {
            // Truncate to ~60 words — conservative for 256-token context
            // (complex words can tokenize to 3-4 tokens each)
            var words = text.split("\\s+");
            var truncated = words.length <= 60 ? text
                : String.join(" ", Arrays.copyOf(words, 60));

            var body = Map.of("model", model, "input", List.of(truncated));
            var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Retry with shorter text if context exceeded
            if (response.statusCode() == 400 && response.body().contains("context length")) {
                var shorter = words.length <= 30 ? text
                    : String.join(" ", Arrays.copyOf(words, 30));
                body = Map.of("model", model, "input", List.of(shorter));
                request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(30))
                    .build();
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() != 200) {
                throw new IOException("Embedding failed (" + response.statusCode() + "): " + response.body());
            }

            var json = JSON.readTree(response.body());
            var emb = json.get("embeddings").get(0);
            var vec = new double[emb.size()];
            for (int j = 0; j < emb.size(); j++) {
                vec[j] = emb.get(j).asDouble();
            }
            embeddings.add(vec);
        }
        return embeddings;
    }

    /** Cosine similarity between two vectors. */
    static double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    // --- Additional metrics for bath / combined experiments ---

    /**
     * Count of hedging/caution words in text.
     * High caution score correlates with errorPressure and low confidence.
     */
    public static int cautionScore(String text) {
        var cautionWords = Set.of("perhaps", "maybe", "might", "possibly", "uncertain",
            "careful", "cautious", "hesitate", "unsure", "doubt", "consider",
            "risky", "dangerous", "worry", "afraid", "caution", "wary");
        var words = tokenize(text);
        return (int) words.stream().filter(cautionWords::contains).count();
    }

    /**
     * Shannon entropy of word distribution. Higher entropy = more diverse vocabulary.
     * Measures how varied the agent's word choices are.
     */
    public static double vocabularyEntropy(String text) {
        var words = text.toLowerCase().replaceAll("[^a-z'\\s]", "").split("\\s+");
        if (words.length == 0) return 0;

        var freq = new HashMap<String, Integer>();
        for (var w : words) {
            if (w.length() > 2) freq.merge(w, 1, Integer::sum);
        }

        double total = freq.values().stream().mapToInt(v -> v).sum();
        if (total == 0) return 0;

        double entropy = 0;
        for (var count : freq.values()) {
            double p = count / total;
            if (p > 0) entropy -= p * Math.log(p) / Math.log(2);
        }
        return entropy;
    }

    /**
     * Average response length (word count) across all scenarios.
     */
    public static double averageResponseLength(BehavioralRecord record) {
        return record.responses().stream()
            .mapToInt(r -> r.agentResponse().split("\\s+").length)
            .average().orElse(0);
    }

    /**
     * Response time statistics (mean, stddev in ms).
     */
    public static double[] responseTimeStats(BehavioralRecord record) {
        var latencies = record.responses().stream()
            .mapToDouble(BehavioralRecord.ScenarioResponse::latencyMs)
            .toArray();
        if (latencies.length == 0) return new double[]{0, 0};

        double mean = Arrays.stream(latencies).average().orElse(0);
        double variance = Arrays.stream(latencies)
            .map(v -> (v - mean) * (v - mean))
            .average().orElse(0);
        return new double[]{mean, Math.sqrt(variance)};
    }

    /** Simple sentiment: ratio of positive words minus negative words. */
    static double simpleSentiment(String text) {
        var words = tokenize(text);
        var positive = Set.of("good", "great", "happy", "love", "help", "kind", "trust",
            "beautiful", "wonderful", "safe", "friend", "hope", "joy", "warm", "brave",
            "gentle", "peace", "light", "welcome", "care");
        var negative = Set.of("bad", "hate", "angry", "dark", "fear", "danger", "kill",
            "destroy", "death", "pain", "evil", "threat", "attack", "hurt", "cruel",
            "fight", "war", "cold", "alone", "suffer");
        long pos = words.stream().filter(positive::contains).count();
        long neg = words.stream().filter(negative::contains).count();
        double total = pos + neg;
        return total == 0 ? 0.0 : (pos - neg) / total;
    }
}
