package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.familiar.DynamicFormValidator;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Does this agent-authored script satisfy the items-as-tools contract?
 *
 * <p>The same three gates {@link CodingTaskItemBridge} applies before it will register a
 * backend-authored file, asked as a QUESTION rather than as a throw — so a caller can act
 * on the answer instead of only failing on it.
 *
 * <p><b>Why it exists.</b> The backend writes the item and the bridge decides whether the
 * item is real. Until now the only thing between them was a rejection log: goose produced a
 * file, the bridge refused it, and the work was silently downgraded to a plain artifact.
 * Measured live against the household 9B on 2026-08-20 — two runs, two different generated
 * files, the same refusal both times:
 *
 * <pre>
 *   contract REJECT ... item 'news_search_*' is missing the required `commands`
 *   block in its manifest. Declare at least one entry.
 * </pre>
 *
 * <p>The items-as-tools preamble already states this in the strongest terms it can
 * ("Forgetting the field is a hard reject — your file will not register"), and the model
 * omits it anyway. That is the third time in one day that prose in a contract failed to
 * produce compliance. So stop asking the model to get it right first time and give the
 * backend what it is actually good at: the defect, named, and another turn to fix it.
 *
 * <p>Two of the checks touch the RUNTIME, because text cannot answer what they ask:
 * whether the entrypoint can be CALLED ({@link #entrypointProblem}), and whether calling
 * it once BREAKS ({@link #runtimeProblem}). The latter honours only a definite
 * {@code REJECT} — an INCONCLUSIVE smoke implicates the stub harness rather than the
 * item, and inventing a complaint for a coder to chase would be worse than silence.
 */
public final class ItemContractCheck {

    private static final Logger log = LoggerFactory.getLogger(ItemContractCheck.class);

    private ItemContractCheck() {}

    /**
     * The first contract problem with this script, or empty when it would register.
     *
     * @param script the .js source the backend wrote
     * @param displayName name to use in the message when the manifest has none
     */
    /**
     * The first contract problem, or empty when there is none.
     *
     * <h2>One gate, not three</h2>
     * This used to run its own copy of the checks. When {@link #problems} gained the
     * loader's full {@code ItemManifestValidator.validate} on 2026-08-22, this did not —
     * so {@code isCompliant} still said yes to an item named {@code media-organizer} that
     * the loader would refuse, and the room object was named after a file that could never
     * register. A contract with more than one implementation is a contract that disagrees
     * with itself; this now asks {@link #problems} and takes the first answer.
     */
    public static Optional<String> firstProblem(String script, String displayName) {
        return problems(script, displayName).stream().findFirst();
    }

    /** True when this script would be accepted for registration. */
    public static boolean isCompliant(String script, String displayName) {
        return firstProblem(script, displayName).isEmpty();
    }

    /**
     * EVERY contract problem with this script, not just the first.
     *
     * <p>Naming one defect at a time is actively harmful when the reader is a model.
     * Measured 2026-08-20: handed the single complaint "missing the required `commands`
     * block", goose added a commands block and <b>deleted the embodiment block while doing
     * it</b> — trading one rejection for another. A repair prompt has to show the whole
     * contract state, or fixing A costs you B.
     */
    public static List<String> problems(String script, String displayName) {
        var out = new ArrayList<String>();
        if (script == null || script.isBlank()) {
            out.add("the file is empty — no manifest and no invoke().");
            return List.copyOf(out);
        }
        ItemManifest manifest = null;
        try {
            manifest = ItemManifestParser.parse(script);
        } catch (Exception e) {
            out.add("the manifest could not be parsed: " + e.getMessage());
            return List.copyOf(out);
        }
        var name = manifest != null && manifest.name() != null && !manifest.name().isBlank()
            ? manifest.name() : displayName;
        try {
            DynamicFormValidator.requireEmbodiment(script, name);
        } catch (Exception e) {
            out.add(e.getMessage());
        }
        try {
            ItemManifestValidator.requireCommands(manifest, /* allowMigration */ false, name);
        } catch (Exception e) {
            out.add(e.getMessage());
        }
        // The SAME validation the loader runs, not a subset of it. This check exists to
        // ask in advance the questions registration will ask, and it was asking fewer:
        // on 2026-08-22 it declared "repair SUCCEEDED — it will register" for an item
        // named `web-sight`, which the loader then refused over the hyphen. Every rule
        // the validator gains from here on is covered by the repair loop for free.
        for (var error : ItemManifestValidator.validate(manifest).errors()) {
            if (!out.contains(error)) out.add(error);
        }
        var entrypoint = entrypointProblem(script, name);
        entrypoint.ifPresent(out::add);
        // No point running code we already know has nothing to call.
        if (entrypoint.isEmpty()) {
            runtimeProblem(script, manifest, name).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /**
     * Is there an entrypoint the runtime can actually CALL?
     *
     * <h2>The join this closes</h2>
     * This class exists to ask, in advance, the questions {@link CodingTaskItemBridge}
     * will ask — so a defect can be handed back to the backend for one more turn instead
     * of only being logged as a refusal. For the entrypoint it asked a DIFFERENT question
     * than the bridge does, and the two disagreed.
     *
     * <p>Live on the household node 2026-08-21. goose wrote {@code library_query.js}
     * wrapped as {@code (function (exports) &#123; ... &#125;)(exports)} with
     * {@code function invoke(params)} inside the closure and only {@code exports.manifest}
     * exported. Every textual gate passed — {@code hasEntrypoint} looks for the substring
     * {@code "function invoke("} and it is right there — so the repair loop found nothing
     * to repair and shipped the file. The bridge then ran it and got
     * <i>"has no invoke() or execute() function"</i>, refused registration, and fell back
     * to placing a plain artifact. The steward picked up an item whose own description told
     * him to type {@code use library_query}, typed it, and was told no such object exists.
     *
     * <p>So the strict check has to BE the runtime. {@link
     * org.wyrdsekai.scripting.sandbox.ItemScriptExecutor#entrypointProblem} evaluates the
     * script and asks for the function without calling it; the cheap textual check stays as
     * a fast reject for the genuinely-absent case, where its message is more specific.
     *
     * <p>Failure to probe at all is NOT reported as a defect — a harness that cannot run
     * must never invent a complaint for a coder to chase.
     */
    public static Optional<String> entrypointProblem(String script, String name) {
        if (!ScriptedItemLoader.hasEntrypoint(script)) {
            return Optional.of("the script declares no invoke()/execute() entrypoint — "
                + "the item would be dead the first time anyone used it. Add "
                + "`function invoke(params) { ... }` that returns an object.");
        }
        try (var executor = new ItemScriptExecutor()) {
            return executor.entrypointProblem(
                name == null || name.isBlank() ? "contract-check" : name,
                script, StubItemWorldApiProvider.INSTANCE);
        } catch (Exception e) {
            log.debug("entrypoint probe unavailable for {}: {}", name, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Does the item BREAK when called? Empty unless it definitely would.
     *
     * <h2>Why the smoke is now (partly) a repairable defect</h2>
     * This class originally excluded {@link ItemInvokeSmoke} on the grounds that a smoke
     * failure can implicate the harness rather than the item — INCONCLUSIVE is not
     * something a coder can be asked to fix. That reasoning holds for INCONCLUSIVE and
     * only for INCONCLUSIVE. A <b>REJECT</b> is by definition own-code failure: the item
     * would break in the first person's hands.
     *
     * <p>Live 2026-08-21, the second item of the day. goose wrote {@code library_speaks}
     * whose {@code invoke} began:
     *
     * <pre>
     *   const &#123; world &#125; = params;
     *   if (!world) return &#123; ok: false, error: "invoke called outside a Wyrdsekai sandbox." &#125;;
     * </pre>
     *
     * <p>{@code world} is a GLOBAL in the sandbox, not a field of {@code params} — so the
     * guard fired on every call. The manifest was perfect, the entrypoint reachable, and
     * the file passed every check this class made; the bridge then ran it, got the item's
     * own error back, and refused registration. The steward was handed a tool whose
     * description told him what to type, and typing it fell through to the legacy router:
     * <i>"No artifacts known for goose task 2253334f…"</i>.
     *
     * <p>That is a defect a coder can fix in one turn, given the message. So hand it back.
     */
    private static Optional<String> runtimeProblem(String script, ItemManifest manifest,
                                                   String name) {
        try {
            var smoke = ItemInvokeSmoke.run(
                name == null || name.isBlank() ? "contract-check" : name, script, manifest);
            if (smoke.verdict() != ItemInvokeSmoke.Verdict.REJECT) return Optional.empty();
            // Only offer the world-is-a-global hint when the script actually does that.
            // Appending it to EVERY runtime failure is noise at best and misdirection at
            // worst: live 2026-08-21 an item failed with `ReferenceError: topic is not
            // defined` and was handed this advice twice, burning both repair rounds on a
            // diagnosis that had nothing to do with the fault.
            var hint = script.contains("world") && script.contains("params")
                    && script.matches("(?s).*\\{\\s*world\\s*\\}\\s*=\\s*params.*")
                ? " Note that `world` is a GLOBAL inside the sandbox — it is NOT a field"
                    + " of `params`, so `const { world } = params` leaves it undefined."
                    + " `params` carries only the caller's arguments"
                    + " (args/target/query/entityId/roomId)."
                : "";
            return Optional.of("calling invoke() once, in the real sandbox, failed: "
                + smoke.detail() + " The item would break the first time anyone used it."
                + " Fix exactly this error — do not restructure anything else." + hint);
        } catch (Exception e) {
            log.debug("runtime probe unavailable for {}: {}", name, e.toString());
            return Optional.empty();
        }
    }
}
