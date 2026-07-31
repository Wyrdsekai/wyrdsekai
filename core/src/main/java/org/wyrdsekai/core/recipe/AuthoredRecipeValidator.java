package org.wyrdsekai.core.recipe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * #1014 (OPEN-R1) — the safety core of the agent-authored recipe
 * compartment. Ship recipes are vetted by a human; an agent-authored recipe is
 * NOT, so it gets a tighter contract than {@link RecipeParser} (structural) +
 * {@link RecipeCallableValidator} (script headers) provide on their own.
 *
 * <p>The risk this closes: a recipe {@code SHELL} step runs an arbitrary command
 * via the host shell. {@link RecipeCallableValidator} only checks that any
 * {@code scripts/...} reference is local-ok — it does NOT stop a bare
 * {@code rm -rf /} (which carries no script ref). For an artifact the agent
 * writes itself, that is remote-code-execution. So an authored recipe may only
 * <b>re-compose the household's already-vetted recipe-callable scripts</b> with
 * gates and decisions — it can never introduce new shell.</p>
 *
 * <p>The contract (v1):</p>
 * <ul>
 *   <li>Allowed step kinds: {@code SHELL}, {@code GATE}, {@code DECISION}.
 *       {@code BACKEND} / {@code GOOSE_RECIPE} (spawn a coding agent) and
 *       {@code LONG_JOB} (detached GPU job) are reserved for ship/steward
 *       recipes — rejected here.</li>
 *   <li>Every {@code SHELL} command (and its rollback) must be a single-line
 *       invocation of a {@code scripts/...} recipe-callable script: the first
 *       token (after an optional {@code python3|python|bash|sh} interpreter)
 *       must be a {@code scripts/<name>.(py|sh|js)} path with no {@code ..}, and
 *       the command may contain none of the shell-control metacharacters
 *       {@code ; | & ` $( > < #} or a raw newline. {@code {{param}}} templates
 *       and {@code ${VAR:-default}} env-defaults are allowed.</li>
 *   <li>A name that matches a classpath-bundled ship recipe is refused (an
 *       authored recipe may not shadow a vetted one). Name must be
 *       {@code [a-z0-9][a-z0-9-]{1,48}}.</li>
 *   <li>An authored recipe that {@code deploys:true} must carry at least one
 *       PERMANENT welfare gate — a self-authored deploy keeps a non-loosenable
 *       floor. ({@link RecipeParser} already requires ≥2 gates total for any
 *       deploy.)</li>
 * </ul>
 *
 * <p>This is NOT a maturity ladder (cf. [[feedback-no-paternalism-on-agent-defaults]]):
 * it is a capability-surface boundary, the same kind items already get. Welfare
 * gates still do the run-time protection; this only bounds what an authored
 * recipe may <em>contain</em>.</p>
 *
 * <p>Pure logic. {@code scriptsRoot == null} (tests) skips the on-disk
 * header/existence check but keeps every structural check.</p>
 */
public final class AuthoredRecipeValidator {

    private AuthoredRecipeValidator() {}

    /** Step kinds an agent may author (v1). The rest are steward/ship-only. */
    private static final Set<StepKind> ALLOWED_KINDS =
        Set.of(StepKind.SHELL, StepKind.GATE, StepKind.DECISION);

    /** Recipe name shape — lowercase, hyphenated, file-safe. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,48}");

    /** First non-interpreter token must be a scripts/ path of an allowed type. */
    private static final Pattern SCRIPT_HEAD = Pattern.compile(
        "scripts/[A-Za-z0-9_./-]+\\.(?:py|sh|js)");

    /** Optional leading interpreter the command may start with. */
    private static final Pattern INTERP = Pattern.compile(
        "^(?:python3|python|bash|sh)\\s+");

    /** Shell-control metacharacters forbidden in an authored command. */
    private static final Pattern FORBIDDEN_SHELL =
        Pattern.compile("[;|&`<>#\\n\\r]|\\$\\(");

    public record Result(boolean ok, List<String> violations) {
        public String summary() {
            return ok ? "valid"
                : violations.size() + " authored-recipe violation(s): "
                    + String.join("; ", violations);
        }
        static Result valid() { return new Result(true, List.of()); }
        static Result fail(List<String> v) { return new Result(false, List.copyOf(v)); }
    }

    /**
     * Validate an authored manifest. {@code reservedNames} are names the author
     * may not use (the classpath-bundled ship recipes — see {@link
     * RecipeService#bundledNames()}). {@code scriptsRoot} is the install
     * {@code scripts/} dir for the recipe-callable header check (null skips it).
     */
    public static Result validate(RecipeManifest m, Set<String> reservedNames, Path scriptsRoot) {
        var v = new ArrayList<String>();
        if (m == null) return Result.fail(List.of("null manifest"));

        // ── name ──
        String name = m.recipe();
        if (name == null || !NAME.matcher(name).matches()) {
            v.add("name '" + name + "' must match [a-z0-9][a-z0-9-]{1,48}");
        } else if (reservedNames != null && reservedNames.contains(name)) {
            v.add("name '" + name + "' shadows a bundled ship recipe (reserved)");
        }

        // ── step kinds + shell safety ──
        for (var step : m.steps()) {
            if (!ALLOWED_KINDS.contains(step.kind())) {
                v.add("step '" + step.id() + "' kind " + step.kind()
                    + " is not authorable (allowed: SHELL, GATE, DECISION)");
                continue;
            }
            if (step instanceof RecipeStep.Shell sh) {
                checkCommand(sh.id(), "command", sh.command(), v);
                if (sh.hasRollback()) checkCommand(sh.id(), "rollback", sh.rollback(), v);
            }
        }

        // ── deploy floor: an authored deploy must keep a PERMANENT gate ──
        if (m.deploys()) {
            boolean hasPermanent = m.stepsOfKind(StepKind.GATE).stream()
                .anyMatch(s -> s instanceof RecipeStep.Gate g && g.isPermanentWelfare());
            if (!hasPermanent) {
                v.add("a deploys:true authored recipe must carry at least one "
                    + "PERMANENT welfare gate (a non-loosenable floor)");
            }
        }

        // ── recipe-callable header check (folds in the existing validator) ──
        if (scriptsRoot != null && v.isEmpty()) {
            // Only run the on-disk pass once the structural checks are clean, so
            // the message points at the real problem.
            var callable = RecipeCallableValidator.validate(m, scriptsRoot);
            for (var c : callable) {
                v.add("step '" + c.stepId() + "' script " + c.scriptPath()
                    + ": " + c.reason());
            }
        }

        return v.isEmpty() ? Result.valid() : Result.fail(v);
    }

    private static void checkCommand(String stepId, String which, String cmd, List<String> v) {
        if (cmd == null || cmd.isBlank()) {
            v.add("step '" + stepId + "' " + which + " is blank");
            return;
        }
        if (FORBIDDEN_SHELL.matcher(cmd).find()) {
            v.add("step '" + stepId + "' " + which
                + " contains a forbidden shell metacharacter (one of ; | & ` < > # newline $() — "
                + "an authored command may only invoke a scripts/ helper");
            return;
        }
        // First token (after an optional interpreter) must be a scripts/ path.
        String head = INTERP.matcher(cmd.stripLeading()).replaceFirst("").stripLeading();
        int sp = head.indexOf(' ');
        String first = sp < 0 ? head : head.substring(0, sp);
        if (!SCRIPT_HEAD.matcher(first).matches()) {
            v.add("step '" + stepId + "' " + which
                + " must invoke a scripts/ helper as its first token (got '" + first + "')");
            return;
        }
        if (first.contains("..")) {
            v.add("step '" + stepId + "' " + which + " script path may not contain '..'");
        }
    }
}
