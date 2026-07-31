package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.soul.SoulFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * turns completed {@link RecipeRunner.RecipeRun}s into
 * {@link org.wyrdsekai.core.soul.FragmentKind#DEXTERITY} soul-fragments so a familiar
 * that runs a governed recipe accrues procedural self-knowledge from the outcome
 * ("I retrained the routing head and it cleared the gate"; "the deploy failed its
 * smoke check and rolled back").
 *
 * <p>This mirrors {@link org.wyrdsekai.core.familiar.FamiliarForgeIngester}: a pure
 * function {@code input state → Result}, no side-effects. The caller (the sleep-pass
 * consolidation, wired alongside the P5 backend dispatch when recipe runs flow through
 * an agent) merges {@link Result#newFragments()} into the manifest and persists.</p>
 *
 * <h2>What gets ingested</h2>
 * <ul>
 *   <li>Every completed run yields one DEXTERITY fragment narrating the outcome —
 *       successes <em>and</em> failures (failure is informative; honesty mirrors the
 *       familiar ingester's high-failure-form fragment).</li>
 *   <li>Only runs that reached {@link RecipeRunner.Status#SUCCESS} contribute a
 *       first-person training-corpus line (weight what worked).</li>
 *   <li>≥{@link #HABIT_THRESHOLD} runs in a batch adds an identity fragment — the
 *       familiar is becoming someone who drives its own procedures.</li>
 * </ul>
 */
public final class RecipeForgeIngester {

    /** Runs-in-a-batch at/above which an identity ("I drive my own recipes") fragment is emitted. */
    public static final int HABIT_THRESHOLD = 3;

    private RecipeForgeIngester() {}

    /**
     * One completed recipe run, tagged with the recipe name (the {@link RecipeRunner.RecipeRun}
     * itself does not carry it) and whether the recipe declared a production deploy.
     */
    public record CompletedRun(String recipeName, boolean deploys, RecipeRunner.RecipeRun run) {
        public CompletedRun {
            if (recipeName == null || recipeName.isBlank()) {
                throw new IllegalArgumentException("recipeName required");
            }
            if (run == null) throw new IllegalArgumentException("run required");
        }
    }

    /** Input bundle — stateless; callers assemble a fresh batch per consolidation pass. */
    public record Batch(String agentDid, List<CompletedRun> runs) {
        public Batch {
            if (agentDid == null || agentDid.isBlank()) {
                throw new IllegalArgumentException("agentDid required");
            }
            runs = runs == null ? List.of() : List.copyOf(runs);
        }
        public boolean isEmpty() { return runs.isEmpty(); }
    }

    /**
     * Output bundle.
     *
     * @param newFragments DEXTERITY fragments for the Forge to integrate
     * @param corpusEntries first-person narrative lines for the next training cycle
     */
    public record Result(List<SoulFragment> newFragments, List<String> corpusEntries) {
        public Result {
            newFragments = newFragments == null ? List.of() : List.copyOf(newFragments);
            corpusEntries = corpusEntries == null ? List.of() : List.copyOf(corpusEntries);
        }
        public boolean isEmpty() { return newFragments.isEmpty() && corpusEntries.isEmpty(); }
    }

    /** Run the consolidation pass. */
    public static Result ingest(Batch batch) {
        if (batch == null || batch.isEmpty()) {
            return new Result(List.of(), List.of());
        }

        var fragments = new ArrayList<SoulFragment>();
        var corpus = new ArrayList<String>();

        for (var cr : batch.runs()) {
            fragments.add(runFragment(cr));
            if (cr.run().status() == RecipeRunner.Status.SUCCESS) {
                corpus.add(corpusEntry(cr));
            }
        }

        if (batch.runs().size() >= HABIT_THRESHOLD) {
            long succeeded = batch.runs().stream()
                    .filter(c -> c.run().status() == RecipeRunner.Status.SUCCESS).count();
            fragments.add(SoulFragment.dexterity(
                    fragmentId("recipe-driver-identity"),
                    "procedure",
                    "Recipe-driver habit",
                    "I have run " + batch.runs().size() + " governed recipes recently ("
                        + succeeded + " all the way to success). I can drive my own procedures — "
                        + "the gates and the reversible deploy are mine to lean on, not someone else's."));
        }

        return new Result(fragments, corpus);
    }

    // ── fragment + corpus builders ──────────────────────────────────────────

    private static SoulFragment runFragment(CompletedRun cr) {
        return SoulFragment.dexterity(
                fragmentId("recipe-" + slug(cr.recipeName())),
                "procedure",
                "Recipe run: " + cr.recipeName(),
                narrative(cr));
    }

    /** First-person account of what happened — the DEXTERITY learning. */
    private static String narrative(CompletedRun cr) {
        var run = cr.run();
        int gatesPassed = (int) run.outcomes().stream()
                .filter(o -> o.kind() == StepKind.GATE && o.ok()).count();
        int gatesTotal = (int) run.outcomes().stream()
                .filter(o -> o.kind() == StepKind.GATE).count();
        String metric = metricSuffix(run);

        return switch (run.status()) {
            case SUCCESS -> "I ran the recipe `" + cr.recipeName() + "` end to end and it succeeded. "
                    + gateClause(gatesPassed, gatesTotal)
                    + (cr.deploys() ? "The new artifact passed every gate and was deployed. " : "")
                    + metric
                    + "This is a procedure I can run again.";
            case GATE_FAILED -> "I ran `" + cr.recipeName() + "` but it stopped at a gate ("
                    + firstFailedGate(run) + "). " + gateClause(gatesPassed, gatesTotal)
                    + "Nothing was deployed — the welfare floor held. " + metric
                    + "If I want this to land, I have to change something upstream of the gate, not the gate.";
            case STEP_FAILED -> "I ran `" + cr.recipeName() + "` and a step failed"
                    + (rolledBack(run) ? " after deploy, so the change was rolled back automatically. "
                                       : ". ")
                    + reverseClause(run) + metric
                    + "The run is reversible by design, so the world is back to its prior state.";
            case NEEDS_BACKEND -> "I tried to run `" + cr.recipeName() + "` but a step needed a coding "
                    + "backend that wasn't wired yet. I couldn't complete it on my own this time.";
            case RESOURCE_DENIED -> "I wanted to run `" + cr.recipeName() + "` but this node can't meet what it "
                    + "needs (" + truncate(run.message(), 140) + "). I didn't force it — I'd rather ask for the "
                    + "resource than thrash a job that can't finish here.";
            case ERROR -> "I ran `" + cr.recipeName() + "` and it errored out (" + truncate(run.message(), 120)
                    + "). I should look at the recipe itself before trying again.";
        };
    }

    private static String corpusEntry(CompletedRun cr) {
        String metric = metricSuffix(cr.run()).trim();
        return "I ran the `" + cr.recipeName() + "` recipe and it completed cleanly"
                + (cr.deploys() ? ", deploying the result through its gates" : "")
                + (metric.isEmpty() ? "." : " — " + metric);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String gateClause(int passed, int total) {
        if (total == 0) return "";
        return passed + " of " + total + " gate" + (total == 1 ? "" : "s") + " passed. ";
    }

    private static String reverseClause(RecipeRunner.RecipeRun run) {
        var failed = run.outcomes().stream()
                .filter(o -> !o.ok() && o.kind() != StepKind.GATE)
                .map(RecipeRunner.StepOutcome::id)
                .findFirst().orElse(null);
        return failed == null ? "" : "The step that failed was `" + failed + "`. ";
    }

    private static boolean rolledBack(RecipeRunner.RecipeRun run) {
        return run.outcomes().stream().anyMatch(o -> o.id().equals("rollback"));
    }

    private static String firstFailedGate(RecipeRunner.RecipeRun run) {
        return run.outcomes().stream()
                .filter(o -> o.kind() == StepKind.GATE && !o.ok())
                .map(RecipeRunner.StepOutcome::id)
                .findFirst().orElse("a gate");
    }

    /** Surface the recipe's headline metric if it left one on the context (e.g. val_accuracy). */
    private static String metricSuffix(RecipeRunner.RecipeRun run) {
        var ctx = run.context();
        for (String key : List.of("val_accuracy", "accuracy")) {
            if (ctx.has(key) && ctx.get(key) instanceof Number n) {
                return "The headline metric " + key + " came out at "
                        + String.format("%.4f", n.doubleValue()) + ". ";
            }
        }
        return "";
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String fragmentId(String slug) {
        return "recipe-forge-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
