package org.wyrdsekai.core.recipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * enforces the "recipe-callable script must be local-only by
 * default" invariant. Any script reachable from a recipe YAML's SHELL,
 * BACKEND, or LongJob step must carry the {@code # recipe-callable: local-ok}
 * header within its first {@link #HEADER_SCAN_LINES} lines.
 *
 * <p>The invariant exists because the V6 "familiar does its own ML" reframe
 * (see {@code v6-reframe-familiar-does-own-ml-2026-05-20}) requires households
 * to evolve without cloud accounts. A script reached from a default-enrolled
 * recipe that needs a cloud API silently breaks the OSS autonomy claim — and
 * the household only discovers it the first time the scheduler fires. Header +
 * load-time check prevents that drift.
 *
 * <p>Operationally:
 * <ul>
 *   <li>Pure function. Returns the violation list; caller decides whether to
 *       throw / warn / persist.</li>
 *   <li>Skips silently when {@code scriptsRoot} is null (test contexts that
 *       don't ship the source tree).</li>
 *   <li>A referenced script that doesn't exist on disk is a violation — it
 *       means the recipe references something that won't be there at run
 *       time. Tests can opt out by passing null scriptsRoot.</li>
 *   <li>The required header line is grep'd from the first {@link
 *       #HEADER_SCAN_LINES} lines (cheap, allows shebang/blank/docstring
 *       to come before).</li>
 * </ul>
 *
 * <p>To add a new recipe-callable script: drop the header right after the
 * shebang. If the script needs cloud access, do not add the header — either
 * (a) add a local-first fallback path so it does, or (b) make sure no
 * default-enrolled recipe references it.
 */
public final class RecipeCallableValidator {

    /** Header marker required at the top of any recipe-callable script. */
    public static final String HEADER_MARKER = "recipe-callable: local-ok";

    /** How many leading lines to scan for the header (covers shebang +
     *  blank + short header comment). */
    public static final int HEADER_SCAN_LINES = 10;

    /** Regex extracting {@code scripts/.../*.(py|sh|js)} references from a
     *  step's command/prompt text. Anchored on the {@code scripts/} prefix
     *  so we don't catch arbitrary file paths an item happens to mention. */
    private static final Pattern SCRIPT_REF = Pattern.compile(
        "scripts/[A-Za-z0-9_./-]+\\.(?:py|sh|js)");

    private RecipeCallableValidator() {}

    /** Captures a single invariant breach with enough context for the
     *  steward / build to act on it. */
    public record Violation(String stepId, String scriptPath, String reason) {}

    /**
     * Validate every SHELL / BACKEND / LongJob step's text for referenced
     * scripts, then for each referenced script check the header is present.
     *
     * @param manifest     the parsed recipe
     * @param scriptsRoot  the directory under which {@code scripts/...}
     *                     paths resolve. Pass {@code null} to skip
     *                     validation (test contexts that don't ship source).
     * @return list of violations (empty when the manifest is clean OR
     *         scriptsRoot is null)
     */
    public static List<Violation> validate(RecipeManifest manifest, Path scriptsRoot) {
        if (manifest == null || scriptsRoot == null) return List.of();
        var violations = new ArrayList<Violation>();
        for (var step : manifest.steps()) {
            for (var ref : extractScriptRefs(step)) {
                var scriptPath = scriptsRoot.resolve(stripLeadingScriptsPrefix(ref));
                if (!Files.isRegularFile(scriptPath)) {
                    violations.add(new Violation(step.id(), ref,
                        "script not found at " + scriptPath));
                    continue;
                }
                if (!hasLocalOkHeader(scriptPath)) {
                    violations.add(new Violation(step.id(), ref,
                        "missing '# " + HEADER_MARKER + "' header in first "
                        + HEADER_SCAN_LINES + " lines of " + scriptPath));
                }
            }
        }
        return List.copyOf(violations);
    }

    /** Compact one-line summary of violations for error messages / logs. */
    public static String summarize(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) return "no violations";
        var sb = new StringBuilder(violations.size() + " recipe-callable violations: ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append("; ");
            var v = violations.get(i);
            sb.append("[step=").append(v.stepId())
              .append(" script=").append(v.scriptPath())
              .append(" :: ").append(v.reason()).append("]");
        }
        return sb.toString();
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Extract script references from any of: SHELL.command / SHELL.rollback /
     *  BACKEND.prompt / LongJob.command. GATE / DECISION / GOOSE_RECIPE_REF
     *  steps carry no shell text and are skipped. */
    static Set<String> extractScriptRefs(RecipeStep step) {
        var refs = new LinkedHashSet<String>();
        switch (step) {
            case RecipeStep.Shell sh -> {
                addMatches(sh.command(), refs);
                if (sh.hasRollback()) addMatches(sh.rollback(), refs);
            }
            case RecipeStep.Backend b -> addMatches(b.prompt(), refs);
            case RecipeStep.LongJob lj -> addMatches(lj.command(), refs);
            default -> { /* GATE / DECISION / GOOSE_RECIPE_REF have no shell text */ }
        }
        return refs;
    }

    private static void addMatches(String text, Set<String> out) {
        if (text == null) return;
        var m = SCRIPT_REF.matcher(text);
        while (m.find()) out.add(m.group());
    }

    /** {@code scripts/classifier/foo.py} → {@code classifier/foo.py} for
     *  resolving against the scriptsRoot (which IS scripts/). */
    private static String stripLeadingScriptsPrefix(String ref) {
        return ref.startsWith("scripts/") ? ref.substring("scripts/".length()) : ref;
    }

    private static boolean hasLocalOkHeader(Path scriptPath) {
        try (var lines = Files.lines(scriptPath)) {
            return lines.limit(HEADER_SCAN_LINES)
                .anyMatch(l -> l.contains(HEADER_MARKER));
        } catch (IOException e) {
            return false;
        }
    }
}
