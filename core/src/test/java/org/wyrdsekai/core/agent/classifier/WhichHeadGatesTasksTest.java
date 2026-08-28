package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * Which head should decide whether a turn gets tools?
 *
 * <p>CompanionActor gates the tool layer on TASK_PRESENT alone. That head was
 * trained on generic assistant tasks (0 world-building examples in 863) with
 * {@code actionable} skewed short (median 6 words) and {@code none} long
 * (median 11) — so it learned length and register. REQUEST_TYPE, already
 * classified on the same turn for affect_present, appeared to separate these
 * cases far better in spot checks.</p>
 *
 * <p>This measures it rather than arguing it: precision / recall / F1 for each
 * candidate gate over a labelled set, plus a per-kind miss breakdown so the
 * REMAINING gap is visible per capability.</p>
 *
 * <p><b>Limitation, stated plainly:</b> the eval labels are hand-authored by
 * whoever writes the file, so they encode a view of where "task" begins. That
 * boundary is the thing to argue about; the numbers only tell you which head
 * tracks a given boundary better.</p>
 */
@Tag("integration")
@Tag("needs-classifier")
class WhichHeadGatesTasksTest {

    private static ClassifierArm arm;
    private record Row(boolean task, String kind, String text) {}

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:which-head-gates-tasks");
    }

    /** REQUEST_TYPE labels that mean "the person wants something done". */
    private static final Set<String> DOING = Set.of("delegate", "action", "tell_someone", "write");
    private static final Set<String> DOING_NARROW = Set.of("delegate", "action", "tell_someone");

    @Test void compare_candidate_gates() throws Exception {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        if (arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door").label() == null)
            Assumptions.abort("no task_present head");

        var evalPath = Path.of("/tmp/headeval/eval.jsonl");
        if (!Files.exists(evalPath)) Assumptions.abort("eval set not present at " + evalPath);
        var mapper = new ObjectMapper();
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(evalPath)) {
            if (line.isBlank()) continue;
            var n = mapper.readTree(line);
            rows.add(new Row(n.get("task").asBoolean(), n.get("kind").asText(), n.get("text").asText()));
        }

        record Strategy(String name, BiPredicate<Classification, Classification> gate) {}
        var strategies = List.of(
            new Strategy("task_present >=0.50 (SHIPPED)",
                (t, r) -> "actionable".equals(t.label()) && t.confidence() >= 0.5),
            new Strategy("task_present, voice only on confident none (MY GATE FIX)",
                (t, r) -> !("none".equals(t.label()) && t.confidence() >= 0.75)),
            new Strategy("request_type in {delegate,action,tell_someone}",
                (t, r) -> DOING_NARROW.contains(r.label())),
            new Strategy("request_type in {delegate,action,tell_someone,write}",
                (t, r) -> DOING.contains(r.label())),
            new Strategy("task_present OR request_type-doing",
                (t, r) -> ("actionable".equals(t.label()) && t.confidence() >= 0.5)
                          || DOING_NARROW.contains(r.label())),
            new Strategy("BOTH fixes: confident-none gate OR request_type-doing",
                (t, r) -> !("none".equals(t.label()) && t.confidence() >= 0.75)
                          || DOING_NARROW.contains(r.label()))
        );

        // classify once, reuse
        var tp = new ArrayList<Classification>();
        var rt = new ArrayList<Classification>();
        for (var row : rows) {
            tp.add(arm.classify(ClassifierHead.TASK_PRESENT, row.text()));
            rt.add(arm.classify(ClassifierHead.REQUEST_TYPE, row.text()));
        }

        System.out.printf("%n=== %d examples (%d task / %d non-task) ===%n",
            rows.size(), rows.stream().filter(Row::task).count(),
            rows.stream().filter(r -> !r.task()).count());
        System.out.printf("%-56s %6s %6s %6s %8s %8s%n",
            "gate strategy", "prec", "recall", "F1", "missed", "false+");

        for (var s : strategies) {
            int tpc = 0, fp = 0, fn = 0;
            var missedKinds = new TreeMap<String, Integer>();
            for (int i = 0; i < rows.size(); i++) {
                boolean gets = s.gate().test(tp.get(i), rt.get(i));
                if (rows.get(i).task() && gets) tpc++;
                else if (!rows.get(i).task() && gets) fp++;
                else if (rows.get(i).task() && !gets) {
                    fn++;
                    missedKinds.merge(rows.get(i).kind(), 1, Integer::sum);
                }
            }
            double prec = tpc + fp == 0 ? 0 : (double) tpc / (tpc + fp);
            double rec  = tpc + fn == 0 ? 0 : (double) tpc / (tpc + fn);
            double f1   = prec + rec == 0 ? 0 : 2 * prec * rec / (prec + rec);
            System.out.printf("%-56s %6.3f %6.3f %6.3f %8d %8d   %s%n",
                s.name(), prec, rec, f1, fn, fp, missedKinds);
        }
        System.out.println("\n  missed = a real request that gets NO tools (capability lost)");
        System.out.println("  false+ = conversation routed to the full tier (costs tokens only)");
    }

    /**
     * How often does each REQUEST_TYPE label actually fire?
     *
     * <p>Matters because the labels are NOT interchangeable downstream:
     * {@code delegate} and {@code write} are bunshin DISPATCH labels
     * (CompanionActor.onAgentMessage:23254, inferAutoPlanGoals:27773), so
     * reusing {@code delegate} as a "give her tools" signal conflates "do this
     * yourself" with "hand this to a subagent". {@code action} would be the
     * semantically safe label — if it ever fires.</p>
     */
    @Test void label_frequency_and_which_are_safe_to_reuse() throws Exception {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        if (arm.classify(ClassifierHead.REQUEST_TYPE, "please fix the door").label() == null)
            Assumptions.abort("no request_type head");
        var evalPath = Path.of("/tmp/headeval/eval.jsonl");
        if (!Files.exists(evalPath)) Assumptions.abort("eval set missing");
        var mapper = new ObjectMapper();
        var counts = new TreeMap<String, int[]>();  // label -> [onTask, onNonTask]
        for (var line : Files.readAllLines(evalPath)) {
            if (line.isBlank()) continue;
            var n = mapper.readTree(line);
            boolean task = n.get("task").asBoolean();
            var c = arm.classify(ClassifierHead.REQUEST_TYPE, n.get("text").asText());
            var arr = counts.computeIfAbsent(c.label() == null ? "<null>" : c.label(), k -> new int[2]);
            arr[task ? 0 : 1]++;
        }
        System.out.println("\n=== REQUEST_TYPE label frequency over the eval set ===");
        System.out.printf("  %-14s %8s %10s   %s%n", "label", "on task", "on chat", "safe to reuse as a task signal?");
        for (var e : counts.entrySet()) {
            String safety = switch (e.getKey()) {
                case "delegate", "write" -> "NO — bunshin dispatch label";
                case "action", "tell_someone" -> "yes — no dispatch consumer";
                default -> "n/a (not a task label)";
            };
            System.out.printf("  %-14s %8d %10d   %s%n", e.getKey(), e.getValue()[0], e.getValue()[1], safety);
        }
    }
}
