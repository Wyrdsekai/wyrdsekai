package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.agent.interiority.ChronicleEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Track-C C5 — pure-logic synthesizer that turns a batch of
 * completed recipe runs into typed {@link ChronicleEntry} rows the
 * bondholder Study Chronicle furnishing renders alongside the narrative
 * testimony.
 *
 * <p>Called from the sleep pass right after
 * {@link RecipeForgeIngester#ingest} — same input, different output.
 * The Forge produces DEXTERITY soul fragments (private to the
 * companion); this produces visible-to-bondholder chronicle entries.
 * Decoupled because the two outputs serve different audiences but key
 * off the same per-batch event.</p>
 *
 * <p>Per-entry shape ({@link ChronicleEntry#data()} keys):</p>
 * <ul>
 *   <li>{@code recipeId} — name of the recipe.</li>
 *   <li>{@code status} — terminal status ({@code SUCCESS / GATE_FAILED /
 *       STEP_FAILED / ERROR / NEEDS_BACKEND}).</li>
 *   <li>{@code triggerSource} / {@code triggerReason} — who put this
 *       row in the queue (CRON / GAP / AGENT / STEWARD) + the reason
 *       string.</li>
 *   <li>{@code primaryMetric} / {@code primaryMetricValue} — first
 *       headline metric found in run context ({@code val_accuracy} /
 *       {@code accuracy}). {@code null} if none.</li>
 *   <li>{@code gatesPassed} / {@code gatesTotal} — gate-step outcomes.</li>
 *   <li>{@code deployed} — true if recipe declared deploys + actually
 *       deployed (i.e. reached deploy step without rollback).</li>
 *   <li>{@code rolledBack} — true if a rollback outcome appeared.</li>
 *   <li>{@code cadenceTier} — the row's cadence tier at the time of
 *       completion (caller passes the post-{@link CadenceLadder} new
 *       tier).</li>
 *   <li>{@code nextFireEstimate} — instant the next fire is permitted
 *       by cooldown gate ({@code completed_at + tier.period}).</li>
 * </ul>
 *
 * <p>The {@code summary} field is a one-line human-legible rendering
 * for surfaces that don't want to consult the data map.</p>
 */
public final class RecipeChronicleSynthesizer {

    private RecipeChronicleSynthesizer() {}

    /** One row from the scheduler's completion handler. */
    public record SynthInput(
            RecipeForgeIngester.CompletedRun completed,
            QueuedRecipe.TriggerSource triggerSource,
            String triggerReason,
            CadenceTier postRunTier) {

        public SynthInput {
            if (completed == null) throw new IllegalArgumentException("completed required");
        }
    }

    /** Synthesize per-batch — one {@link ChronicleEntry} per completed run. */
    public static List<ChronicleEntry> synthesize(String agentDid,
            List<SynthInput> batch, Instant now) {
        if (agentDid == null || batch == null || batch.isEmpty()) return List.of();
        var out = new ArrayList<ChronicleEntry>(batch.size());
        for (var item : batch) {
            out.add(toEntry(agentDid, item, now == null ? Instant.now() : now));
        }
        return out;
    }

    /** Synthesize from a single completed run; convenience for tests + C5 hook. */
    public static ChronicleEntry synthesize(String agentDid, SynthInput input, Instant now) {
        return toEntry(agentDid, input, now == null ? Instant.now() : now);
    }

    private static ChronicleEntry toEntry(String agentDid, SynthInput input, Instant now) {
        var cr = input.completed();
        var run = cr.run();
        var data = new LinkedHashMap<String, Object>();
        data.put("recipeId", cr.recipeName());
        data.put("status", run.status().name());

        var src = input.triggerSource() == null
            ? QueuedRecipe.TriggerSource.AGENT
            : input.triggerSource();
        data.put("triggerSource", src.name());
        if (input.triggerReason() != null) {
            data.put("triggerReason", input.triggerReason());
        }

        // Headline metric — same lookup as RecipeForgeIngester.metricSuffix
        // (val_accuracy then accuracy).
        var ctx = run.context();
        if (ctx != null) {
            for (String key : List.of("val_accuracy", "accuracy")) {
                if (ctx.has(key) && ctx.get(key) instanceof Number n) {
                    data.put("primaryMetric", key);
                    data.put("primaryMetricValue", n.doubleValue());
                    break;
                }
            }
        }

        // Gate outcomes.
        if (run.outcomes() != null) {
            int gatesTotal = 0, gatesPassed = 0;
            boolean rolledBack = false;
            boolean deployed = false;
            for (var o : run.outcomes()) {
                if (o.kind() == StepKind.GATE) {
                    gatesTotal++;
                    if (o.ok()) gatesPassed++;
                }
                if ("rollback".equals(o.id())) rolledBack = true;
                if ("deploy".equals(o.id()) && o.ok()) deployed = true;
            }
            data.put("gatesPassed", gatesPassed);
            data.put("gatesTotal", gatesTotal);
            data.put("rolledBack", rolledBack);
            // "Actually deployed" = recipe declares deploys AND deploy step
            // succeeded AND no rollback fired afterward.
            data.put("deployed", cr.deploys() && deployed && !rolledBack);
        }

        var tier = input.postRunTier() == null
            ? CadenceTier.WARMUP : input.postRunTier();
        data.put("cadenceTier", tier.name());
        data.put("nextFireEstimate", now.plus(tier.period()).toString());

        return new ChronicleEntry(agentDid, now,
            ChronicleEntry.Kind.RECIPE_RUN, oneLineSummary(cr, tier), data);
    }

    /** Bondholder-facing one-liner. Mirrors the Forge narrative voice. */
    private static String oneLineSummary(RecipeForgeIngester.CompletedRun cr,
            CadenceTier postRunTier) {
        var status = cr.run().status();
        var metric = extractMetric(cr.run().context());
        var name = cr.recipeName();
        var tier = postRunTier == null ? CadenceTier.WARMUP : postRunTier;
        return switch (status) {
            case SUCCESS -> "Ran `" + name + "` and it succeeded"
                + metric.map(v -> " (val_accuracy " + format4(v) + ")").orElse("")
                + " — cadence " + tier + ".";
            case GATE_FAILED -> "Ran `" + name + "` and a gate blocked deploy"
                + metric.map(v -> " (val_accuracy " + format4(v) + ")").orElse("")
                + ". Cadence reset to " + tier + ".";
            case STEP_FAILED -> "Ran `" + name + "` and a step failed; "
                + "any deploy rolled back. Cadence " + tier + ".";
            case NEEDS_BACKEND -> "Tried `" + name + "` but a step needed a "
                + "coding backend that wasn't wired.";
            case RESOURCE_DENIED -> "Held off `" + name + "` — this node can't meet its "
                + "resource needs; asking rather than thrashing.";
            case ERROR -> "Ran `" + name + "` and it errored — recipe needs a look.";
        };
    }

    private static Optional<Double> extractMetric(RecipeContext ctx) {
        if (ctx == null) return Optional.empty();
        for (String key : List.of("val_accuracy", "accuracy")) {
            if (ctx.has(key) && ctx.get(key) instanceof Number n) {
                return Optional.of(n.doubleValue());
            }
        }
        return Optional.empty();
    }

    private static String format4(double v) {
        return String.format("%.4f", v);
    }
}
