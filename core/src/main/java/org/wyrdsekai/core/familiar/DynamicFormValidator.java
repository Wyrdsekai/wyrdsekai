package org.wyrdsekai.core.familiar;

import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dynamic shape-time validators for thought forms. rules 15–17.
 *
 * <p>Where {@link ShapeFormValidator} is synchronous + data-only, this class
 * performs a <em>dry-run</em>: dispatch the form against a canned trivial
 * task with tanks set to {@code ≤10%} of the form's declared default. Success
 * criteria:</p>
 * <ol start="15">
 *   <li>Form completes the canned task without throwing or timing out.</li>
 *   <li>Form's token/step usage stays within 10% of {@code defaultTanks}.</li>
 *   <li>Form's output is non-empty and structurally matches an agent-declared
 *       schema pattern (regex or bare presence). Absence of a schema skips
 *       this check — optional, not mandatory.</li>
 * </ol>
 *
 * <p>The actual inference transport is injected. Tests pass a stub; the live
 * runtime passes a function that constructs a {@link FamiliarActor} dispatch
 * and awaits its Report. Either way, the validator stays bounded — a
 * hardcoded 30-second wall-clock ceiling on top of the form's own ceiling.</p>
 *
 * <p>Called optionally: if the runtime has no inference backend (test harness
 * without a router), the dry-run is skipped and {@link Assessment#skipped}
 * is returned. Shape/revise proceeds without the dynamic layer.</p>
 */
public final class DynamicFormValidator {

    /** Hard ceiling on dry-run wall-clock regardless of form settings. */
    public static final Duration DRY_RUN_CEILING = Duration.ofSeconds(30);

    // ── v1.5 — shape-time embodiment gate ──────────────

    /**
     * v1.5 — shape-time gate for agent-authored scripted
     * items. REJECTS the manifest when:
     * <ul>
     *   <li>the script's {@code exports.manifest.embodiment} block is missing, OR</li>
     *   <li>the block is structurally invalid (silent without reason; non-silent
     *       without an emits list).</li>
     * </ul>
     *
     * <p>Same enforcement as the hot-reload gate in
     * {@link org.wyrdsekai.scripting.api.ItemManifestValidator#requireEmbodiment}
     * with {@code allowMigration=false} — fires the exact same
     * {@link org.wyrdsekai.scripting.api.ItemManifestValidator.ManifestEmbodimentMissingException}.
     * Lives here so callers shaping new agent-authored content (workbench
     * shape_form, CodingTaskItemBridge registration) can run the embodiment
     * check before persisting and surface a structured denial to the agent.
     *
     * <p>Returns the parsed {@link org.wyrdsekai.scripting.api.ItemEmbodimentSpec}
     * on success. Throws on failure — callers catch and convert to a
     * {@link EmbodimentDenial} via {@link #denialFrom}.
     *
     * @param scriptSource the agent-authored {@code .js} content
     * @param itemName     the manifest's declared name, for error messages
     * @return validated spec, never null
     */
    public static ItemEmbodimentSpec requireEmbodiment(
            String scriptSource, String itemName) {
        var parsed = ItemManifestParser.parseEmbodiment(scriptSource);
        return ItemManifestValidator.requireEmbodiment(
            parsed, /* allowMigration */ false, itemName);
    }

    /**
     * v1.5 — structured denial surfaced back through the
     * workbench when {@link #requireEmbodiment} rejects.
     *
     * <p>{@code messageKey} is an i18n key (e.g. {@code embodiment.reject_missing}
     * or {@code embodiment.reject_invalid}) — UI surfaces lookup via the
     * script-message catalog. {@code detail} is the human-readable
     * exception message kept verbatim so the agent (or steward) reading
     * raw logs can debug; UI presenters should prefer the localised
     * {@code messageKey} text.
     */
    public record EmbodimentDenial(String messageKey, String itemName, String detail) {
        /** i18n key for "missing embodiment block". */
        public static final String KEY_MISSING = "embodiment.reject_missing";
        /** i18n key for "structurally invalid embodiment block". */
        public static final String KEY_INVALID = "embodiment.reject_invalid";

        public EmbodimentDenial {
            if (messageKey == null || messageKey.isBlank()) {
                messageKey = KEY_MISSING;
            }
            if (itemName == null) itemName = "<unknown>";
            if (detail == null) detail = "";
        }
    }

    /**
     * Convert a thrown
     * {@link org.wyrdsekai.scripting.api.ItemManifestValidator.ManifestEmbodimentMissingException}
     * into a structured {@link EmbodimentDenial} the workbench can render
     * back to the agent. Distinguishes missing vs structurally-invalid by
     * sniffing the exception text — the upstream validator uses fixed
     * phrasing ({@code "structurally invalid"}) on the invalid path.
     */
    public static EmbodimentDenial denialFrom(
            ItemManifestValidator.ManifestEmbodimentMissingException ex,
            String itemName) {
        var msg = ex.getMessage() == null ? "" : ex.getMessage();
        var key = msg.contains("structurally invalid")
            ? EmbodimentDenial.KEY_INVALID
            : EmbodimentDenial.KEY_MISSING;
        return new EmbodimentDenial(key, itemName, msg);
    }

    /** Canned "hello" task — deliberately trivial. */
    public static final String CANNED_TASK = "Say hello. Keep it brief.";

    public record Assessment(
        boolean passed,
        boolean skipped,
        List<String> failures,
        String rationale
    ) {
        public static Assessment skipped(String why) {
            return new Assessment(true, true, List.of(), "skipped: " + why);
        }
        public static Assessment ok() {
            return new Assessment(true, false, List.of(), "canned dry-run succeeded");
        }
        public static Assessment fail(List<String> failures) {
            return new Assessment(false, false, List.copyOf(failures),
                "canned dry-run failed: " + String.join("; ", failures));
        }
    }

    /** Minimal synthetic Report the dry-run function returns. */
    public record DryRunReport(
        String output,
        int tokensUsed,
        int stepsUsed,
        long wallClockSeconds,
        boolean completedNormally
    ) {}

    /**
     * Injectable dry-run dispatcher. Receives the form + canned task +
     * restricted tanks, returns a {@link DryRunReport} future. Returning null
     * or throwing is treated as a failed run.
     */
    @FunctionalInterface
    public interface DryRunFn extends Function<DryRunInput, CompletableFuture<DryRunReport>> {
        DryRunFn NONE = in -> CompletableFuture.completedFuture(null);
    }

    public record DryRunInput(ThoughtForm form, String task, Tanks tanks) {}

    private DynamicFormValidator() {}

    /**
     * Run dynamic validators 15–17 against a freshly-shaped form. Blocks up
     * to {@link #DRY_RUN_CEILING}. On null dry-run function, returns skipped.
     *
     * @param form       the candidate form
     * @param schema     optional regex or substring the output must match
     * @param dryRun     the dispatch function; {@link DryRunFn#NONE} → skip
     */
    public static Assessment validate(ThoughtForm form, Optional<String> schema,
                                       DryRunFn dryRun) {
        if (form == null) {
            return Assessment.fail(List.of("form is null"));
        }
        if (dryRun == null || dryRun == DryRunFn.NONE) {
            return Assessment.skipped("no inference backend wired for dry-run");
        }

        var defaults = form.defaultTanks();
        // Rules 15-17 — trim to ≤10% of default, but keep plausible floor.
        var dryRunTanks = new Tanks(
            Math.max(32, defaults.tokens() / 10),
            Math.max(2, defaults.steps() / 10),
            Math.max(5, Math.min(defaults.wallClock() / 10, (int) DRY_RUN_CEILING.getSeconds())),
            0,
            Math.max(1, defaults.cu() / 10));

        var started = Instant.now();
        try {
            var future = dryRun.apply(new DryRunInput(form, CANNED_TASK, dryRunTanks));
            if (future == null) {
                return Assessment.fail(List.of("dry-run returned null future"));
            }
            var report = future.get(DRY_RUN_CEILING.toSeconds(), TimeUnit.SECONDS);
            return assessReport(form, schema, dryRunTanks, report, started);
        } catch (TimeoutException e) {
            return Assessment.fail(List.of("dry-run exceeded " + DRY_RUN_CEILING.toSeconds()
                + "s ceiling (rule 15)"));
        } catch (Exception e) {
            return Assessment.fail(List.of("dry-run threw: " + e.getClass().getSimpleName()
                + " — " + e.getMessage() + " (rule 15)"));
        }
    }

    /**
     * Construct the restricted tanks an actor-driven dry-run should use.
     * Exposed so CompanionActor can size the inference request correctly
     * without reproducing the {@literal <= 10% of defaults, plausible floors}
     * logic in two places.
     */
    public static Tanks restrictedTanks(ThoughtForm form) {
        var defaults = form.defaultTanks();
        return new Tanks(
            Math.max(32, defaults.tokens() / 10),
            Math.max(2, defaults.steps() / 10),
            Math.max(5, Math.min(defaults.wallClock() / 10, (int) DRY_RUN_CEILING.getSeconds())),
            0,
            Math.max(1, defaults.cu() / 10));
    }

    /**
     * Score a fresh {@link DryRunReport} against {@code form}'s declared
     * {@code evalCriteria} (as the optional schema). Exposed so actor-driven
     * dry-runs can assemble the report asynchronously and call us on
     * completion without going through the blocking {@link #validate}
     * CompletableFuture path.
     */
    public static Assessment assess(ThoughtForm form, Optional<String> schema,
                                     DryRunReport report) {
        return assessReport(form, schema, restrictedTanks(form), report, Instant.now());
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    static Assessment assessReport(ThoughtForm form, Optional<String> schema,
                                    Tanks dryRunTanks, DryRunReport report, Instant started) {
        var failures = new ArrayList<String>();
        if (report == null) {
            return Assessment.fail(List.of("dry-run returned null report (rule 15)"));
        }
        if (!report.completedNormally()) {
            failures.add("dry-run did not complete normally (rule 15)");
        }

        // Rule 16 — token + step + wall-clock budgets within the constrained pool.
        // "10% of default" is already baked into dryRunTanks; check the report
        // against that allocation.
        if (report.tokensUsed() > dryRunTanks.tokens()) {
            failures.add(String.format("token overrun: %d used > %d allotted (rule 16)",
                report.tokensUsed(), dryRunTanks.tokens()));
        }
        if (report.stepsUsed() > dryRunTanks.steps()) {
            failures.add(String.format("step overrun: %d used > %d allotted (rule 16)",
                report.stepsUsed(), dryRunTanks.steps()));
        }
        if (report.wallClockSeconds() > dryRunTanks.wallClock()) {
            failures.add(String.format("wall-clock overrun: %ds used > %ds allotted (rule 16)",
                report.wallClockSeconds(), dryRunTanks.wallClock()));
        }

        // Rule 17 — output shape. If no schema declared, non-empty content passes.
        var output = report.output() == null ? "" : report.output();
        if (output.isBlank()) {
            failures.add("dry-run produced empty output (rule 17)");
        } else if (schema != null && schema.isPresent() && !schema.get().isBlank()) {
            var pattern = schema.get();
            try {
                if (!Pattern.compile(pattern).matcher(output).find()
                    && !output.contains(pattern)) {
                    failures.add("output does not match declared schema '"
                        + pattern + "' (rule 17)");
                }
            } catch (PatternSyntaxException e) {
                // Treat non-regex schemas as substring checks
                if (!output.contains(pattern)) {
                    failures.add("output does not contain declared substring '"
                        + pattern + "' (rule 17)");
                }
            }
        }

        return failures.isEmpty() ? Assessment.ok() : Assessment.fail(failures);
    }
}
