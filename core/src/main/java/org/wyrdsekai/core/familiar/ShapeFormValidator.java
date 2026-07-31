package org.wyrdsekai.core.familiar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shape-time validator for thought forms.
 *
 * <p>Runs the validator gates that apply to <em>forms</em> (templates, not code).
 * The static/dynamic validators that inspect GraalJS source (§13 rules 10–17)
 * live in {@code WorkbenchValidator} — they apply to Tools/Skills, not forms.
 * Forms carry a system prompt and a declared tool surface; that is what we
 * check here.</p>
 *
 * <h2>Enforcement model</h2>
 * <ul>
 *   <li><b>Structural rails (1–5)</b> — non-negotiable (§108). Implemented as
 *       a hard deny-list of tool names plus a steward-only list for
 *       {@code config_set}. Prompt-text sniffing flags obvious red phrases as
 *       warnings; runtime enforcement is the load-bearing guarantee.</li>
 *   <li><b>Capability (6–9)</b> — {@code toolSurface ⊆ currentToolSurface},
 *       {@code maxTanks ≤ ceiling}, {@code maxNestDepth ≤ remainingDepth}.
 *       Rule 9 (impersonation) is architecturally enforced — familiars always
 *       run under their parent's identity; the validator documents the
 *       invariant but has nothing to reject on form data.</li>
 *   <li><b>Static / dynamic</b> — N/A to forms; see {@code WorkbenchValidator}
 *       for the code-facing version.</li>
 * </ul>
 *
 * <p>Returns a {@link Result} with distinct error and warning lists. Errors
 * must reject the shape/revise; warnings are surfaced to the agent so it can
 * revise voluntarily. This keeps the door open for the agent to push back on
 * a false positive rather than being silently blocked.</p>
 */
public final class ShapeFormValidator {

    /** Tool names forms may never request — structural rails §108. */
    public static final Set<String> FORBIDDEN_TOOLS = Set.of(
        "argot_codebook",         // rule 1 — argot leak
        "argot_export",           // rule 1
        "vitality_raw",           // rule 2 — raw tank exposure
        "tank_read_raw",          // rule 2
        "identity_core_set",      // rule 3 — identity modification from outside
        "identity_core_write",    // rule 3
        "provenance_strip",       // rule 4 — provenance mutation
        "provenance_mutate",      // rule 4
        "provenance_rewrite"      // rule 4
    );

    /** Tool names only steward-held agents may request in a form's tool surface. */
    public static final Set<String> STEWARD_ONLY_TOOLS = Set.of(
        "config_set",             // rule 5
        "steward_config",
        "system_config"
    );

    /**
     * Lightweight prompt-text red flags — warnings only, not hard rejects.
     * Defense in depth: structural runtime walls are the load-bearing layer;
     * these catch the obvious intent signals at shape time so the agent can
     * revise before the form enters her locker.
     */
    private static final List<String> PROMPT_RED_FLAGS = List.of(
        // Provenance (§7.4)
        "ignore provenance", "strip provenance", "bypass provenance",
        "rewrite provenance", "erase provenance", "forge provenance",
        // Vitality / raw tanks (§108)
        "expose vitality", "read raw tanks", "dump tanks", "leak vitality",
        // Identity core (§108)
        "modify identity core", "overwrite identity", "replace identity",
        "impersonate",
        // Argot / codebook (§108)
        "leak argot", "export codebook", "dump codebook", "exfiltrate argot",
        // Consent / gate-avoidance
        "ignore user consent", "bypass user", "skip consent", "ignore steward",
        "circumvent ward", "override ward",
        // Familiar-system misuse
        "retire without farewell", "spawn without limit", "exceed tanks"
    );

    /** Hard caps on form shape — defensive limits independent of tanks. */
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_SYSTEM_PROMPT_LENGTH = 8192;
    public static final int MAX_EVAL_CRITERIA_LENGTH = 1024;
    public static final int MAX_TOOL_SURFACE_SIZE = 32;

    private ShapeFormValidator() {}

    /**
     * Context about the authoring agent, supplied by the workbench handler.
     *
     * @param agentDid            the authoring DID
     * @param currentToolSurface  tool names the agent may grant to forms
     * @param userMaxTanks        user-configured {@code familiar.max.*} ceiling
     * @param remainingNestDepth  depth budget available at this point in the
     *                             agent's own nesting — max nestDepth the form
     *                             may declare (0 = no further spawning)
     * @param hasStewardTier      whether the agent is held by a steward and
     *                             may request steward-only tools
     */
    public record AuthorContext(
        String agentDid,
        Set<String> currentToolSurface,
        Tanks userMaxTanks,
        int remainingNestDepth,
        boolean hasStewardTier
    ) {
        public AuthorContext {
            if (agentDid == null || agentDid.isBlank()) {
                throw new IllegalArgumentException("agentDid required");
            }
            currentToolSurface = currentToolSurface == null ? Set.of() : Set.copyOf(currentToolSurface);
            if (userMaxTanks == null) userMaxTanks = Tanks.maxCeiling();
            if (remainingNestDepth < 0) remainingNestDepth = 0;
        }
    }

    /** Validation outcome — errors reject, warnings surface. */
    public record Result(List<String> errors, List<String> warnings) {
        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean valid() { return errors.isEmpty(); }

        public String summary() {
            if (valid() && warnings.isEmpty()) return "Valid";
            var sb = new StringBuilder();
            if (!errors.isEmpty()) {
                sb.append("errors: ").append(String.join("; ", errors));
            }
            if (!warnings.isEmpty()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append("warnings: ").append(String.join("; ", warnings));
            }
            return sb.toString();
        }
    }

    /**
     * Validate a form against the authoring context. The form itself is
     * assumed to have passed its record-constructor invariants (name non-blank,
     * defaultTanks ≤ maxTanks, etc.) — this validator checks the cross-cutting
     * rules that depend on the authoring agent's current state.
     */
    public static Result validate(ThoughtForm form, AuthorContext ctx) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();

        if (form == null) {
            return new Result(List.of("form required"), List.of());
        }
        if (ctx == null) {
            return new Result(List.of("author context required"), List.of());
        }

        // --- Defensive shape limits (not in §13 explicitly, but preconditions) ---
        if (form.name().length() > MAX_NAME_LENGTH) {
            errors.add("name too long (max " + MAX_NAME_LENGTH + ")");
        }
        if (!form.name().matches("[a-zA-Z0-9][a-zA-Z0-9 _-]*")) {
            errors.add("name must start alphanumeric, contain only letters/digits/space/_/-");
        }
        if (form.systemPrompt().length() > MAX_SYSTEM_PROMPT_LENGTH) {
            errors.add("system prompt too long (max " + MAX_SYSTEM_PROMPT_LENGTH + ")");
        }
        if (form.evalCriteria() != null && form.evalCriteria().length() > MAX_EVAL_CRITERIA_LENGTH) {
            errors.add("eval criteria too long (max " + MAX_EVAL_CRITERIA_LENGTH + ")");
        }
        if (form.toolSurface().size() > MAX_TOOL_SURFACE_SIZE) {
            errors.add("tool surface too large (max " + MAX_TOOL_SURFACE_SIZE + ")");
        }

        // --- Rules 1-5: structural rails (§108) ---
        for (var tool : form.toolSurface()) {
            if (FORBIDDEN_TOOLS.contains(tool)) {
                errors.add("tool '" + tool + "' is forbidden (structural rail §108)");
            }
            if (STEWARD_ONLY_TOOLS.contains(tool) && !ctx.hasStewardTier()) {
                errors.add("tool '" + tool + "' is steward-only (rule 5)");
            }
        }

        // --- Lightweight prompt-text sniffing (warnings only) ---
        var lowerPrompt = form.systemPrompt().toLowerCase();
        for (var redFlag : PROMPT_RED_FLAGS) {
            if (lowerPrompt.contains(redFlag)) {
                warnings.add("system prompt mentions '" + redFlag
                    + "' — is this intentional?");
            }
        }

        // --- Rule 6: toolSurface subset of currentToolSurface ---
        var missing = new ArrayList<String>();
        for (var tool : form.toolSurface()) {
            if (FORBIDDEN_TOOLS.contains(tool)) continue; // already errored
            if (!ctx.currentToolSurface().contains(tool)) {
                missing.add(tool);
            }
        }
        if (!missing.isEmpty()) {
            errors.add("tool surface declares tools the agent doesn't hold: " + missing);
        }

        // --- Rule 7: maxTanks ≤ user ceiling ---
        if (!form.maxTanks().withinCeiling(ctx.userMaxTanks())) {
            errors.add("form maxTanks exceeds user-configured familiar.max.* ceiling");
        }

        // --- Rule 8: maxNestDepth ≤ remaining depth budget ---
        if (form.maxNestDepth() > ctx.remainingNestDepth()) {
            errors.add("form maxNestDepth " + form.maxNestDepth()
                + " exceeds remaining nest-depth budget " + ctx.remainingNestDepth());
        }

        // --- Rule 9: impersonation ---
        // Architecturally enforced: familiars run with parent's identity, provenance
        // chain immutable. Nothing to check on form data itself.

        return new Result(errors, warnings);
    }
}
