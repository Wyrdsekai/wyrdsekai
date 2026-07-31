package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** — runner: sequencing, in-runtime gates, branching, rollback, NEEDS_BACKEND. */
class RecipeRunnerTest {

    /** Stub command runner: maps a command substring → result; records every command run. */
    static final class StubCommands implements CommandRunner {
        final List<String> ran = new ArrayList<>();
        private final Function<String, Result> rule;
        StubCommands(Function<String, Result> rule) { this.rule = rule; }
        @Override public Result run(String command) {
            ran.add(command);
            return rule.apply(command);
        }
    }

    private static CommandRunner.Result ok(String stdout) { return new CommandRunner.Result(0, stdout, ""); }
    private static CommandRunner.Result fail() { return new CommandRunner.Result(1, "", "boom"); }

    @Test void happy_path_runs_to_success_through_gates() {
        String yaml = """
            recipe: t-happy
            deploys: true
            params: { min_accuracy: { type: number, default: 0.80 } }
            steps:
              - { id: train, kind: SHELL, command: "train {{min_accuracy}}" }
              - { id: gate-acc, kind: GATE, condition: "val_accuracy >= {{min_accuracy}}", on_fail: STOP }
              - { id: gate-reg, kind: GATE, condition: "true == true", on_fail: STOP }
              - { id: deploy, kind: SHELL, command: "cp new prod", rollback: "cp prod.bak prod" }
            """;
        var cmds = new StubCommands(c -> c.contains("train") ? ok("{\"val_accuracy\": 0.83}") : ok(""));
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(0.83, ((Number) run.context().get("val_accuracy")).doubleValue(), 1e-9);
        assertTrue(cmds.ran.stream().anyMatch(c -> c.equals("cp new prod")), "deploy should have run");
        assertTrue(cmds.ran.stream().anyMatch(c -> c.equals("train 0.8")), "param default should template into command");
    }

    @Test void failing_metric_gate_stops_before_deploy() {
        String yaml = """
            recipe: t-gate-fail
            deploys: true
            steps:
              - { id: train, kind: SHELL, command: "train" }
              - { id: gate-acc, kind: GATE, condition: "val_accuracy >= 0.80", on_fail: STOP }
              - { id: gate-reg, kind: GATE, condition: "true == true", on_fail: STOP }
              - { id: deploy, kind: SHELL, command: "cp new prod" }
            """;
        var cmds = new StubCommands(c -> c.contains("train") ? ok("{\"val_accuracy\": 0.50}") : ok(""));
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.GATE_FAILED, run.status());
        assertFalse(cmds.ran.contains("cp new prod"), "deploy must NOT run when the metric gate fails");
    }

    @Test void rollback_runs_in_reverse_on_post_deploy_failure() {
        String yaml = """
            recipe: t-rollback
            steps:
              - { id: deploy, kind: SHELL, command: "cp new prod", rollback: "cp prod.bak prod" }
              - { id: smoke, kind: SHELL, command: "smoke-fail" }
            """;
        var cmds = new StubCommands(c -> c.contains("fail") ? fail() : ok(""));
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertTrue(cmds.ran.contains("cp prod.bak prod"), "deploy rollback should run after smoke failure");
        // rollback runs AFTER the failing smoke step
        assertTrue(cmds.ran.indexOf("cp prod.bak prod") > cmds.ran.indexOf("smoke-fail"));
    }

    @Test void decision_branches_on_context_value() {
        String yaml = """
            recipe: t-decide
            steps:
              - { id: probe, kind: SHELL, command: "emit" }
              - { id: decide, kind: DECISION, reads: result, branches: { pass: done, fail: bad } }
              - { id: bad, kind: SHELL, command: "should-not-run" }
              - { id: done, kind: SHELL, command: "echo done" }
            """;
        var cmds = new StubCommands(c -> c.contains("emit") ? ok("{\"result\": \"pass\"}") : ok(""));
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertFalse(cmds.ran.contains("should-not-run"), "fail branch must be skipped");
        assertTrue(cmds.ran.contains("echo done"));
    }

    @Test void backend_step_needs_backend_when_no_dispatcher() {
        String yaml = """
            recipe: t-backend
            steps:
              - { id: b, kind: BACKEND, prompt: "do it", tools: [shell], success_contract: "exit:0" }
            """;
        var run = new RecipeRunner(new StubCommands(c -> ok(""))).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.NEEDS_BACKEND, run.status());
    }

    @Test void backend_step_dispatches_when_dispatcher_present() {
        String yaml = """
            recipe: t-backend2
            steps:
              - { id: b, kind: BACKEND, prompt: "do it", success_contract: "exit:0" }
            """;
        BackendDispatcher disp = (step, ctx) -> { ctx.put("did", true); return true; };
        var run = new RecipeRunner(new StubCommands(c -> ok("")), disp)
                .run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(Boolean.TRUE, run.context().get("did"));
    }

    @Test void shell_failure_halts_run() {
        String yaml = """
            recipe: t-shellfail
            steps:
              - { id: a, kind: SHELL, command: "do-fail" }
              - { id: b, kind: SHELL, command: "after" }
            """;
        var cmds = new StubCommands(c -> c.contains("fail") ? fail() : ok(""));
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertFalse(cmds.ran.contains("after"), "steps after a failed shell step must not run");
    }

    // -- OPEN-R5 closure: probe_overrouting.py SHELL JSON → gate-regression chain ---
    // The probe emits a single-line JSON object on stdout. mergeJsonStdout pulls
    // `overrouting_probe_passes` into RecipeContext where gate-regression reads it.
    // These tests prove the full SHELL → merge → GATE chain without invoking the
    // real Python — the runner's contract is "stdout JSON → context keys".

    @Test void regression_probe_passing_shape_drives_gate_to_pass() {
        // Simulates probe_overrouting.py emitting overrouting_probe_passes=true.
        // gate-regression should branch true, deploy should fire.
        String yaml = """
            recipe: t-probe-pass
            deploys: true
            steps:
              - { id: train, kind: SHELL, command: "train" }
              - { id: gate-acc, kind: GATE, condition: "val_accuracy >= 0.80", on_fail: STOP }
              - { id: regression-probe, kind: SHELL, command: "probe" }
              - { id: gate-reg, kind: GATE, condition: "overrouting_probe_passes == true", on_fail: STOP }
              - { id: deploy, kind: SHELL, command: "cp new prod" }
            """;
        var cmds = new StubCommands(c -> {
            if (c.equals("train")) return ok("{\"val_accuracy\": 0.91}");
            if (c.equals("probe")) return ok(
                "{\"overrouting_probe_passes\": true, "
                + "\"anchors_tested\": 90, \"misclassified\": 4, \"max_misses\": 6}");
            return ok("");
        });
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(Boolean.TRUE, run.context().get("overrouting_probe_passes"));
        assertEquals(90, ((Number) run.context().get("anchors_tested")).intValue());
        assertTrue(cmds.ran.contains("cp new prod"), "deploy must run when probe passes");
    }

    // -- #1012: per-kind step-watchdog timeouts + flat-1 configurable retry --------------

    /** Stub command runner that captures per-call timeout and can be scripted per attempt. */
    static final class TimingStub implements CommandRunner {
        final List<Duration> timeoutsSeen = new ArrayList<>();
        final List<String> ran = new ArrayList<>();
        private final Function<Integer, Result> perAttempt;
        private final AtomicInteger attempt = new AtomicInteger();
        TimingStub(Function<Integer, Result> perAttempt) { this.perAttempt = perAttempt; }
        @Override public Result run(String command) { return run(command, null); }
        @Override public Result run(String command, Duration timeout) {
            ran.add(command);
            timeoutsSeen.add(timeout);
            return perAttempt.apply(attempt.getAndIncrement());
        }
    }

    private static CommandRunner.Result transientFail() {
        return new CommandRunner.Result(124, "", "timed out", true);
    }

    @Test void shell_step_uses_kind_default_timeout_when_no_override() {
        // SHELL default = 10 minutes (StepKind.SHELL.defaultTimeout()).
        var stub = new TimingStub(i -> ok(""));
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-kind-default
                    steps:
                      - { id: a, kind: SHELL, command: "echo a" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(1, stub.timeoutsSeen.size());
        assertEquals(Duration.ofMinutes(10), stub.timeoutsSeen.get(0),
                "SHELL kind default of 10min should be threaded into the runner");
    }

    @Test void shell_step_uses_per_step_timeout_override_when_declared() {
        var stub = new TimingStub(i -> ok(""));
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-override
                    steps:
                      - { id: a, kind: SHELL, command: "echo a", timeout: 90s }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(Duration.ofSeconds(90), stub.timeoutsSeen.get(0),
                "per-step timeout override should beat the kind default");
    }

    @Test void long_job_kind_default_is_two_hours() {
        var stub = new TimingStub(i -> ok(""));
        // LONG_JOB goes through the BackendDispatcher path — to exercise the kind default,
        // wire a tiny inline dispatcher that just records the timeout it sees.
        var seen = new ArrayList<Duration>();
        BackendDispatcher disp = new BackendDispatcher() {
            @Override public boolean dispatch(RecipeStep step, RecipeContext ctx) { return true; }
            @Override public DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration t) {
                seen.add(t);
                return DispatchOutcome.success();
            }
        };
        var run = new RecipeRunner(stub, disp, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-longjob-default
                    steps:
                      - { id: lj, kind: LONG_JOB, command: "train.sh", poll_seconds: 60, done_when: "ckpt exists" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(Duration.ofHours(2), seen.get(0),
                "LONG_JOB kind default should be 2 hours");
    }

    @Test void shell_transient_failure_retries_and_succeeds_on_second_attempt() {
        // Manifest defaults to retry_count = 1 → up to 2 attempts. First attempt: transient
        // timeout. Second attempt: succeeds.
        var stub = new TimingStub(i -> i == 0 ? transientFail() : ok("{\"val\": 7}"));
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-retry-ok
                    steps:
                      - { id: a, kind: SHELL, command: "flaky" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(2, stub.ran.size(), "the runner should retry once after the transient failure");
        // Outcome detail should call out the retry.
        assertTrue(run.outcomes().get(0).detail().contains("after 2 attempts"),
                "StepOutcome should surface the retry count");
        assertEquals(2, ((Number) run.context().get("a.attempts")).intValue());
    }

    @Test void shell_transient_failure_exhausts_retries_and_fails() {
        // All attempts are transient timeouts. retry_count default = 1 → 2 attempts then halt.
        var stub = new TimingStub(i -> transientFail());
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-retry-exhaust
                    steps:
                      - { id: a, kind: SHELL, command: "wedged" }
                      - { id: b, kind: SHELL, command: "should-not-run" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertEquals(2, stub.ran.size(), "retry budget = 1 means exactly 2 attempts before halt");
        assertFalse(stub.ran.contains("should-not-run"), "next step must not run after exhaustion");
    }

    @Test void shell_logical_failure_does_NOT_retry() {
        // exit 1 is a logical failure (success_contract violated) — retry would only mask the
        // real problem. The runner must halt immediately.
        var stub = new TimingStub(i -> fail()); // exit 1, transient=false
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-logical-no-retry
                    retry_count: 2
                    steps:
                      - { id: a, kind: SHELL, command: "logic-bug" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertEquals(1, stub.ran.size(),
                "logical failures must not consume retries — halt immediately");
        assertNull(run.context().get("a.attempts"),
                "no retries → no '.attempts' key in context");
    }

    @Test void retry_count_zero_disables_retry_even_on_transient() {
        var stub = new TimingStub(i -> transientFail());
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-retry-zero
                    retry_count: 0
                    steps:
                      - { id: a, kind: SHELL, command: "flaky" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertEquals(1, stub.ran.size(),
                "retry_count: 0 should disable retry — single attempt then halt");
    }

    @Test void retry_count_two_gives_three_total_attempts_then_recovery() {
        // 1st: transient. 2nd: transient. 3rd: success.
        var stub = new TimingStub(i -> i < 2 ? transientFail() : ok(""));
        var run = new RecipeRunner(stub, null, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-retry-2
                    retry_count: 2
                    steps:
                      - { id: a, kind: SHELL, command: "flakier" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(3, stub.ran.size(), "retry_count 2 → up to 3 attempts");
        assertEquals(3, ((Number) run.context().get("a.attempts")).intValue());
    }

    @Test void backend_transient_failure_retries_via_dispatcher() {
        // Mirror retry semantics on the BACKEND path through DispatchOutcome.transientFail.
        var attempts = new AtomicInteger(0);
        BackendDispatcher disp = new BackendDispatcher() {
            @Override public boolean dispatch(RecipeStep step, RecipeContext ctx) { return false; }
            @Override public DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration t) {
                int i = attempts.getAndIncrement();
                return i == 0
                        ? DispatchOutcome.transientFail()
                        : DispatchOutcome.success();
            }
        };
        var run = new RecipeRunner(new StubCommands(c -> ok("")), disp, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-backend-retry
                    steps:
                      - { id: b, kind: BACKEND, prompt: "do it", success_contract: "exit:0" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertEquals(2, attempts.get(), "BACKEND should retry once on transient failure");
        assertEquals(2, ((Number) run.context().get("b.attempts")).intValue());
    }

    @Test void backend_logical_failure_does_NOT_retry() {
        var attempts = new AtomicInteger(0);
        BackendDispatcher disp = new BackendDispatcher() {
            @Override public boolean dispatch(RecipeStep step, RecipeContext ctx) { return false; }
            @Override public DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration t) {
                attempts.incrementAndGet();
                return DispatchOutcome.logicalFail();
            }
        };
        var run = new RecipeRunner(new StubCommands(c -> ok("")), disp, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-backend-logical
                    retry_count: 2
                    steps:
                      - { id: b, kind: BACKEND, prompt: "do it", success_contract: "exit:0" }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.STEP_FAILED, run.status());
        assertEquals(1, attempts.get(), "logical failures don't burn retries");
    }

    @Test void backend_per_step_timeout_threads_into_dispatcher() {
        var seen = new ArrayList<Duration>();
        BackendDispatcher disp = new BackendDispatcher() {
            @Override public boolean dispatch(RecipeStep step, RecipeContext ctx) { return true; }
            @Override public DispatchOutcome dispatchWith(RecipeStep step, RecipeContext ctx, Duration t) {
                seen.add(t);
                return DispatchOutcome.success();
            }
        };
        var run = new RecipeRunner(new StubCommands(c -> ok("")), disp, RecipeRunner.Sleeper.NOOP)
                .run(RecipeParser.parseManifest("""
                    recipe: t-backend-timeout
                    steps:
                      - { id: b, kind: BACKEND, prompt: "do it", success_contract: "exit:0", timeout: 30m }
                    """), Map.of());
        assertEquals(RecipeRunner.Status.SUCCESS, run.status());
        assertNotNull(seen.get(0));
        assertEquals(Duration.ofMinutes(30), seen.get(0));
    }

    @Test void regression_probe_failing_shape_stops_at_gate() {
        // Simulates probe_overrouting.py emitting overrouting_probe_passes=false.
        // gate-regression must STOP before deploy — load-bearing welfare gate.
        String yaml = """
            recipe: t-probe-fail
            deploys: true
            steps:
              - { id: train, kind: SHELL, command: "train" }
              - { id: gate-acc, kind: GATE, condition: "val_accuracy >= 0.80", on_fail: STOP }
              - { id: regression-probe, kind: SHELL, command: "probe" }
              - { id: gate-reg, kind: GATE, condition: "overrouting_probe_passes == true", on_fail: STOP, welfare: permanent }
              - { id: deploy, kind: SHELL, command: "cp new prod" }
            """;
        var cmds = new StubCommands(c -> {
            if (c.equals("train")) return ok("{\"val_accuracy\": 0.91}");
            if (c.equals("probe")) return ok(
                "{\"overrouting_probe_passes\": false, "
                + "\"anchors_tested\": 90, \"misclassified\": 27, \"max_misses\": 6}");
            return ok("");
        });
        var run = new RecipeRunner(cmds).run(RecipeParser.parseManifest(yaml), Map.of());
        assertEquals(RecipeRunner.Status.GATE_FAILED, run.status());
        assertEquals(Boolean.FALSE, run.context().get("overrouting_probe_passes"));
        assertFalse(cmds.ran.contains("cp new prod"),
            "deploy must NOT run when overrouting probe fails — welfare floor");
    }
}
