package org.wyrdsekai.core.agent.affordance;

import org.wyrdsekai.core.agent.ActionPolicy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * the {@code tune-tool-affordance} recipe's engine.
 * Reuses the canonical runtime logic ({@link ToolAffordanceLog} → {@link ToolFitReport}
 * → {@link ToolAffordanceTuner} → {@link ToolAffordanceStore}) so the batch self-
 * improvement job and the in-process instrument never drift. No new logic here — just
 * the observe→act wiring the agent runs on itself.
 *
 * <p>RELEVANCE only (§4): it upserts {@code tool_affordance} weights; it cannot touch
 * permission. Bounded by {@link ToolAffordanceTuner.Bounds}, so a loop firing every
 * cadence asymptotes rather than runaway-biasing.</p>
 *
 * <pre>args: --jdbc &lt;url&gt; [--agent &lt;did&gt;] [--limit N] [--step s] [--max m] [--min-obs k] [--apply true|false]</pre>
 * Prints a single-line JSON summary for the recipe report step.
 */
public final class ToolAffordanceTuneMain {

    private ToolAffordanceTuneMain() {}

    public static void main(String[] args) {
        var a = parse(args);
        String jdbc = a.get("jdbc");
        if (jdbc == null || jdbc.isBlank()) {
            System.out.println("{\"ok\":false,\"error\":\"missing --jdbc\"}");
            return;
        }
        String agent = a.getOrDefault("agent", "");
        int limit = intArg(a, "limit", 500);
        var bounds = new ToolAffordanceTuner.Bounds(
            dblArg(a, "step", 0.2), dblArg(a, "max", 2.0), intArg(a, "min-obs", 2));
        boolean apply = !"false".equalsIgnoreCase(a.getOrDefault("apply", "true"));

        var log = new ToolAffordanceLog(jdbc);
        var store = new ToolAffordanceStore(jdbc);

        var rows = log.recent(agent.isBlank() ? null : agent, limit);
        var fit = ToolFitReport.compute(rows);
        var proposals = ToolAffordanceTuner.tune(fit.mismatches(),
            n -> store.resolve(n, ActionPolicy.domainFor(n)), bounds);

        int applied = 0;
        if (apply) {
            var now = Instant.now();
            for (var p : proposals) { store.upsert(p, now); applied++; }
        }

        var sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"scanned\":").append(rows.size());
        sb.append(",\"passes_with_want\":").append(fit.passesWithWant());
        sb.append(",\"surfaced_fraction\":").append(round(fit.surfacedFraction()));
        sb.append(",\"emitted_fraction\":").append(round(fit.emittedFraction()));
        sb.append(",\"mismatches\":").append(fit.mismatches().size());
        sb.append(",\"proposed\":").append(proposals.size());
        sb.append(",\"applied\":").append(applied);
        sb.append(",\"tuned\":[");
        for (int i = 0; i < proposals.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(proposals.get(i).toolName()).append('"');
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].startsWith("--")) { m.put(args[i].substring(2), args[i + 1]); i++; }
        }
        return m;
    }

    private static int intArg(Map<String, String> a, String k, int d) {
        try { return a.containsKey(k) ? Integer.parseInt(a.get(k)) : d; } catch (Exception e) { return d; }
    }

    private static double dblArg(Map<String, String> a, String k, double d) {
        try { return a.containsKey(k) ? Double.parseDouble(a.get(k)) : d; } catch (Exception e) { return d; }
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
