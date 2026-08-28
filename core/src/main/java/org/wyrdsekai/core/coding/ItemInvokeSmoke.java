package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * Invoke-once smoke for freshly built scripted items (2026-08-16,
 * steward-mandated: "otherwise we guarantee stuff will break").
 *
 * <p>The gap this closes: the registration pipeline parsed and
 * structurally validated the manifest but NEVER CALLED {@code invoke()}
 * — so a loader-valid item whose code crashes on first touch reached a
 * person's hands before anyone knew. This runs {@code invoke()} exactly
 * once, at registration, in the standard sandbox against the
 * no-side-effect {@link StubItemWorldApiProvider}, with placeholder
 * params synthesized from the manifest's own declarations.</p>
 *
 * <h2>Verdict taxonomy — honest about what a stub can prove</h2>
 * <ul>
 *   <li>{@link Verdict#PASS} — invoke returned without error. NOT proof
 *       of semantic correctness (a dice roller returning 0 every time
 *       still passes); it proves the code path executes.</li>
 *   <li>{@link Verdict#REJECT} — the item WILL break in a person's
 *       hands: compile failure, missing invoke(), own-code JS error,
 *       timeout (the stub answers instantly, so a timeout is an infinite
 *       loop), or resource exhaustion. Registering these guarantees
 *       breakage.</li>
 *   <li>{@link Verdict#INCONCLUSIVE} — the failure implicates the
 *       HARNESS, not the item: capability denial or a host-side
 *       execution error (a provider surface the stub doesn't model).
 *       These register with a warning — a stub limitation must never
 *       kill a legitimate item.</li>
 * </ul>
 */
public final class ItemInvokeSmoke {

    private static final Logger log = LoggerFactory.getLogger(ItemInvokeSmoke.class);

    public enum Verdict { PASS, REJECT, INCONCLUSIVE }

    public record Result(Verdict verdict, String detail) {}

    private ItemInvokeSmoke() {}

    /** Run the smoke. Never throws — an unexpected harness error is INCONCLUSIVE. */
    public static Result run(String itemId, String script, ItemManifest manifest) {
        try (var executor = new ItemScriptExecutor()) {
            var out = executor.execute(itemId, script,
                placeholderParams(manifest), StubItemWorldApiProvider.INSTANCE);
            return classify(out);
        } catch (Exception e) {
            log.warn("invoke-once smoke harness error for {}: {}", itemId, e.toString());
            return new Result(Verdict.INCONCLUSIVE, "smoke harness error: " + e.getMessage());
        }
    }

    /**
     * Placeholder params from the manifest's own declarations — the same
     * shapes a first real caller would send. Undeclared params stay
     * absent; a script that crashes on a missing OPTIONAL param would
     * crash identically in production.
     */
    static Map<String, Object> placeholderParams(ItemManifest manifest) {
        // Start from what a REAL caller sends. Items-as-tools items declare `commands`,
        // not `params`, so this used to hand invoke() an empty map — every such item was
        // smoked with no arguments, took its "nothing was asked" early return, and passed
        // without its actual work ever running.
        //
        // Live 2026-08-21: library_to_fairy passed the smoke, was registered, kept, and
        // handed over — and died on `world.llm.complete({...})` the first time someone
        // used it with a query. The smoke had never reached that line.
        var params = new HashMap<String, Object>(
            CarriedItemUse.params("did:key:smoke", firstCommandArgs(manifest)));
        if (manifest == null || manifest.params() == null) return params;
        for (var p : manifest.params()) {
            var type = p.type() == null ? "string" : p.type().toLowerCase();
            switch (type) {
                case "number", "integer", "int" -> params.put(p.name(), 1);
                case "boolean", "bool" -> params.put(p.name(), true);
                case "array", "list" -> params.put(p.name(), new Object[0]);
                default -> params.put(p.name(), "test");
            }
        }
        return params;
    }

    /**
     * A plausible argument string, preferring one the manifest itself declares.
     *
     * <p>A command's {@code args} is the author's own example of what gets typed, so it
     * is the best available stand-in. Blank ones (the no-arg default command) are skipped
     * — using them would put us straight back on the early-return path this exists to
     * get past.
     */
    static String firstCommandArgs(ItemManifest manifest) {
        if (manifest != null && manifest.commands() != null) {
            for (var c : manifest.commands()) {
                var a = c.args();
                if (a != null && !a.isBlank()) return a.trim();
            }
        }
        return "smoke test query";
    }

    static Result classify(Map<String, Object> out) {
        if (out == null) {
            return new Result(Verdict.REJECT, "invoke() produced no result");
        }
        // Capability gating is a harness property, not an item bug.
        if (out.containsKey("capability_denied")) {
            return new Result(Verdict.INCONCLUSIVE,
                "capability denied under smoke caps: " + out.get("capability_denied"));
        }
        var err = out.get("error");
        if (err == null) {
            return new Result(Verdict.PASS, "invoke() completed");
        }
        var msg = String.valueOf(err);
        // The executor's error strings are structured (see ItemScriptExecutor):
        // classify by class, defaulting host-side surprises to INCONCLUSIVE so
        // a stub gap can never kill a legitimate item.
        if (msg.startsWith("Execution error:")) {
            return new Result(Verdict.INCONCLUSIVE, msg);
        }
        // timed out (stub answers instantly → infinite loop), resource budget,
        // compile failure, missing invoke(), plain script error: all own-code.
        return new Result(Verdict.REJECT, msg);
    }
}
