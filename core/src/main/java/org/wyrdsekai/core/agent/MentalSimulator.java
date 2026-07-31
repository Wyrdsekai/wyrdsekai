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

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * runs Drive-9B inference with
 * the {@link WorldModelPromptRenderer} output as system prefix and parses
 * a JSON-structured prediction (per-step success + final state + confidence
 * + reasoning).
 *
 * <p>This is the M3-prompt path: zero fine-tuning, structure-first. The
 * rendered world model goes in front of the candidate plan; Drive-9B reads
 * the closed grammar and predicts.</p>
 *
 * <p>Pure-function shape: caller supplies router, scheduler, prompt prefix
 * source, and the candidate plan; simulator produces a {@link Prediction}.
 * Testable without GraalJS or live llama-server — see
 * {@link org.wyrdsekai.core.agent.MentalSimulatorTest}.</p>
 */
public final class MentalSimulator {

    private static final Logger log = LoggerFactory.getLogger(MentalSimulator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Drive-9B is the reasoning tier (cap:reasoning maps to it via InferenceRouter). */
    private static final String MODEL_HINT = "cap:reasoning";

    /** Temperature 0.2 — deterministic enough for prediction, slight variation for nuance. */
    private static final double DEFAULT_TEMPERATURE = 0.2;

    /** Default per-call timeout. Drive-9B chain prediction can take 10-30s. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);

    /** Max tokens for a chain prediction response. */
    private static final int MAX_TOKENS = 600;

    /**
     * §M3-Path-A — number of in-context
     * example simulations rendered into M3's system prompt. Bank-calibration
     * 2026-05-08 (n=151, Brier 0.466) showed M3 over-rejecting 73% of plans
     * because it had no exemplars to anchor against — only the world-model
     * structure. Adding M2's 4-success / 2-failure stratification (rendered as
     * step-by-step simulations rather than M2's plan-score format) gives M3
     * the same anchoring without changing the prediction grammar.
     *
     * <p>Smaller sample than M2 (4+2 vs 8+6+4) because each M3 example is
     * rendered as a multi-line step-by-step trace rather than M2's single
     * Plan: line — keeps the prompt under ~5k tokens including world model.</p>
     */
    private static final int N_SUCCESS_EXAMPLES = 4;
    private static final int N_FAILURE_EXAMPLES = 2;

    /** Lazy-loaded shared example bank (same bank as {@link M2PlanScorer}). */
    private static volatile List<M2PlanScorer.Example> CACHED_BANK = null;
    private static final Object BANK_LOCK = new Object();

    /**
     * GBNF grammar enforcing the JSON response shape:
     * {steps: [{action, success, outcome}, ...], final_state, confidence, reasoning}
     */
    private static final String JSON_GRAMMAR = """
            root      ::= "{" ws "\\"steps\\"" ws ":" ws stepArr ws "," ws "\\"final_state\\"" ws ":" ws string ws "," ws "\\"confidence\\"" ws ":" ws number ws "," ws "\\"reasoning\\"" ws ":" ws string ws "}"
            stepArr   ::= "[" ws (step (ws "," ws step)*)? ws "]"
            step      ::= "{" ws "\\"action\\"" ws ":" ws string ws "," ws "\\"success\\"" ws ":" ws successVal ws "," ws "\\"outcome\\"" ws ":" ws string ws "}"
            successVal::= "\\"yes\\"" | "\\"no\\"" | "\\"uncertain\\""
            string    ::= "\\"" char* "\\""
            char      ::= [^"\\\\] | "\\\\" ["\\\\/bfnrt]
            number    ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            ws        ::= [ \\t\\n]*
            """;

    // ── Records ───────────────────────────────────────────

    /** Single step's predicted outcome. */
    public record StepResult(
        String action,
        String predictedOutcome,
        boolean predictedSuccess,
        boolean uncertain
    ) {}

    /** Full prediction for a plan (or single action chain of length 1). */
    public record Prediction(
        String predictedEndState,
        double confidence,
        List<StepResult> stepResults,
        String reasoning,
        boolean parseFailure
    ) {
        public static Prediction lowConfidenceFallback(String reason, List<String> actions) {
            var steps = new ArrayList<StepResult>(actions.size());
            for (var a : actions) {
                steps.add(new StepResult(a, "unknown", false, true));
            }
            return new Prediction("unknown", 0.0, steps, reason, true);
        }

        /** True if confidence is high enough to commit silently (≥ 0.7). */
        public boolean highConfidence() { return confidence >= 0.7; }

        /** True if confidence is below the rejection threshold (< 0.4). */
        public boolean shouldReject() { return confidence < 0.4; }
    }

    // ── Bank loading (shared with M2PlanScorer) ───────────

    /**
     * §M3-Path-A — load the same
     * example bank M2 uses, lazily and once. Disk path
     * ({@code scripts/m2/plan_examples.jsonl}) wins over the classpath fallback
     * ({@code /m2/plan_examples.jsonl} bundled into core resources by gradle's
     * {@code syncM2Bank} task). Mirrors {@link M2PlanScorer#loadDefault} so
     * a single edit to the JSONL feeds both gates.
     */
    static List<M2PlanScorer.Example> loadBank() {
        var cached = CACHED_BANK;
        if (cached != null) return cached;
        synchronized (BANK_LOCK) {
            if (CACHED_BANK != null) return CACHED_BANK;
            try {
                if (Files.exists(M2PlanScorer.DEFAULT_BANK_PATH)) {
                    CACHED_BANK = List.copyOf(M2PlanScorer.parseJsonl(
                        Files.readAllBytes(M2PlanScorer.DEFAULT_BANK_PATH)));
                    log.info("MentalSimulator bank loaded from disk: {} examples",
                        CACHED_BANK.size());
                    return CACHED_BANK;
                }
            } catch (Exception e) {
                log.debug("MentalSimulator: disk bank read failed: {}", e.getMessage());
            }
            try (var in = MentalSimulator.class.getResourceAsStream("/m2/plan_examples.jsonl")) {
                if (in != null) {
                    var buf = new ByteArrayOutputStream();
                    in.transferTo(buf);
                    CACHED_BANK = List.copyOf(M2PlanScorer.parseJsonl(buf.toByteArray()));
                    log.info("MentalSimulator bank loaded from classpath: {} examples",
                        CACHED_BANK.size());
                    return CACHED_BANK;
                }
            } catch (Exception e) {
                log.debug("MentalSimulator: classpath bank read failed: {}", e.getMessage());
            }
            log.warn("MentalSimulator: no example bank found; gates will run without anchoring");
            CACHED_BANK = List.of();
            return CACHED_BANK;
        }
    }

    /**
     * Stratified sample from the bank: {@link #N_SUCCESS_EXAMPLES} successes +
     * {@link #N_FAILURE_EXAMPLES} failures. Smaller than M2's draw because each
     * M3 example renders as a multi-line step-by-step trace; this keeps the
     * full prompt under ~5k tokens including world-model render.
     */
    static List<M2PlanScorer.Example> sampleStratified(
            List<M2PlanScorer.Example> bank, Random rng) {
        if (bank == null || bank.isEmpty()) return List.of();
        var successes = new ArrayList<M2PlanScorer.Example>();
        var failures = new ArrayList<M2PlanScorer.Example>();
        for (var ex : bank) {
            if ("completed".equals(ex.outcome())) successes.add(ex);
            else failures.add(ex);
        }
        Collections.shuffle(successes, rng);
        Collections.shuffle(failures, rng);
        var out = new ArrayList<M2PlanScorer.Example>(N_SUCCESS_EXAMPLES + N_FAILURE_EXAMPLES);
        for (int i = 0; i < Math.min(N_SUCCESS_EXAMPLES, successes.size()); i++) out.add(successes.get(i));
        for (int i = 0; i < Math.min(N_FAILURE_EXAMPLES, failures.size()); i++) out.add(failures.get(i));
        return out;
    }

    // ── Public API ────────────────────────────────────────

    /**
     * Predict the outcome of a single action against the current state.
     */
    public static CompletionStage<Prediction> simulate(
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            Supplier<String> worldModelPrefix,
            String currentState,
            String action) {
        return simulateChain(router, scheduler, worldModelPrefix, currentState, List.of(action));
    }

    /**
     * step-level mental sim.
     * Run during plan execution after one or more steps have completed; the
     * model sees what already happened and forecasts only the remaining steps.
     * That focused view is more accurate than re-simulating the full plan
     * from scratch (the prefix establishes ground truth instead of having to
     * be re-predicted), and it lets the gate fire after each step rather than
     * only at plan creation.
     *
     * <p>{@code executed} is the list of completed step descriptions in order
     * (may be empty). {@code remaining} is the list of pending steps. The
     * function asks the model to predict only the remaining steps and report
     * a confidence for the rest of the plan.</p>
     */
    public static CompletionStage<Prediction> simulateRemaining(
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            Supplier<String> worldModelPrefix,
            String currentState,
            List<String> executed,
            List<String> remaining) {
        if (router == null || remaining == null || remaining.isEmpty()) {
            return CompletableFuture.completedFuture(
                Prediction.lowConfidenceFallback(
                    "no router or no remaining steps",
                    remaining == null ? List.of() : remaining));
        }
        var augmentedState = buildExecutedStatePrefix(currentState, executed);
        return simulateChain(router, scheduler, worldModelPrefix, augmentedState, remaining);
    }

    static String buildExecutedStatePrefix(String currentState, List<String> executed) {
        if (executed == null || executed.isEmpty()) {
            return currentState == null ? "(unspecified)" : currentState;
        }
        var sb = new StringBuilder(256);
        sb.append(currentState == null ? "(unspecified)" : currentState);
        sb.append("\n\nAlready completed (treat as ground truth, not predictions):\n");
        for (int i = 0; i < executed.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(executed.get(i)).append('\n');
        }
        sb.append("Predict ONLY the remaining steps below.");
        return sb.toString();
    }

    /**
     * Predict the outcome of an action chain against the current state.
     * Returns low-confidence fallback if router is null, plan is empty, or
     * inference fails.
     */
    public static CompletionStage<Prediction> simulateChain(
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            Supplier<String> worldModelPrefix,
            String currentState,
            List<String> actions) {
        if (router == null || actions == null || actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                Prediction.lowConfidenceFallback(
                    "no router or empty plan", actions == null ? List.of() : actions));
        }

        var requestId = "mental-sim-" + UUID.randomUUID();
        var examples = sampleStratified(loadBank(), new Random(requestId.hashCode()));
        var systemPrompt = buildSystemPrompt(worldModelPrefix, examples);
        var userPrompt = buildUserPrompt(currentState, actions);
        var messages = List.of(
            new InferenceClient.ChatMessage("system", systemPrompt),
            new InferenceClient.ChatMessage("user", userPrompt));

        CompletionStage<InferenceRouter.InferResponse> future = AskPattern.ask(
            router,
            (ActorRef<InferenceRouter.InferResponse> replyTo) ->
                new InferenceRouter.ChatRequest(
                    requestId,
                    MODEL_HINT,
                    messages,
                    MAX_TOKENS,
                    DEFAULT_TEMPERATURE,
                    replyTo,
                    null,           // preferredBackend
                    JSON_GRAMMAR,   // GBNF grammar — JSON shape enforcement
                    null,           // format
                    null,           // tools
                    null,           // toolChoice
                    null,           // topP
                    null,           // presencePenalty
                    null,           // repetitionPenalty
                    true),          // localOnly — never route mental sim cross-zone
            DEFAULT_TIMEOUT,
            scheduler);

        return future.handle((response, failure) -> {
            if (failure != null) {
                log.debug("MentalSimulator inference failed: {}", failure.getMessage());
                return Prediction.lowConfidenceFallback(
                    "inference failure: " + failure.getMessage(), actions);
            }
            if (response instanceof InferenceRouter.InferOk ok) {
                return parsePrediction(ok.content(), actions);
            }
            if (response instanceof InferenceRouter.InferError err) {
                log.debug("MentalSimulator inference error: {}", err.error());
                return Prediction.lowConfidenceFallback(
                    "inference error: " + err.error(), actions);
            }
            return Prediction.lowConfidenceFallback("unknown response type", actions);
        });
    }

    // ── Prompt building ───────────────────────────────────

    /**
     * Backward-compat overload — used by tests that don't care about example anchoring.
     */
    static String buildSystemPrompt(Supplier<String> worldModelPrefix) {
        return buildSystemPrompt(worldModelPrefix, List.of());
    }

    /**
     * §M3-Path-A — render world-model
     * prefix + stratified bank examples (as step-by-step simulations) +
     * confidence-scale anchoring. The example block teaches M3 how to map
     * known plan shapes to step-level success/failure traces, fixing the
     * "no exemplar → over-reject" failure mode observed in Brier 0.466
     * calibration data.
     */
    static String buildSystemPrompt(Supplier<String> worldModelPrefix,
                                    List<M2PlanScorer.Example> examples) {
        var prefix = worldModelPrefix == null ? "" : worldModelPrefix.get();
        if (prefix == null) prefix = "";
        var sb = new StringBuilder(8192);
        sb.append(prefix).append("\n\n");
        sb.append("You are simulating a companion's plan in this zone. Given the current state\n")
          .append("and the candidate action chain, predict whether each step will succeed and\n")
          .append("what the final state will be. Use only the action consequences observed in the\n")
          .append("ZONE STATE MAP and ACTION CONSEQUENCES sections above. If a step would loop,\n")
          .append("miss a prerequisite, or violate a KNOWN PATTERN, mark it failure.\n\n");
        sb.append("CRITICAL — the `confidence` field is the probability the WHOLE PLAN reaches\n")
          .append("its goal successfully, NOT your confidence in your own analysis. Use this scale:\n")
          .append("  • 0.85–1.00  every step succeeds, no antipatterns, plan delivers result\n")
          .append("  • 0.55–0.84  mostly clean but one uncertain step or weak prerequisite\n")
          .append("  • 0.25–0.54  at least one step likely fails OR a prerequisite is missing\n")
          .append("  • 0.00–0.24  plan loops, violates a KNOWN PATTERN, or never delivers\n")
          .append("If ANY step's `success` is \"no\" or the plan matches an antipattern in\n")
          .append("KNOWN PATTERNS, confidence MUST be below 0.4. A loop antipattern, premature\n")
          .append("goal_done, or missing prerequisite all force confidence ≤ 0.20.\n\n");
        if (examples != null && !examples.isEmpty()) {
            sb.append("EXAMPLES — labelled step-by-step simulations of plans you've seen before.\n")
              .append("Anchor your prediction on the closest matching example. Short conversational\n")
              .append("plans (\"Player said hi\", emotional check-ins, single-tool calls) are\n")
              .append("typically clean successes; do not over-reject them.\n\n");
            for (int i = 0; i < examples.size(); i++) {
                renderExample(sb, i + 1, examples.get(i));
            }
        }
        sb.append("Respond with JSON only. No prose outside the JSON.\n");
        return sb.toString();
    }

    /** Render one bank example as a step-by-step simulation trace. */
    static void renderExample(StringBuilder sb, int idx, M2PlanScorer.Example ex) {
        var label = ex.outcome();
        sb.append("[EXAMPLE ").append(idx).append(": ").append(label).append("]\n");
        sb.append("Context: ").append(ex.context()).append('\n');
        sb.append("Predicted steps:\n");
        var actions = ex.actions();
        var failed = !"completed".equals(label);
        boolean loop = "loop".equals(label);
        boolean noAction = "no_action".equals(label);
        // For successes mark every step "yes". For failures the trace tells the
        // model which step pattern broke down — heuristic, tied to outcome label.
        // Intermediate steps actually executed; only the breakdown point is "failure".
        for (int i = 0; i < actions.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(actions.get(i));
            boolean lastStep = (i == actions.size() - 1);
            if (!failed) {
                sb.append(" → success\n");
            } else if (loop) {
                // First step succeeds (action ran). Repeats after that = no progress.
                if (i == 0) sb.append(" → success\n");
                else sb.append(" → no progress (loop antipattern)\n");
            } else if (noAction) {
                // Every "step" is actually narration that never fires a tool.
                sb.append(" → narration only, no tool fired\n");
            } else if (lastStep) {
                sb.append(" → ").append(label).append(" — plan does not deliver\n");
            } else {
                // Earlier steps ran cleanly; only the last step is the failure point.
                sb.append(" → success\n");
            }
        }
        sb.append("final_state: ");
        if (failed) sb.append("plan failed — ").append(label);
        else sb.append("goal delivered cleanly");
        sb.append('\n');
        sb.append("confidence: ").append(ex.confidence()).append("\n\n");
    }

    static String buildUserPrompt(String currentState, List<String> actions) {
        var sb = new StringBuilder(256);
        sb.append("Current state: ").append(currentState == null ? "(unspecified)" : currentState)
          .append("\n\nPlan:\n");
        for (int i = 0; i < actions.size(); i++) {
            sb.append(i + 1).append(". ").append(actions.get(i)).append('\n');
        }
        sb.append('\n')
          .append("Respond JSON: {\"steps\":[{\"action\":\"...\",\"success\":\"yes|no|uncertain\",\"outcome\":\"...\"},...],\"final_state\":\"...\",\"confidence\":0.0-1.0,\"reasoning\":\"...\"}\n")
          .append("Remember: `confidence` = probability the plan reaches its goal. A loop or premature goal_done forces confidence below 0.2.");
        return sb.toString();
    }

    // ── JSON parsing ──────────────────────────────────────

    static Prediction parsePrediction(String content, List<String> actions) {
        if (content == null || content.isBlank()) {
            return Prediction.lowConfidenceFallback("empty inference content", actions);
        }
        try {
            var node = MAPPER.readTree(content);
            var stepsNode = node.path("steps");
            var steps = new ArrayList<StepResult>();
            if (stepsNode.isArray()) {
                for (var s : stepsNode) {
                    var action = s.path("action").asText("");
                    var success = s.path("success").asText("uncertain");
                    var outcome = s.path("outcome").asText("");
                    var ok = "yes".equalsIgnoreCase(success);
                    var unc = "uncertain".equalsIgnoreCase(success);
                    steps.add(new StepResult(action, outcome, ok, unc));
                }
            }
            var finalState = node.path("final_state").asText("unknown");
            var confidence = clamp(node.path("confidence").asDouble(0.5));
            var reasoning = node.path("reasoning").asText("");
            return new Prediction(finalState, confidence, steps, reasoning, false);
        } catch (Exception e) {
            log.debug("MentalSimulator JSON parse failed: {} content_preview={}",
                e.getMessage(), content.length() > 80 ? content.substring(0, 80) + "..." : content);
            return Prediction.lowConfidenceFallback(
                "json parse failure: " + e.getMessage(), actions);
        }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.5;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private MentalSimulator() {} // static-only
}
