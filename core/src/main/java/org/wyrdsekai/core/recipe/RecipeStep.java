package org.wyrdsekai.core.recipe;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One step of a Wyrdsekai recipe manifest.
 *
 * <p>A recipe is an ordered list of typed steps run by the recipe engine on top
 * of the existing GoalExecutor/Pekko primitives. The load-bearing rule: {@link Gate},
 * deploy, and rollback are enforced by the Wyrdsekai runtime, never delegated to a
 * coding backend ( — the welfare-floor gate).
 *
 * <p>Sealed so the runner (P2) can exhaustively switch on step kind. Records carry
 * only data; the engine interprets them.
 *
 * <p>#1012 — Shell/Backend/GooseRecipeRef/LongJob each carry an optional {@code timeout}
 * override. When null, the runner falls back to {@link StepKind#defaultTimeout()}. GATE
 * and DECISION are pure-runtime (no wall-clock) and have no timeout.
 */
public sealed interface RecipeStep
        permits RecipeStep.Shell, RecipeStep.GooseRecipeRef, RecipeStep.Backend,
                RecipeStep.Gate, RecipeStep.Decision, RecipeStep.LongJob {

    /** Unique within a manifest; referenced by Decision branches and Gate.onFail. */
    String id();

    StepKind kind();

    /**
     * Per-step wall-clock override (#1012). Null means "use {@link StepKind#defaultTimeout()}".
     * Pure-runtime kinds (GATE/DECISION) always return null. The runner reads this; the
     * dispatcher/command-runner reads what the runner passes.
     */
    default Duration timeout() { return null; }

    /**
     * Run a command/template directly in the Wyrdsekai runtime (deterministic plumbing).
     * {@code rollback} (optional) is a compensating command the runner executes in reverse
     * order if a later step fails — this is how a deploy step stays reversible.
     * {@code timeout} (optional) overrides the SHELL-kind default of 10min.
     */
    record Shell(String id, String command, String rollback, Duration timeout) implements RecipeStep {
        public Shell(String id, String command) { this(id, command, null, null); }
        public Shell(String id, String command, String rollback) { this(id, command, rollback, null); }
        @Override public StepKind kind() { return StepKind.SHELL; }
        public boolean hasRollback() { return rollback != null && !rollback.isBlank(); }
    }

    /** Run a referenced Goose-compatible recipe (leaf executable unit) via a backend. */
    record GooseRecipeRef(String id, String recipeRef, Map<String, Object> params, Duration timeout)
            implements RecipeStep {
        public GooseRecipeRef(String id, String recipeRef, Map<String, Object> params) {
            this(id, recipeRef, params, null);
        }
        @Override public StepKind kind() { return StepKind.GOOSE_RECIPE; }
    }

    /** Dispatch a bounded freeform sub-prompt to the selected backend (Pi default). */
    record Backend(String id, String prompt, List<String> tools, String successContract, Duration timeout)
            implements RecipeStep {
        public Backend(String id, String prompt, List<String> tools, String successContract) {
            this(id, prompt, tools, successContract, null);
        }
        @Override public StepKind kind() { return StepKind.BACKEND; }
    }

    /**
     * Assert a condition; on failure STOP or branch. Enforced in-runtime — a backend
     * cannot bypass it. {@code onFail} is the literal "STOP" or a target step id.
     *
     * <p>{@code welfare} declares whether the gate is a permanent welfare-floor
     * contract (load-bearing, never auto-loosens — e.g. the val-accuracy floor in
     * retrain-classifier-head, or the overrouting probe) versus a temporary
     * capability gate (auto-loosens after sustained green runs, manageable by
     * Forge-side recipe revision). OPEN-R4 / §10.5 OPEN-25.
     * The tag is the contract surface; auto-loosening logic for TEMPORARY is
     * deferred to post-v0.1. Default {@link WelfareClass#TEMPORARY} when
     * unspecified — explicit PERMANENT must be authored.
     */
    record Gate(String id, String condition, String onFail, WelfareClass welfare)
            implements RecipeStep {
        public static final String STOP = "STOP";
        public Gate(String id, String condition, String onFail) {
            this(id, condition, onFail, WelfareClass.TEMPORARY);
        }
        @Override public StepKind kind() { return StepKind.GATE; }
        public boolean stopsOnFail() { return STOP.equalsIgnoreCase(onFail); }
        public boolean isPermanentWelfare() { return welfare == WelfareClass.PERMANENT; }
    }

    /**
     * Classifies a Gate's contract surface. See {@link Gate} javadoc +.
     * PERMANENT: load-bearing, mirrors the welfare-floor contract from
     * never tightened-or-relaxed by automated
     *   recipe revision.
     * TEMPORARY: capability gate (default); Forge-side revision can tune the
     *   threshold once the agent demonstrates sustained capability.
     */
    enum WelfareClass { PERMANENT, TEMPORARY }

    /** Read an artifact, branch to a step. {@code branches} maps an outcome value → step id. */
    record Decision(String id, String reads, Map<String, String> branches) implements RecipeStep {
        @Override public StepKind kind() { return StepKind.DECISION; }
    }

    /** Launch + detach + poll a long-running job (e.g. a GPU run) via the bunshin scheduler. */
    record LongJob(String id, String command, int pollSeconds, String doneWhen, Duration timeout)
            implements RecipeStep {
        public LongJob(String id, String command, int pollSeconds, String doneWhen) {
            this(id, command, pollSeconds, doneWhen, null);
        }
        @Override public StepKind kind() { return StepKind.LONG_JOB; }
    }
}
