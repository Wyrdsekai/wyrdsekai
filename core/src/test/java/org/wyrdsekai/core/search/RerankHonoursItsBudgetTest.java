package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A stated budget that cannot stop anything is not a budget.
 *
 * <p>{@code RERANK_BUDGET_MS = 2_500} was written when the rerank embedded one
 * candidate per call and the deadline was tested each time round the loop.
 * Batching — the correct fix for per-call overhead — collapsed that into a single
 * {@code embedBatch}, and the check became one test taken <em>before</em>
 * starting. After that nothing could stop it.</p>
 *
 * <p>Measured on the live household, 2026-08-08: 64 candidates took ~48s against
 * the declared 2.5s, roughly <b>20x over</b>. That is not merely slow — it
 * changes the outcome. The ReAct loop gave up at ~37s and spoke; the passages
 * landed 23 seconds later with nobody waiting:</p>
 *
 * <pre>
 * 14:20:52  ReAct loop ended at step 5 (model spoke text)
 * 14:21:15  searchKnowledge → 20 results (10 pack, 10 study)
 * </pre>
 *
 * <p>A tool that answers after the conversation has moved on has not answered.</p>
 */
class RerankHonoursItsBudgetTest {

    private static String source() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/search/WyrdLuceneStore.java";
        var fromCore = Paths.get("..", rel);
        var p = Files.exists(fromCore)
            ? fromCore : Paths.get(rel);
        return Files.readString(p);
    }

    /** THE regression: one pre-check around an unbounded call. */
    @Test
    void the_whole_candidate_set_is_no_longer_embedded_in_one_unbounded_call() throws Exception {
        assertThat(source())
            .as("a single embedBatch over every body cannot be interrupted")
            .doesNotContain("var vecs = svc.embedBatch(bodies);");
    }

    /** The deadline must be tested inside the loop, not only before it. */
    @Test
    void the_deadline_is_checked_between_slices() throws Exception {
        var src = source();
        int loop = src.indexOf("for (int start = 0; start < bodies.size(); start += SLICE)");

        assertThat(loop).as("the batch must be sliced").isGreaterThan(0);
        var body = src.substring(loop, Math.min(src.length(), loop + 900));
        assertThat(body)
            .as("each slice must re-test the budget")
            .contains("System.nanoTime() >= deadline");
        assertThat(body)
            .as("and stop when it is spent")
            .contains("break");
    }

    /** Slices must still be batched — reverting to per-candidate would undo the 2→64 fix. */
    @Test
    void slices_are_still_batched_not_one_call_per_candidate() throws Exception {
        var src = source();

        assertThat(src)
            .as("per-call overhead dominates; the slice must go through embedBatch")
            .contains("svc.embedBatch(bodies.subList(start, end))");
        int loop = src.indexOf("final int SLICE = 16;");
        assertThat(loop).as("a slice size worth batching").isGreaterThan(0);
    }

    /** Running out of budget must degrade, never throw or empty the result. */
    @Test
    void an_exhausted_budget_leaves_the_remainder_in_bm25_order() throws Exception {
        var src = source();
        int loop = src.indexOf("for (int start = 0; start < bodies.size(); start += SLICE)");
        var body = src.substring(loop, Math.min(src.length(), loop + 900));

        assertThat(body)
            .as("the operator needs to know the budget bit, and how far it got")
            .contains("budget spent after");
        assertThat(body).contains("keep BM25 order");
    }

    /** The budget itself must stay a named constant, not become a literal. */
    @Test
    void the_budget_is_still_declared() throws Exception {
        assertThat(source()).contains("RERANK_BUDGET_MS");
    }
}
