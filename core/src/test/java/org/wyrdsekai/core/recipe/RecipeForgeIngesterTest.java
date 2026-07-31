package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — recipe-run outcomes → DEXTERITY soul-fragments + training corpus. */
class RecipeForgeIngesterTest {

    private static RecipeRunner.RecipeRun successWithMetric() {
        var ctx = new RecipeContext();
        ctx.put("val_accuracy", 0.8348);
        return new RecipeRunner.RecipeRun(RecipeRunner.Status.SUCCESS, "ok",
                List.of(
                        new RecipeRunner.StepOutcome("train", StepKind.SHELL, true, "exit=0"),
                        new RecipeRunner.StepOutcome("gate-accuracy", StepKind.GATE, true, "PASS"),
                        new RecipeRunner.StepOutcome("gate-regression", StepKind.GATE, true, "PASS"),
                        new RecipeRunner.StepOutcome("deploy", StepKind.SHELL, true, "exit=0")),
                ctx);
    }

    private static RecipeRunner.RecipeRun gateFailed() {
        var ctx = new RecipeContext();
        ctx.put("val_accuracy", 0.55);
        return new RecipeRunner.RecipeRun(RecipeRunner.Status.GATE_FAILED, "gate 'gate-accuracy' failed",
                List.of(
                        new RecipeRunner.StepOutcome("train", StepKind.SHELL, true, "exit=0"),
                        new RecipeRunner.StepOutcome("gate-accuracy", StepKind.GATE, false, "FAIL")),
                ctx);
    }

    private static RecipeRunner.RecipeRun rolledBack() {
        return new RecipeRunner.RecipeRun(RecipeRunner.Status.STEP_FAILED, "shell step 'post-deploy-check' exit 1",
                List.of(
                        new RecipeRunner.StepOutcome("deploy", StepKind.SHELL, true, "exit=0"),
                        new RecipeRunner.StepOutcome("post-deploy-check", StepKind.SHELL, false, "exit=1"),
                        new RecipeRunner.StepOutcome("rollback", StepKind.SHELL, true, "rollback exit=0")),
                new RecipeContext());
    }

    @Test void success_yields_dexterity_fragment_and_corpus_line() {
        var batch = new RecipeForgeIngester.Batch("did:wyrd:agent1",
                List.of(new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, successWithMetric())));
        var result = RecipeForgeIngester.ingest(batch);

        assertEquals(1, result.newFragments().size());
        SoulFragment f = result.newFragments().get(0);
        assertEquals(FragmentKind.DEXTERITY, f.kind(), "recipe learnings must be DEXTERITY");
        assertEquals("procedure", f.category());
        assertTrue(f.text().contains("succeeded"), "text: " + f.text());
        assertTrue(f.text().contains("0.8348"), "headline metric should surface: " + f.text());

        assertEquals(1, result.corpusEntries().size(), "a clean success contributes one corpus line");
        assertTrue(result.corpusEntries().get(0).contains("retrain-classifier-head"));
    }

    @Test void gate_failure_is_recorded_but_not_corpus_weighted() {
        var batch = new RecipeForgeIngester.Batch("did:wyrd:agent1",
                List.of(new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, gateFailed())));
        var result = RecipeForgeIngester.ingest(batch);

        assertEquals(1, result.newFragments().size());
        assertTrue(result.newFragments().get(0).text().contains("stopped at a gate"),
                "text: " + result.newFragments().get(0).text());
        assertTrue(result.newFragments().get(0).text().contains("gate-accuracy"));
        assertTrue(result.corpusEntries().isEmpty(), "a blocked run must NOT seed the training corpus");
    }

    @Test void rolled_back_run_narrates_reversibility() {
        var batch = new RecipeForgeIngester.Batch("did:wyrd:agent1",
                List.of(new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, rolledBack())));
        var result = RecipeForgeIngester.ingest(batch);

        String text = result.newFragments().get(0).text();
        assertTrue(text.contains("rolled back"), "text: " + text);
        assertTrue(text.contains("post-deploy-check"), "should name the failing step: " + text);
        assertTrue(result.corpusEntries().isEmpty());
    }

    @Test void habit_identity_fragment_emitted_past_threshold() {
        var batch = new RecipeForgeIngester.Batch("did:wyrd:agent1", List.of(
                new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, successWithMetric()),
                new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, successWithMetric()),
                new RecipeForgeIngester.CompletedRun("retrain-classifier-head", true, successWithMetric())));
        var result = RecipeForgeIngester.ingest(batch);

        // 3 run fragments + 1 identity fragment
        assertEquals(4, result.newFragments().size());
        assertTrue(result.newFragments().stream().anyMatch(f -> f.label().equals("Recipe-driver habit")));
        // every fragment this ingester emits is DEXTERITY
        assertTrue(result.newFragments().stream().allMatch(f -> f.kind() == FragmentKind.DEXTERITY));
        assertEquals(3, result.corpusEntries().size());
    }

    @Test void empty_batch_is_empty_result() {
        var result = RecipeForgeIngester.ingest(new RecipeForgeIngester.Batch("did:wyrd:agent1", List.of()));
        assertTrue(result.isEmpty());
        assertFalse(result.newFragments().size() > 0);
    }
}
