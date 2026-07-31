package org.wyrdsekai.core.recipe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #1142 — the safety core of the {@code tune-recipe-params} loop.
 *
 * <p>The loop reads a recipe's outcome history and nudges <em>soft</em> param
 * defaults (timeouts, lookback windows, advisory thresholds) into the
 * {@link SqlRecipeParamOverrides} store so future runs use the tuned value. The
 * single invariant that makes this safe is enforced here: a param referenced by
 * a <b>PERMANENT</b> welfare gate condition (OPEN-R4, #1013) is a load-bearing
 * floor and may NEVER be auto-tuned. Everything else is bounded by an explicit
 * {@code [min,max]} the caller supplies.</p>
 *
 * <p>Pure logic — no I/O. The REST apply endpoint and the recipe script both
 * call {@link #validateNudge} before any write, so the floor-protection holds
 * regardless of who proposes the nudge.</p>
 */
public final class RecipeParamTuner {

    private RecipeParamTuner() {}

    /** Matches a {@code {{param}}} reference in a gate condition. */
    private static final Pattern PARAM_REF =
        Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /**
     * Params referenced by any PERMANENT welfare gate's condition — the
     * untouchable floors. {@code {{x}} <= y} style conditions: every {@code {{ }}}
     * token is collected.
     */
    public static Set<String> floorProtectedParams(RecipeManifest m) {
        var out = new LinkedHashSet<String>();
        if (m == null) return out;
        for (var step : m.stepsOfKind(StepKind.GATE)) {
            if (step instanceof RecipeStep.Gate g
                    && g.isPermanentWelfare() && g.condition() != null) {
                Matcher mt = PARAM_REF.matcher(g.condition());
                while (mt.find()) out.add(mt.group(1));
            }
        }
        return out;
    }

    public enum Refusal { NONE, FLOOR_PROTECTED, OUT_OF_BOUNDS, BAD_BOUNDS, UNKNOWN_PARAM }

    public record Decision(boolean allow, Refusal refusal, String detail) {
        public static Decision allowed() { return new Decision(true, Refusal.NONE, null); }
        public static Decision deny(Refusal r, String d) { return new Decision(false, r, d); }
    }

    /**
     * May a nudge of {@code param} to {@code value} (declared bounds {@code [min,max]})
     * be applied to {@code manifest}? Refuses, in order: unknown param,
     * floor-protected param (referenced by a PERMANENT gate), inverted bounds,
     * out-of-bounds value.
     */
    public static Decision validateNudge(RecipeManifest manifest, String param,
                                         double value, double min, double max) {
        if (manifest == null || param == null || param.isBlank())
            return Decision.deny(Refusal.UNKNOWN_PARAM, "null manifest/param");
        if (manifest.params() == null || !manifest.params().containsKey(param))
            return Decision.deny(Refusal.UNKNOWN_PARAM, "no such recipe param: " + param);
        if (floorProtectedParams(manifest).contains(param))
            return Decision.deny(Refusal.FLOOR_PROTECTED,
                "param '" + param + "' is referenced by a PERMANENT welfare gate (a floor)");
        if (min > max)
            return Decision.deny(Refusal.BAD_BOUNDS, "min " + min + " > max " + max);
        if (value < min || value > max)
            return Decision.deny(Refusal.OUT_OF_BOUNDS,
                value + " outside [" + min + "," + max + "]");
        return Decision.allowed();
    }

    /** Outcome stats over a window of terminal queue rows. */
    public record OutcomeStats(int total, int succeeded, int failed, int gateFailed) {
        public double failRate() { return total == 0 ? 0.0 : (double) failed / total; }
    }

    /**
     * Reconstruct outcome stats from queue history (terminal rows only). The
     * queue persists only an unstructured terminal {@code message}, so gate
     * failures are heuristically detected by the substring {@code "gate"} —
     * approximate, but enough to drive a soft nudge.
     */
    public static OutcomeStats statsFrom(List<QueuedRecipe> rows) {
        int total = 0, ok = 0, fail = 0, gate = 0;
        if (rows != null) {
            for (var r : rows) {
                if (r == null || r.status() == null) continue;
                if (r.status() == QueuedRecipe.Status.SUCCEEDED) { total++; ok++; }
                else if (r.status() == QueuedRecipe.Status.FAILED) {
                    total++; fail++;
                    var msg = r.message();
                    if (msg != null && msg.toLowerCase().contains("gate")) gate++;
                }
            }
        }
        return new OutcomeStats(total, ok, fail, gate);
    }
}
