package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * second-opinion gate alongside
 * {@link MentalSimulator}. Loads a curated example bank
 * ({@code scripts/m2/plan_examples.jsonl}) and runs in-context scoring of a
 * candidate plan against a stratified sample of those examples.
 *
 * <p>Singleton-style: load examples once at boot via {@link #loadDefault()}
 * (or pass a custom path / stream). Each {@link #score} call samples 8 success
 * + 6 failure + 4 edge examples — Drive-9B reads them + the candidate, returns
 * a JSON-shaped score. Stratified sampling keeps the few-shot prompt balanced
 * regardless of bank growth.</p>
 *
 * <p>No fine-tuning. The example bank IS the M2 training signal.</p>
 */
public final class M2PlanScorer {

    private static final Logger log = LoggerFactory.getLogger(M2PlanScorer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_HINT = "cap:reasoning";
    private static final double DEFAULT_TEMPERATURE = 0.2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final int MAX_TOKENS = 400;

    private static final int N_SUCCESS = 8;
    private static final int N_FAILURE = 6;
    private static final int N_EDGE = 4;

    /** Default location for the example bank, relative to repo root. */
    public static final Path DEFAULT_BANK_PATH = Path.of("scripts", "m2", "plan_examples.jsonl");

    /** Outcome enum mirroring the bank schema. "completed" = success; everything else = failure. */
    private static final Set<String> SUCCESS_OUTCOMES = Set.of("completed");
    private static final Set<String> EDGE_OUTCOMES = Set.of(
        "completed_edge", "single_step", "long_run", "cross_zone", "bunshin", "ward_recover"
    );

    /** GBNF grammar for the score response. */
    private static final String JSON_GRAMMAR = """
            root             ::= "{" ws "\\"confidence\\"" ws ":" ws number ws "," ws "\\"predicted_outcome\\"" ws ":" ws outcome ws "," ws "\\"reasoning\\"" ws ":" ws string ws "}"
            outcome          ::= "\\"completed\\"" | "\\"loop\\"" | "\\"abandoned\\"" | "\\"blocked\\"" | "\\"premature_done\\"" | "\\"missing_prereq\\"" | "\\"wrong_room\\"" | "\\"no_action\\"" | "\\"missing_delivery\\"" | "\\"ward_denied\\""
            string           ::= "\\"" char* "\\""
            char             ::= [^"\\\\] | "\\\\" ["\\\\/bfnrt]
            number           ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            ws               ::= [ \\t\\n]*
            """;

    // ── Records ──────────────────────────────────────────────

    /** A single labelled example loaded from the JSONL bank. */
    public record Example(
        String planId,
        String context,
        List<String> actions,
        String outcome,
        double confidence,
        String notes
    ) {
        boolean isSuccess() { return SUCCESS_OUTCOMES.contains(outcome); }
        boolean isEdge() { return EDGE_OUTCOMES.contains(outcome); }
    }

    /** Final score returned to caller. */
    public record Score(
        double confidence,
        String predictedOutcome,
        String reasoning,
        boolean parseFailure
    ) {
        public static Score fallback(String why) {
            return new Score(0.5, "uncertain", why, true);
        }

        /** True if confidence is high enough to commit silently (≥ 0.7). */
        public boolean highConfidence() { return confidence >= 0.7; }

        /** True if confidence is below the rejection threshold (< 0.4). */
        public boolean shouldReject() { return confidence < 0.4; }

        /**
         * §M4-D — category-aware reject check used by
         * the proactive synthesis path (see {@link PredictionPlanSynthesizer}).
         * The model-emitted-plan path keeps the default 0.4 threshold; only
         * proactive predictions consult the Beta-Binomial calibrator.
         */
        public boolean shouldReject(double threshold) { return confidence < threshold; }
    }

    // ── Bank ─────────────────────────────────────────────────

    private final List<Example> successes;
    private final List<Example> failures;
    private final List<Example> edges;
    private final Random rng;

    public M2PlanScorer(List<Example> bank, long seed) {
        var s = new ArrayList<Example>();
        var f = new ArrayList<Example>();
        var e = new ArrayList<Example>();
        for (var ex : bank) {
            if (ex.isEdge()) e.add(ex);
            else if (ex.isSuccess()) s.add(ex);
            else f.add(ex);
        }
        this.successes = List.copyOf(s);
        this.failures = List.copyOf(f);
        this.edges = List.copyOf(e);
        this.rng = new Random(seed);
        log.info("M2PlanScorer loaded: {} successes, {} failures, {} edge",
            successes.size(), failures.size(), edges.size());
    }

    public M2PlanScorer(List<Example> bank) {
        this(bank, System.nanoTime());
    }

    /**
     * Load the bank from {@link #DEFAULT_BANK_PATH} (dev-iteration source) or the
     * classpath-bundled copy at {@code /m2/plan_examples.jsonl} (production .deb /
     * .pkg deploys, where the JVM cwd is not the repo root).
     *
     * <p>Disk takes precedence over classpath so an edit to {@code scripts/m2/}
     * during dev wins. The two files should be kept in sync — {@code core}
     * Gradle builds copy {@code scripts/m2/plan_examples.jsonl} into
     * {@code resources/m2/} as part of {@code processResources}.</p>
     */
    public static M2PlanScorer loadDefault() {
        try {
            if (Files.exists(DEFAULT_BANK_PATH)) {
                return new M2PlanScorer(parseJsonl(Files.readAllBytes(DEFAULT_BANK_PATH)));
            }
        } catch (Exception e) {
            log.warn("M2PlanScorer: failed to read {}: {}", DEFAULT_BANK_PATH, e.getMessage());
        }
        // Classpath fallback — production deploys (.deb / .pkg) and any JVM
        // launched outside the repo root. The bank is bundled under core
        // resources at /m2/plan_examples.jsonl.
        try (InputStream in = M2PlanScorer.class.getResourceAsStream("/m2/plan_examples.jsonl")) {
            if (in != null) {
                var buf = new ByteArrayOutputStream();
                in.transferTo(buf);
                return new M2PlanScorer(parseJsonl(buf.toByteArray()));
            }
        } catch (Exception e) {
            log.debug("M2PlanScorer: no classpath bank: {}", e.getMessage());
        }
        log.warn("M2PlanScorer: no example bank found; scorer will return fallback for every call");
        return new M2PlanScorer(List.of());
    }

    static List<Example> parseJsonl(byte[] data) throws Exception {
        var examples = new ArrayList<Example>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                var node = MAPPER.readTree(line);
                var actions = new ArrayList<String>();
                var actNode = node.path("actions");
                if (actNode.isArray()) {
                    for (var a : actNode) actions.add(a.asText(""));
                }
                examples.add(new Example(
                    node.path("plan_id").asText(""),
                    node.path("context").asText(""),
                    List.copyOf(actions),
                    node.path("outcome").asText("completed"),
                    node.path("confidence").asDouble(0.5),
                    node.path("notes").asText("")
                ));
            }
        }
        return examples;
    }

    // ── Public API ───────────────────────────────────────────

    public CompletionStage<Score> score(
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            String context,
            List<String> candidatePlan) {
        if (router == null || candidatePlan == null || candidatePlan.isEmpty()) {
            return CompletableFuture.completedFuture(Score.fallback("no router or empty plan"));
        }
        if (successes.isEmpty() && failures.isEmpty() && edges.isEmpty()) {
            return CompletableFuture.completedFuture(Score.fallback("empty example bank"));
        }

        var requestId = "m2-score-" + UUID.randomUUID();
        var sample = sampleStratified();
        var systemPrompt = buildSystemPrompt(sample);
        var userPrompt = buildUserPrompt(context, candidatePlan);
        var messages = List.of(
            new InferenceClient.ChatMessage("system", systemPrompt),
            new InferenceClient.ChatMessage("user", userPrompt));

        CompletionStage<InferenceRouter.InferResponse> future = AskPattern.ask(
            router,
            (ActorRef<InferenceRouter.InferResponse> replyTo) ->
                new InferenceRouter.ChatRequest(
                    requestId, MODEL_HINT, messages,
                    MAX_TOKENS, DEFAULT_TEMPERATURE, replyTo,
                    null, JSON_GRAMMAR, null, null, null, null, null, null, true),
            DEFAULT_TIMEOUT, scheduler);

        return future.handle((response, failure) -> {
            if (failure != null) {
                log.debug("M2PlanScorer inference failed: {}", failure.getMessage());
                return Score.fallback("inference failure: " + failure.getMessage());
            }
            if (response instanceof InferenceRouter.InferOk ok) {
                return parseScore(ok.content());
            }
            if (response instanceof InferenceRouter.InferError err) {
                return Score.fallback("inference error: " + err.error());
            }
            return Score.fallback("unknown response type");
        });
    }

    // ── Sampling + prompt building ──────────────────────────

    List<Example> sampleStratified() {
        var out = new ArrayList<Example>(N_SUCCESS + N_FAILURE + N_EDGE);
        out.addAll(pickN(successes, N_SUCCESS));
        out.addAll(pickN(failures, N_FAILURE));
        out.addAll(pickN(edges, N_EDGE));
        return out;
    }

    private List<Example> pickN(List<Example> pool, int n) {
        if (pool.isEmpty()) return List.of();
        if (pool.size() <= n) return pool;
        var copy = new ArrayList<>(pool);
        Collections.shuffle(copy, rng);
        return copy.subList(0, n);
    }

    static String buildSystemPrompt(List<Example> examples) {
        var sb = new StringBuilder(2048);
        sb.append("You are scoring agent plan quality. Below are example plans labelled with outcomes.\n");
        sb.append("Use them to assess whether a new candidate plan will succeed. Watch for: loop antipatterns,\n");
        sb.append("premature goal_done, missing prerequisites, wrong-room actions, and undelivered results.\n\n");
        sb.append("CRITICAL — `confidence` is the probability the plan REACHES ITS GOAL, not your\n");
        sb.append("confidence in your own analysis. Anchor on the labelled examples:\n");
        sb.append("  • copy the example's labelled confidence when the candidate matches its outcome\n");
        sb.append("  • a plan that matches a `loop` / `premature_done` / `missing_prereq` / `no_action`\n");
        sb.append("    / `missing_delivery` example MUST score < 0.20\n");
        sb.append("  • a plan that matches a `completed` example may score 0.80–0.95\n");
        sb.append("  • mixed signal → 0.40–0.60\n");
        sb.append("Set `predicted_outcome` to the matching example's outcome label.\n\n");
        for (int i = 0; i < examples.size(); i++) {
            var ex = examples.get(i);
            sb.append("[EXAMPLE ").append(i + 1).append(": ").append(ex.outcome()).append("]\n");
            sb.append("Context: ").append(ex.context()).append('\n');
            sb.append("Plan: ").append(String.join(" → ", ex.actions())).append('\n');
            sb.append("Outcome: ").append(ex.outcome())
              .append(" (confidence ").append(ex.confidence()).append(")\n");
            if (ex.notes() != null && !ex.notes().isBlank()) {
                sb.append("Notes: ").append(ex.notes()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Respond with JSON only. No prose outside the JSON.\n");
        return sb.toString();
    }

    static String buildUserPrompt(String context, List<String> candidatePlan) {
        var sb = new StringBuilder(256);
        sb.append("Score this plan:\n");
        sb.append("Context: ").append(context == null ? "(unspecified)" : context).append('\n');
        sb.append("Plan: ").append(String.join(" → ", candidatePlan)).append("\n\n");
        sb.append("Respond JSON: {\"confidence\":0.0-1.0,\"predicted_outcome\":\"completed|loop|abandoned|blocked|premature_done|missing_prereq|wrong_room|no_action|missing_delivery|ward_denied\",\"reasoning\":\"...\"}");
        return sb.toString();
    }

    // ── Parsing ───────────────────────────────────────────────

    static Score parseScore(String content) {
        if (content == null || content.isBlank()) {
            return Score.fallback("empty inference content");
        }
        try {
            var node = MAPPER.readTree(content);
            var conf = clamp(node.path("confidence").asDouble(0.5));
            var outcome = node.path("predicted_outcome").asText("uncertain");
            var reasoning = node.path("reasoning").asText("");
            return new Score(conf, outcome, reasoning, false);
        } catch (Exception e) {
            log.debug("M2PlanScorer JSON parse failed: {} preview={}", e.getMessage(),
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
            return Score.fallback("json parse failure: " + e.getMessage());
        }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.5;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    // ── Stats / debugging ────────────────────────────────────

    public int bankSize() { return successes.size() + failures.size() + edges.size(); }

    /** Test-only: deterministic seed for sampling. */
    public List<Example> sampleStratifiedForTest() { return sampleStratified(); }
}
