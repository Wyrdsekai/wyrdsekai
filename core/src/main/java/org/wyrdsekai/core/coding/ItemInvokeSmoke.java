package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * Every command the item declares, used in turn against a store that remembers, and what failed.
     * The single run above takes one command and a store that holds nothing. A placed backup vault
     * was "mended" with a pack command that threw on first use, then "mended" again by swapping the
     * arguments so that each pack kept the word "snapshots" and read back nothing, and answered
     * "ok" both times (household node, 2026-11-05). So: each command runs to the end; what it keeps
     * comes back under the name it reads by; a command that takes an id finds what was just kept,
     * and something shows that id. Empty when all of that holds.
     */
    public static List<String> failingUses(String itemId, String script, ItemManifest manifest) {
        var out = new ArrayList<String>();
        var uses = new ArrayList<String>();
        if (manifest != null && manifest.commands() != null) {
            for (var c : manifest.commands()) {
                var a = c.args() == null ? "" : c.args().trim();
                if (!uses.contains(a)) uses.add(a);
            }
        }
        if (uses.isEmpty()) uses.add(firstCommandArgs(manifest));
        // Commands that take an id go last, when there is something kept to name.
        var plain = uses.stream().filter(a -> !TAKES_AN_ID.matcher(a).find()).toList();
        var byId = uses.stream().filter(a -> TAKES_AN_ID.matcher(a).find()).toList();
        var store = new RememberingNotes();
        var provider = (ItemWorldApiProvider) Proxy.newProxyInstance(ItemWorldApiProvider.class.getClassLoader(),
            new Class<?>[] {ItemWorldApiProvider.class}, store);
        try (var executor = new ItemScriptExecutor()) {
            var answers = new StringBuilder();
            for (var args : plain) {
                var result = executor.execute(itemId, script, new HashMap<String, Object>(CarriedItemUse.params("did:key:smoke", args)), provider);
                answers.append(result).append('\n');
                var r = classify(result);
                if (r.verdict() == Verdict.REJECT) {
                    out.add("using it with \"" + args + "\" fails: " + r.detail()
                        + " — change the code so this command runs to the end.");
                }
            }
            var nameBack = "; world.notes.add takes the text first and a list of names second: world.notes.add(JSON.stringify(entry), [\""
                + itemId + "\"]). Read them back with world.notes.list(\"" + itemId + "\"); each comes back with its id and content.";
            for (var args : byId) {
                var kept = store.lastId();
                var used = kept == null ? args : TAKES_AN_ID.matcher(args).replaceFirst(Matcher.quoteReplacement(kept));
                var result = executor.execute(itemId, script, new HashMap<String, Object>(CarriedItemUse.params("did:key:smoke", used)), provider);
                var r = classify(result);
                if (r.verdict() == Verdict.REJECT) {
                    out.add("using it with \"" + args + "\" fails: " + r.detail()
                        + " — change the code so this command runs to the end.");
                } else if (kept != null && Boolean.FALSE.equals(result.get("ok"))) {
                    out.add("using it with \"" + used + "\", the id of what it just kept, it answers: "
                        + result.get("summary") + " — it has to find what it kept. Check how the id is cut from params.args" + nameBack);
                } else if (kept != null && answers.indexOf(kept) < 0) {
                    out.add("using it, the command \"" + args + "\" needs an id and no command shows the id of what it keeps: "
                        + "say the id in what the command that keeps it answers, or list them.");
                }
            }
            if (store.keptUnderAName() && store.readByAName() && store.namesMissEachOther()) {
                out.add("using it, what it keeps does not come back: it keeps entries under " + store.written
                    + " and reads them back by " + store.read + nameBack);
            }
        } catch (Exception e) {
            log.warn("invoke-every-use harness error for {}: {}", itemId, e.toString());
        }
        return out;
    }

    /** A command's own example of an argument that is an id: {@code restore <snapshot-id>}. */
    private static final Pattern TAKES_AN_ID = Pattern.compile("<[^>]*\\bid\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** The notes store, as the household's keeps them: under names, read back by name. Everything else is the stub. */
    private static final class RememberingNotes implements InvocationHandler {
        private final List<Map<String, Object>> notes = new ArrayList<>();
        final Set<String> written = new LinkedHashSet<>();
        final Set<String> read = new LinkedHashSet<>();
        private boolean keptAny;
        private String lastId;

        String lastId() { return lastId; }
        boolean keptUnderAName() { return keptAny; }
        boolean readByAName() { return !read.isEmpty(); }
        boolean namesMissEachOther() { return Collections.disjoint(written, read); }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "notesAdd" -> {
                    var content = (String) args[0];
                    if (content == null || content.isBlank()) return Map.of("ok", false, "error", "blank content");
                    var names = new ArrayList<String>();
                    if (args[1] != null) {
                        for (var t : (List<String>) args[1]) {
                            var name = WyrdLuceneStore.studyTag(t);
                            if (name != null) names.add(name);
                        }
                    }
                    lastId = "note:smoke:" + (notes.size() + 1);
                    keptAny = true;
                    written.addAll(names);
                    var note = new HashMap<String, Object>(Map.of("id", lastId, "content", content));
                    note.put("names", names);
                    notes.add(0, note);
                    return Map.of("ok", true, "id", lastId);
                }
                case "notesList" -> {
                    var name = WyrdLuceneStore.studyTag((String) args[0]);
                    if (name == null) return List.copyOf(notes);
                    read.add(name);
                    return notes.stream().filter(n -> ((List<String>) n.get("names")).contains(name)).toList();
                }
                default -> {
                    try {
                        return method.invoke(StubItemWorldApiProvider.INSTANCE, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
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
