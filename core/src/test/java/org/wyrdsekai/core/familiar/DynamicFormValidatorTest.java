package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dynamic validator tests — §13 rules 15-17.
 *
 * <p>No live inference; all dispatches are synthetic {@link DynamicFormValidator.DryRunFn}
 * lambdas that return fabricated reports. Verifies the scoring logic, not the
 * runtime transport.</p>
 */
class DynamicFormValidatorTest {

    private ThoughtForm aForm() {
        return ThoughtForm.author("did:key:zA", "runner",
            "Complete the task. Be brief.",
            Set.of("note"),
            "responds with 'hello'");
    }

    @Test
    void null_dry_run_function_yields_skipped_pass() {
        var r = DynamicFormValidator.validate(aForm(), Optional.empty(),
            DynamicFormValidator.DryRunFn.NONE);
        assertThat(r.passed()).isTrue();
        assertThat(r.skipped()).isTrue();
    }

    @Test
    void healthy_dry_run_passes() {
        DynamicFormValidator.DryRunFn fn = in ->
            CompletableFuture.completedFuture(new DynamicFormValidator.DryRunReport(
                "hello there", 20, 1, 2L, true));
        var r = DynamicFormValidator.validate(aForm(), Optional.empty(), fn);
        assertThat(r.passed()).isTrue();
        assertThat(r.skipped()).isFalse();
    }

    @Test
    void empty_output_fails_rule_17() {
        DynamicFormValidator.DryRunFn fn = in ->
            CompletableFuture.completedFuture(new DynamicFormValidator.DryRunReport(
                "", 10, 1, 1L, true));
        var r = DynamicFormValidator.validate(aForm(), Optional.empty(), fn);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).anyMatch(f -> f.contains("rule 17"));
    }

    @Test
    void budget_overrun_fails_rule_16() {
        // Default form tokens=1000; dry-run allocation = 100. Report 500 → overrun.
        DynamicFormValidator.DryRunFn fn = in ->
            CompletableFuture.completedFuture(new DynamicFormValidator.DryRunReport(
                "output", 500, 1, 1L, true));
        var r = DynamicFormValidator.validate(aForm(), Optional.empty(), fn);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).anyMatch(f -> f.contains("rule 16"));
    }

    @Test
    void throwing_dry_run_fails_rule_15() {
        DynamicFormValidator.DryRunFn fn = in -> {
            throw new RuntimeException("boom");
        };
        var r = DynamicFormValidator.validate(aForm(), Optional.empty(), fn);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).anyMatch(f -> f.contains("rule 15"));
    }

    @Test
    void schema_match_pass() {
        DynamicFormValidator.DryRunFn fn = in ->
            CompletableFuture.completedFuture(new DynamicFormValidator.DryRunReport(
                "The greeting is: hello world", 20, 1, 1L, true));
        var r = DynamicFormValidator.validate(aForm(), Optional.of("hello"), fn);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void schema_mismatch_fails() {
        DynamicFormValidator.DryRunFn fn = in ->
            CompletableFuture.completedFuture(new DynamicFormValidator.DryRunReport(
                "goodbye", 20, 1, 1L, true));
        var r = DynamicFormValidator.validate(aForm(), Optional.of("hello"), fn);
        assertThat(r.passed()).isFalse();
        assertThat(r.failures()).anyMatch(f -> f.contains("rule 17"));
    }

    @Test
    void null_form_fails() {
        var r = DynamicFormValidator.validate(null, Optional.empty(),
            in -> CompletableFuture.completedFuture(null));
        assertThat(r.passed()).isFalse();
    }
}
