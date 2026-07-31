package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * §M4-A — turn an actionable {@link OraclePrediction}
 * into a {@link ProactiveAction.Initiative} candidate, scored by
 * {@link M2PlanScorer} so the proactivity gate only fires plans that survive the
 * same calibration the model-emitted-plan path uses.
 *
 * <p>Each prediction category maps to a small set of action-sequence templates.
 * The synthesizer picks one template, fills in slots from the prediction text,
 * scores the candidate via M2, and returns the candidate (or null) wrapped as
 * an Initiative whose {@code actionJson} is the serialized plan + {@code description}
 * carries the human-readable trigger.
 *
 * <p>Plans that score below M2's rejection threshold (< 0.4) return null.
 * The caller is expected to skip firing on null and let the prediction try again
 * on the next sleep cycle (its slot in {@code OraclePredictionCache} survives).
 */
public final class PredictionPlanSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(PredictionPlanSynthesizer.class);

    /** Category → ordered list of plan templates. First match wins. */
    private static final Map<String, List<List<String>>> TEMPLATES = Map.of(
        "temporal", List.of(
            // user pattern: at this hour they look up X / they ask about Y
            List.of("library_search", "summarize", "tell_agent", "goal_done"),
            List.of("recall", "tell_agent", "goal_done")),
        "anticipation", List.of(
            List.of("query_oracle", "tell_agent", "goal_done"),
            List.of("library_search", "summarize", "tell_agent", "goal_done")),
        "anomaly", List.of(
            List.of("query_oracle", "tell_agent", "goal_done"),
            List.of("introspect", "tell_agent", "goal_done")),
        "forecast", List.of(
            List.of("query_oracle", "summarize", "tell_agent", "goal_done")),
        "recommendation", List.of(
            List.of("library_search", "summarize", "tell_agent", "goal_done")),
        "topic", List.of(
            List.of("library_search", "tell_agent", "goal_done")),
        "sequence", List.of(
            List.of("recall", "tell_agent", "goal_done")),
        "pattern", List.of(
            List.of("recall", "tell_agent", "goal_done"),
            List.of("introspect", "tell_agent", "goal_done")),
        "correlation", List.of(
            List.of("query_oracle", "tell_agent", "goal_done"))
    );

    /** Default fallback for unknown categories. */
    private static final List<String> DEFAULT_TEMPLATE = List.of(
        "tell_agent", "goal_done");

    private PredictionPlanSynthesizer() {}

    /**
     * Synthesize an Initiative for a prediction. Returns null if the prediction
     * isn't actionable, no template matched, or M2 rejected the plan.
     *
     * @param prediction the source prediction
     * @param context    context string for M2 (typically prediction.text + recent state)
     * @param router     inference router for M2 scoring (null → skip scoring, accept plan)
     * @param scheduler  pekko scheduler for AskPattern
     * @param scorer     pre-loaded M2PlanScorer (singleton recommended)
     */
    public static CompletionStage<ProactiveAction.Initiative> synthesize(
            OraclePrediction prediction,
            String context,
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            M2PlanScorer scorer) {
        return synthesize(prediction, context, router, scheduler, scorer, null);
    }

    /**
     * Full overload — accepts a §M4-D {@link BetaBinomialCalibrator} that
     * supplies a category-specific reject threshold. When null, falls back
     * to the M2 default ({@code 0.4}).
     */
    public static CompletionStage<ProactiveAction.Initiative> synthesize(
            OraclePrediction prediction,
            String context,
            ActorRef<InferenceRouter.Command> router,
            Scheduler scheduler,
            M2PlanScorer scorer,
            BetaBinomialCalibrator calibrator) {
        if (prediction == null || !prediction.actionable()) {
            return CompletableFuture.completedFuture(null);
        }
        var templates = TEMPLATES.getOrDefault(prediction.category(), List.of(DEFAULT_TEMPLATE));
        // Pick the first template; future variants could rank by drive state.
        var actions = templates.get(0);
        var plan = renderPlan(actions, prediction);

        // No router → skip scoring (test path / inference unavailable).
        if (router == null || scorer == null) {
            log.debug("Synthesize without scoring (router/scorer null) for prediction={}",
                prediction.id());
            return CompletableFuture.completedFuture(buildInitiative(prediction, plan));
        }

        var planContext = context == null
            ? "Proactive: " + prediction.text()
            : context + "\nProactive trigger: " + prediction.text();
        double threshold = calibrator == null
            ? BetaBinomialCalibrator.DEFAULT_THRESHOLD
            : calibrator.thresholdFor(prediction.category());
        return scorer.score(router, scheduler, planContext, plan)
            .thenApply(score -> {
                if (score == null) {
                    log.warn("M2 score null for prediction={}, treating as reject", prediction.id());
                    return null;
                }
                if (score.shouldReject(threshold)) {
                    log.info("Prediction {} synthesized plan rejected by M2 (conf={}, threshold={}): {}",
                        prediction.id(),
                        String.format("%.2f", score.confidence()),
                        String.format("%.2f", threshold),
                        score.reasoning());
                    return null;
                }
                log.info("Prediction {} → Initiative (M2 conf={}, threshold={}, plan={})",
                    prediction.id(),
                    String.format("%.2f", score.confidence()),
                    String.format("%.2f", threshold),
                    plan);
                return buildInitiative(prediction, plan);
            });
    }

    /** Convenience overload that pulls scorer + router from CompanionActor capabilities. */
    public static CompletionStage<ProactiveAction.Initiative> synthesize(
            OraclePrediction prediction,
            String context,
            ActorContext<?> ctx,
            ActorRef<InferenceRouter.Command> router,
            M2PlanScorer scorer) {
        return synthesize(prediction, context, router,
            ctx == null ? null : ctx.getSystem().scheduler(), scorer);
    }

    /**
     * Render the action template into a concrete plan list, substituting prediction
     * facts into action arguments where useful. Kept simple — the model still has
     * full latitude inside ReAct; this is just the seed plan M2 sees.
     */
    private static List<String> renderPlan(List<String> actions, OraclePrediction p) {
        var topic = extractTopic(p);
        var rendered = new ArrayList<String>(actions.size());
        for (var a : actions) {
            rendered.add(switch (a) {
                case "library_search" -> "library_search('" + topic + "')";
                case "recall"         -> "recall('" + topic + "')";
                case "query_oracle"   -> "query_oracle('" + p.category() + "')";
                case "tell_agent"     -> "tell_agent(bondholder, findings)";
                case "summarize"      -> "summarize";
                case "goal_done"      -> "goal_done";
                case "introspect"     -> "introspect";
                default               -> a;
            });
        }
        return rendered;
    }

    /** Pull a topic phrase from the prediction text — falls back to category. */
    private static String extractTopic(OraclePrediction p) {
        var text = p.text() == null ? "" : p.text().trim();
        if (text.isEmpty()) return p.category();
        // Trim quotes and clamp length so the prompt stays compact.
        var clean = text.replaceAll("[\"']", "");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean;
    }

    private static ProactiveAction.Initiative buildInitiative(
            OraclePrediction prediction, List<String> plan) {
        var actionJson = serializePlan(prediction, plan);
        var description = "Anticipating " + prediction.category() + ": " + prediction.text();
        // Initiative carries no drive name; map to vigilance as the anticipatory drive.
        return new ProactiveAction.Initiative(actionJson, "vigilance", description);
    }

    /**
     * Serialize the plan as a JSON object the proactive-execution path can read.
     * Schema: {predictionId, category, confidence, actions: [...]}.
     */
    private static String serializePlan(OraclePrediction p, List<String> actions) {
        var sb = new StringBuilder("{");
        sb.append("\"predictionId\":\"").append(escape(p.id())).append("\",");
        sb.append("\"category\":\"").append(escape(p.category())).append("\",");
        sb.append("\"confidence\":").append(p.confidence()).append(",");
        sb.append("\"actions\":[");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(actions.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
