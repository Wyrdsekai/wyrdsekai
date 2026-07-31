package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PredictionPlanSynthesizer}. The router/scorer-driven
 * path runs in actor-system tests; here we cover the deterministic glue
 * (template selection, plan rendering, no-router shortcut, non-actionable skip).
 */
class PredictionPlanSynthesizerTest {

    @Test
    void non_actionable_prediction_returns_null() throws Exception {
        var p = new OraclePrediction("p", "weather is fine", "topic", 0.5, "k", "e",
            /*actionable*/ false);
        var out = PredictionPlanSynthesizer.synthesize(p, "ctx",
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(out).isNull();
    }

    @Test
    void no_router_or_scorer_skips_M2_and_returns_initiative() throws Exception {
        var p = new OraclePrediction("p1", "User asks about gardening at 9am",
            "temporal", 0.8, "k", "e", true);
        var out = PredictionPlanSynthesizer.synthesize(p, "ctx",
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(out).isNotNull();
        assertThat(out).isInstanceOf(ProactiveAction.Initiative.class);
        var init = (ProactiveAction.Initiative) out;
        // Initiative description carries the trigger
        assertThat(init.description()).contains("temporal");
        assertThat(init.description()).contains("gardening");
        // ActionJSON serializes a plan
        assertThat(init.actionJson()).contains("\"predictionId\":\"p1\"");
        assertThat(init.actionJson()).contains("\"category\":\"temporal\"");
        assertThat(init.actionJson()).contains("library_search");
        assertThat(init.actionJson()).contains("tell_agent");
        assertThat(init.actionJson()).contains("goal_done");
    }

    @Test
    void anomaly_uses_query_oracle_template() throws Exception {
        var p = new OraclePrediction("a1", "Sleep pattern shifted",
            "anomaly", 0.7, "k", "e", true);
        var out = PredictionPlanSynthesizer.synthesize(p, null,
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(out).isNotNull();
        var init = (ProactiveAction.Initiative) out;
        assertThat(init.actionJson()).contains("query_oracle");
        assertThat(init.actionJson()).contains("\"category\":\"anomaly\"");
    }

    @Test
    void unknown_category_falls_back_to_default_template() throws Exception {
        var p = new OraclePrediction("u1", "Mystery prediction",
            "totally-unknown-category", 0.6, "k", "e", true);
        var out = PredictionPlanSynthesizer.synthesize(p, null,
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertThat(out).isNotNull();
        var init = (ProactiveAction.Initiative) out;
        // DEFAULT_TEMPLATE is just tell_agent + goal_done
        assertThat(init.actionJson()).contains("tell_agent");
        assertThat(init.actionJson()).contains("goal_done");
    }

    @Test
    void initiative_drive_is_vigilance() throws Exception {
        var p = new OraclePrediction("v1", "in 1 hour", "anticipation", 0.7,
            "k", "e", true);
        var out = PredictionPlanSynthesizer.synthesize(p, null,
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        var init = (ProactiveAction.Initiative) out;
        assertThat(init.driveName()).isEqualTo("vigilance");
    }

    @Test
    void null_calibrator_overload_falls_back_to_default_threshold() throws Exception {
        var p = new OraclePrediction("c1", "User checks calendar at 3pm",
            "temporal", 0.7, "k", "e", true);
        // Calibrator-aware overload, no scorer/router → still returns Initiative.
        // Confirms the new parameter doesn't break the no-router shortcut path.
        var out = PredictionPlanSynthesizer.synthesize(p, null,
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null,
            null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertThat(out).isNotNull();
        assertThat(out).isInstanceOf(ProactiveAction.Initiative.class);
    }

    @Test
    void plan_topic_substitution_clamps_long_text() throws Exception {
        var longText = "x".repeat(200);
        var p = new OraclePrediction("long", longText, "topic", 0.5, "k", "e", true);
        var out = PredictionPlanSynthesizer.synthesize(p, null,
            (ActorRef<InferenceRouter.Command>) null,
            (Scheduler) null, null)
            .toCompletableFuture().get(2, TimeUnit.SECONDS);

        var init = (ProactiveAction.Initiative) out;
        // library_search arg should be clamped to <=60 chars of the topic
        assertThat(init.actionJson()).contains("library_search");
        assertThat(init.actionJson().length()).isLessThan(longText.length() + 200);
    }
}
