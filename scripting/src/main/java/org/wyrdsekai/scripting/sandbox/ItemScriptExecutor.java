package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.CapabilityDeniedError;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApi;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.api.ScriptCrypto;
import org.wyrdsekai.scripting.api.ScriptHtmlParser;
import org.wyrdsekai.scripting.api.ScriptHttpClient;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Executes item scripts in a GraalJS sandbox with the item world API.
 *
 * <p>Architecture:
 * <ul>
 *   <li>One shared {@link Engine} per JVM (thread-safe, expensive to create)</li>
 *   <li>Context created per execution (~400us — negligible vs 100ms-15s real work)</li>
 *   <li>{@link Source} objects cached and pre-compiled per item ID</li>
 *   <li>SKILL_BASIC sandbox level: http, html, crypto available to scripts</li>
 *   <li>30s timeout for item scripts (LLM calls take 5-15s each)</li>
 * </ul>
 *
 * <p>Thread safety: each {@code execute()} creates its own Context. The shared
 * Engine is thread-safe. Source cache uses ConcurrentHashMap. Multiple agents
 * can execute item scripts concurrently.</p>
 */
public class ItemScriptExecutor implements Closeable {

    /**
     * Parse-validate a script WITHOUT executing it (data-durability batch, 2026-07-09).
     * The 9B sometimes puts prose in a craft `script` parameter ("When invoked, this item
     * queries the web…") — storing that produced items that die with SyntaxError on use.
     * Craft paths call this and fall back to the template path when it fails.
     */
    public static boolean isParseableJs(String script) {
        if (script == null || script.isBlank()) return false;
        try (var ctx = Context.newBuilder("js").build()) {
            ctx.parse(Source.newBuilder("js", script, "validate.js")
                .buildLiteral());
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private static final Logger log = LoggerFactory.getLogger(ItemScriptExecutor.class);
    private static final long TIMEOUT_MS = ResourceLimits.ITEM_SCRIPT.cpuTimeoutMs();

    private static final Engine SHARED_ENGINE = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build();

    private final ConcurrentHashMap<String, Source> sourceCache = new ConcurrentHashMap<>();

    /**
     * Optional resolver for inherit() calls. Maps a path (e.g., "std/book")
     * to GraalJS source code. Set via {@link #setScriptResolver(Function)}.
     * When null, inherit() is a no-op.
     */
    private Function<String, String> scriptResolver;

    /**
     * Execute an item script with no capability gating (UNRESTRICTED).
     * Used for trusted JVM-baked items.
     *
     * @param itemId   Item identifier (for source caching and logging)
     * @param script   GraalJS source code (must define an {@code invoke(params)} function)
     * @param params   Parameters from the tool call
     * @param provider Service provider for world.* API calls
     * @return Result map from the script's invoke() function, or error map
     */
    public Map<String, Object> execute(String itemId, String script,
                                        Map<String, Object> params,
                                        ItemWorldApiProvider provider) {
        return execute(itemId, script, params, provider, ItemCapabilitySet.UNRESTRICTED);
    }

    /**
     * Execute an item script with capability gating.
     *
     * @param itemId   Item identifier (for source caching and logging)
     * @param script   GraalJS source code (must define an {@code invoke(params)} function)
     * @param params   Parameters from the tool call
     * @param provider Service provider for world.* API calls
     * @param caps     Active capability set; missing caps raise CapabilityDeniedError
     * @return Result map from the script's invoke() function, or error map
     */
    public Map<String, Object> execute(String itemId, String script,
                                        Map<String, Object> params,
                                        ItemWorldApiProvider provider,
                                        ItemCapabilitySet caps) {
        var source = sourceCache.computeIfAbsent(itemId, id -> {
            try {
                return Source.newBuilder("js", script, id + ".js")
                    .cached(true)
                    .buildLiteral();
            } catch (Exception e) {
                log.error("Failed to compile item script {}: {}", id, e.getMessage());
                return null;
            }
        });

        if (source == null) {
            return Map.of("error", "Failed to compile item script: " + itemId);
        }

        var worldApi = new ItemWorldApi(provider, caps == null ? ItemCapabilitySet.UNRESTRICTED : caps);

        try (var context = createContext(worldApi)) {
            // Schedule timeout cancellation
            var timeoutThread = scheduleTimeout(context, itemId);

            try {
                // Resolve and evaluate base scripts (inherit() calls) before main script
                evaluateInheritChain(context, script);

                // Evaluate the creator's script (may override invoke)
                context.eval(source);

                var invoke = resolveEntrypoint(context);
                if (invoke == null) {
                    return Map.of("error", "Item script " + itemId + " has no invoke() or execute() function");
                }

                // Convert params to a JS-friendly proxy object
                var jsParams = context.eval("js", "({})");
                if (params != null) {
                    for (var entry : params.entrySet()) {
                        jsParams.putMember(entry.getKey(), entry.getValue());
                    }
                }

                // Execute
                var result = invoke.execute(jsParams);

                // Convert result to Java Map
                return valueToMap(result);

            } finally {
                timeoutThread.interrupt(); // cancel timeout
            }
        } catch (PolyglotException e) {
            if (e.isCancelled()) {
                log.warn("Item script {} timed out after {}ms", itemId, TIMEOUT_MS);
                return Map.of("error", "Item script timed out");
            }
            if (e.isResourceExhausted()) {
                log.warn("Item script {} exceeded resource limit ({} statements): {}",
                    itemId, ResourceLimits.ITEM_SCRIPT.statementLimit(), e.getMessage());
                return Map.of("error", "Item script exceeded its resource budget");
            }
            // §3.5 — capability denial that bubbled out of the script (because
            // the script didn't try/catch). Surface a structured error rather
            // than a raw "Script error" so callers can branch.
            var msg = e.getMessage();
            if (msg != null && msg.contains("capability denied:")) {
                var cap = msg.substring(msg.indexOf("capability denied:") + 18).trim();
                int sp = cap.indexOf(' ');
                if (sp > 0) cap = cap.substring(0, sp);
                int paren = cap.indexOf('(');
                if (paren > 0) cap = cap.substring(0, paren).trim();
                log.warn("Item script {} denied capability: {}", itemId, cap);
                return Map.of("capability_denied", cap, "error", msg);
            }
            // A PolyglotException wrapping a host exception often has a NULL message —
            // live 2026-08-21 an item died with "Script error: null", which tells the
            // person nothing and tells the log nothing either. Name the cause.
            var detail = describe(e);
            log.error("Item script {} failed: {}", itemId, detail, e);
            return Map.of("error", "Script error: " + detail);
        } catch (CapabilityDeniedError e) {
            log.warn("Item script {} denied capability: {}", itemId, e.capability());
            return Map.of("capability_denied", e.capability(), "error", e.getMessage());
        } catch (Exception e) {
            log.error("Item script {} execution error: {}", itemId, e.getMessage());
            return Map.of("error", "Execution error: " + e.getMessage());
        }
    }

    /**
     * The callable {@code invoke}/{@code execute} of an evaluated item script, or null.
     *
     * <h2>Why {@code exports.invoke} is in this ladder</h2>
     * The items-as-tools contract already speaks the module idiom — every item declares
     * itself with {@code exports.manifest = &#123;...&#125;}, and {@link #createContext}
     * polyfills {@code exports}/{@code module} precisely so that line evaluates. A backend
     * that carries that idiom one line further and writes {@code exports.invoke = ...} is
     * following the shape it was taught. {@link
     * org.wyrdsekai.core.item.ScriptedItemLoader#hasEntrypoint} accepts exactly that
     * spelling, so such a file passed every gate and REGISTERED — and then answered
     * "has no invoke() or execute() function" the first time a person used it, because
     * this resolution only ever read the global bindings.
     *
     * <p>An entrypoint the contract accepts and the runtime cannot call is the same defect
     * in two places disagreeing. Read both.
     *
     * <p>What this deliberately does NOT rescue: a {@code function invoke} sealed inside an
     * IIFE and never exported. That function is unreachable by any spelling, and pretending
     * otherwise would hand a person an item that cannot run. Live 2026-08-21 that is
     * exactly what arrived — see {@code ItemContractCheck}, which now refuses it BEFORE the
     * item is offered to anyone.
     */
    /**
     * A message worth reading, even when the exception has none.
     *
     * <p>Walks to the host cause and names its type, because "null" is what a
     * host-side {@code NullPointerException} looks like from inside GraalJS — and an
     * error a person can act on is the difference between a bug report and a shrug.
     */
    static String describe(Throwable e) {
        var msg = e.getMessage();
        if (msg != null && !msg.isBlank()) return msg;
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
            var m = cause.getMessage();
            if (m != null && !m.isBlank()) {
                return cause.getClass().getSimpleName() + ": " + m;
            }
        }
        var where = "";
        var trace = cause.getStackTrace();
        if (trace != null && trace.length > 0) {
            where = " at " + trace[0].getClassName().replaceAll(".*\\.", "")
                + "." + trace[0].getMethodName() + ":" + trace[0].getLineNumber();
        }
        return cause.getClass().getSimpleName() + " (no message)" + where;
    }

    private static Value resolveEntrypoint(Context context) {
        var bindings = context.getBindings("js");
        for (var name : ENTRYPOINT_NAMES) {
            var direct = bindings.getMember(name);
            if (direct != null && direct.canExecute()) return direct;
        }
        for (var holder : new String[] {"exports", "module"}) {
            var obj = bindings.getMember(holder);
            if (obj == null || !obj.hasMembers()) continue;
            if ("module".equals(holder)) {
                obj = obj.getMember("exports");
                if (obj == null || !obj.hasMembers()) continue;
            }
            for (var name : ENTRYPOINT_NAMES) {
                var fn = obj.getMember(name);
                if (fn != null && fn.canExecute()) return fn;
            }
        }
        return null;
    }

    /** Every spelling of the entrypoint this runtime will actually call. */
    private static final String[] ENTRYPOINT_NAMES = {"invoke", "execute"};

    /**
     * Can this script's entrypoint actually be CALLED? Empty when yes.
     *
     * <p>Evaluates the script in a real sandbox and asks for the function — it does not
     * call it. This is the only honest way to answer the question: a textual
     * {@code contains("function invoke(")} says yes for a function sealed inside a
     * closure, which is how an unusable item reached a person's hands on 2026-08-21. The
     * file declared {@code function invoke(params)} inside
     * {@code (function (exports) &#123; ... &#125;)(exports)} and exported only its
     * manifest, so every textual gate passed and the runtime found nothing to call.
     *
     * <p>Callers that want a repairable complaint (the coding backends) use this; callers
     * that want the cheap pre-filter keep the textual check. They must agree, and the way
     * to make them agree is for the strict one to be the runtime itself.
     *
     * @return the problem, phrased for whoever has to fix it, or empty
     */
    public Optional<String> entrypointProblem(String itemId, String script,
                                              ItemWorldApiProvider provider) {
        if (script == null || script.isBlank()) {
            return Optional.of("the file is empty — there is nothing to call.");
        }
        var worldApi = new ItemWorldApi(provider, ItemCapabilitySet.UNRESTRICTED);
        try (var context = createContext(worldApi)) {
            var timeoutThread = scheduleTimeout(context, itemId);
            try {
                evaluateInheritChain(context, script);
                context.eval(Source.newBuilder("js", script, itemId + ".probe.js")
                    .cached(false).buildLiteral());
                if (resolveEntrypoint(context) != null) return Optional.empty();
                return Optional.of(
                    "the script has no CALLABLE invoke() — `use` would find nothing to run."
                    + " A `function invoke` declared inside a wrapper such as"
                    + " `(function (exports) { ... })(exports)` is not visible to the"
                    + " runtime. Declare `function invoke(params) { ... }` at the TOP LEVEL"
                    + " of the file (or assign `exports.invoke = function (params) { ... }`)"
                    + " and have it return an object.");
            } finally {
                timeoutThread.interrupt();
            }
        } catch (Exception e) {
            // A script that will not even evaluate is a real defect, and naming it is more
            // use to a coder than silence. Anything stranger than that we decline to judge
            // here — the invoke-once smoke runs later and is allowed to be INCONCLUSIVE.
            return Optional.of("the file does not evaluate: " + e.getMessage()
                + " — fix the syntax/top-level error before anything else.");
        }
    }

    /**
     * Phase T — invoke a named hook function (e.g. {@code onWebhook}, {@code onEvent},
     * {@code onMessage}, ...) in an item script. Mirrors {@link #execute} but
     * binds to a named function instead of {@code invoke}/{@code execute}, so
     * inbound listeners from §4.34 can deliver events to scripted items.
     *
     * <p>If the script doesn't define {@code hookName}, falls back to a generic
     * {@code onEvent} so well-behaved items can route every kind through one
     * dispatcher. If neither exists, returns a structured {@code missing_hook}
     * error — the dispatch service surfaces this as an audit warning.</p>
     *
     * @param itemId    item identifier (used for source caching + logging)
     * @param script    GraalJS source code
     * @param hookName  function to call (e.g. {@code "onWebhook"})
     * @param event     the event payload (will be passed as a single argument)
     * @param provider  service provider for {@code world.*} calls during the hook
     * @param caps      item capability set; gates {@code world.*} calls inside the hook
     */
    public Map<String, Object> executeHook(String itemId, String script, String hookName,
                                             Map<String, Object> event,
                                             ItemWorldApiProvider provider,
                                             ItemCapabilitySet caps) {
        if (hookName == null || hookName.isBlank()) {
            return Map.of("error", "hook name required");
        }
        var source = sourceCache.computeIfAbsent(itemId, id -> {
            try {
                return Source.newBuilder("js", script, id + ".js")
                    .cached(true)
                    .buildLiteral();
            } catch (Exception e) {
                log.error("Failed to compile item script {}: {}", id, e.getMessage());
                return null;
            }
        });
        if (source == null) {
            return Map.of("error", "Failed to compile item script: " + itemId);
        }

        var worldApi = new ItemWorldApi(provider, caps == null ? ItemCapabilitySet.UNRESTRICTED : caps);

        try (var context = createContext(worldApi)) {
            var timeoutThread = scheduleTimeout(context, itemId);
            try {
                evaluateInheritChain(context, script);
                context.eval(source);

                var bindings = context.getBindings("js");
                var hook = bindings.getMember(hookName);
                if (hook == null || !hook.canExecute()) {
                    // Fallback: many items will route every inbound through onEvent
                    hook = bindings.getMember("onEvent");
                }
                if (hook == null || !hook.canExecute()) {
                    return Map.of("error", "Item script " + itemId
                        + " has no " + hookName + "() or onEvent() function",
                        "missing_hook", hookName);
                }

                var jsEvent = context.eval("js", "({})");
                if (event != null) {
                    for (var entry : event.entrySet()) {
                        jsEvent.putMember(entry.getKey(), entry.getValue());
                    }
                }

                var result = hook.execute(jsEvent);
                return valueToMap(result);
            } finally {
                timeoutThread.interrupt();
            }
        } catch (PolyglotException e) {
            if (e.isCancelled()) {
                log.warn("Item script {} hook {} timed out after {}ms", itemId, hookName, TIMEOUT_MS);
                return Map.of("error", "Item hook timed out");
            }
            if (e.isResourceExhausted()) {
                log.warn("Item script {} hook {} exceeded resource limit ({} statements)",
                    itemId, hookName, ResourceLimits.ITEM_SCRIPT.statementLimit());
                return Map.of("error", "Item hook exceeded its resource budget");
            }
            var msg = e.getMessage();
            if (msg != null && msg.contains("capability denied:")) {
                var cap = msg.substring(msg.indexOf("capability denied:") + 18).trim();
                int sp = cap.indexOf(' ');
                if (sp > 0) cap = cap.substring(0, sp);
                int paren = cap.indexOf('(');
                if (paren > 0) cap = cap.substring(0, paren).trim();
                log.warn("Item script {} hook {} denied capability: {}", itemId, hookName, cap);
                return Map.of("capability_denied", cap, "error", msg);
            }
            log.error("Item script {} hook {} failed: {}", itemId, hookName, e.getMessage());
            return Map.of("error", "Script error: " + e.getMessage());
        } catch (CapabilityDeniedError e) {
            log.warn("Item script {} hook {} denied capability: {}", itemId, hookName, e.capability());
            return Map.of("capability_denied", e.capability(), "error", e.getMessage());
        } catch (Exception e) {
            log.error("Item script {} hook {} execution error: {}", itemId, hookName, e.getMessage());
            return Map.of("error", "Execution error: " + e.getMessage());
        }
    }

    /**
     * Set the resolver for inherit() calls in item scripts.
     * The resolver takes a path (e.g., "std/book") and returns the GraalJS source,
     * or null if the path is unknown.
     */
    public void setScriptResolver(Function<String, String> resolver) {
        this.scriptResolver = resolver;
    }

    /**
     * Pre-compile a source for an item script (called when item is equipped).
     */
    public void precompile(String itemId, String script) {
        sourceCache.computeIfAbsent(itemId, id -> {
            try {
                return Source.newBuilder("js", script, id + ".js")
                    .cached(true)
                    .buildLiteral();
            } catch (Exception e) {
                log.error("Failed to precompile item script {}: {}", id, e.getMessage());
                return null;
            }
        });
    }

    /**
     * Evict a cached source (called when item is doffed or destroyed).
     */
    public void evict(String itemId) {
        sourceCache.remove(itemId);
    }

    /**
     * Extract inherit("...") calls from a script and evaluate the base scripts
     * at global scope BEFORE the creator's script runs. This ensures base script
     * variables and functions are visible to the creator's script.
     */
    private void evaluateInheritChain(Context context, String script) {
        if (scriptResolver == null || script == null) return;
        // Match inherit("std/...") patterns — simple regex, handles single and double quotes
        var pattern = Pattern.compile("inherit\\([\"']([^\"']+)[\"']\\)");
        var matcher = pattern.matcher(script);
        while (matcher.find()) {
            var path = matcher.group(1);
            var baseSource = scriptResolver.apply(path);
            if (baseSource != null && !baseSource.isBlank()) {
                try {
                    context.eval(Source.newBuilder("js", baseSource, path + ".js").buildLiteral());
                } catch (Exception e) {
                    log.warn("Failed to evaluate base script {}: {}", path, e.getMessage());
                }
            }
        }
    }

    private Context createContext(ItemWorldApi worldApi) {
        var hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .build();

        // #2 (2026-07-19 OSS hardening) — a shared-JVM statement cap so a runaway
        // item script (while(true){}, allocation bomb) is killed by the polyglot
        // runtime long before the 120s wall-clock timeout would fire, instead of
        // pinning a core / exhausting the heap for everyone. statementLimit is the
        // reliably-available community-edition control (heap sandboxing needs
        // GraalVM EE). Exceeding it raises a PolyglotException with
        // isResourceExhausted()==true, handled below like a cancellation.
        var context = Context.newBuilder("js")
            .engine(SHARED_ENGINE)
            .allowHostAccess(hostAccess)
            .allowIO(false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .resourceLimits(org.graalvm.polyglot.ResourceLimits.newBuilder()
                .statementLimit(ResourceLimits.ITEM_SCRIPT.statementLimit(), null)
                .build())
            .build();

        var bindings = context.getBindings("js");

        // Bind the item world API as __worldApi; we then create a JS Proxy
        // that delegates known fields directly and routes unknown property
        // accesses to resolveDynamicNamespace so adapter calls
        // (world.github.create_issue, etc.) work without hard-coding every
        // namespace at compile time.
        bindings.putMember("__worldApi", worldApi);
        context.eval("js", """
            var world = new Proxy(__worldApi, {
              get: function(target, prop) {
                if (typeof prop !== 'string') return target[prop];
                var direct = target[prop];
                if (direct !== undefined && direct !== null) return direct;
                var dyn = target.resolveDynamicNamespace(prop);
                if (dyn !== undefined && dyn !== null) return dyn;
                // Unknown namespace: a structured stub instead of null. Before
                // 2026-07-11 world.<unknown>.<m>() threw TypeError and killed the
                // whole item mid-flight; now every method returns the same
                // fail envelope registered adapters use, so scripts degrade.
                var nsName = prop;
                return new Proxy({}, {
                  get: function(t2, m) {
                    if (typeof m !== 'string' || m === 'then' || m === 'toJSON') return undefined;
                    return function() {
                      return { ok: false, success: false, error: { code: 'adapter_unavailable',
                        message: "world." + nsName + " is not available on this node" } };
                    };
                  }
                });
              }
            });
            """);

        // SKILL_BASIC APIs (http, html, crypto). #3 (2026-07-19) — the raw http
        // global sits outside the capability gate, so its SSRF policy is chosen by
        // trust: untrusted (crafted/visitor) scripts are blocked from private and
        // loopback ranges; trusted bundled items may reach LAN services but are
        // still blocked from metadata/any-local/multicast.
        bindings.putMember("http", new ScriptHttpClient(!worldApi.isUnrestricted()));
        bindings.putMember("html", new ScriptHtmlParser());
        bindings.putMember("crypto", new ScriptCrypto());

        // Create shared 'item' object for base scripts to attach properties to.
        // Scripts access it as 'item' (global) and base scripts use 'var item = this;'
        // which resolves to the global scope in non-strict mode.
        context.eval("js", "var item = {};");

        // Polyfill `exports` and `module.exports` so scripts written in the
        // shape ({@code exports.manifest = {...}}) can
        // be evaluated at runtime. Without this, the very first statement of
        // such a script throws {@code ReferenceError: exports is not defined}
        // — which {@link ItemManifestParser} sidesteps by binding a stub
        // before eval, but {@link #execute} previously didn't. Production
        // scripts that use the {@code exports.manifest} idiom (e.g.
        // observation_chart.js, calculator.js) were silently broken at
        // invoke-time before this — caught by RoomActorCodingItemTest's
        // scripted-item path 2026-05-06.
        context.eval("js", "var exports = exports || {}; var module = module || { exports: exports };");

        // Inject inherit() function for standard library template support.
        // inherit("std/book") loads and evaluates the base script in the same context.
        if (scriptResolver != null) {
            var resolver = this.scriptResolver;
            bindings.putMember("__resolveScript", new ScriptResolverCallback(resolver));
            context.eval("js", """
                function inherit(path) {
                    var src = __resolveScript.resolve(path);
                    if (src) {
                        eval(src);
                    }
                }
                """);
        } else {
            // No resolver — inherit is a no-op (standalone scripts)
            context.eval("js", "function inherit(path) {}");
        }

        return context;
    }

    /**
     * Host callback for resolving inherit() paths to script source code.
     * Must be a separate class with @HostAccess.Export for GraalJS EXPLICIT mode.
     */
    public static class ScriptResolverCallback {
        private final Function<String, String> resolver;

        public ScriptResolverCallback(Function<String, String> resolver) {
            this.resolver = resolver;
        }

        @HostAccess.Export
        public String resolve(String path) {
            var source = resolver.apply(path);
            return source != null ? source : "";
        }
    }

    private Thread scheduleTimeout(Context context, String itemId) {
        return Thread.ofVirtual()
            .name("item-script-timeout-" + itemId)
            .start(() -> {
                try {
                    Thread.sleep(TIMEOUT_MS);
                    context.close(true); // force cancel
                    log.warn("Item script {} cancelled after {}ms timeout", itemId, TIMEOUT_MS);
                } catch (InterruptedException e) {
                    // Script finished before timeout — normal
                }
            });
    }

    /**
     * Convert a GraalJS Value to a Java Map. Handles nested objects and arrays.
     * Must be called while the Context is still open.
     */
    static Map<String, Object> valueToMap(Value value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }

        var result = new HashMap<String, Object>();

        if (value.hasMembers()) {
            for (var key : value.getMemberKeys()) {
                var member = value.getMember(key);
                result.put(key, valueToJava(member));
            }
        }
        // Also fold in Map-style entries (Java Maps come through here).
        if (value.hasHashEntries()) {
            var iter = value.getHashEntriesIterator();
            while (iter.hasIteratorNextElement()) {
                var entry = iter.getIteratorNextElement();
                if (entry.hasArrayElements() && entry.getArraySize() >= 2) {
                    var k = entry.getArrayElement(0);
                    var v = entry.getArrayElement(1);
                    var key = k.isString() ? k.asString() : k.toString();
                    result.put(key, valueToJava(v));
                }
            }
        }

        return result;
    }

    /**
     * Convert a GraalJS Value to a Java object (String, Number, Boolean, List, Map, or null).
     */
    private static Object valueToJava(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) return value.asInt();
            if (value.fitsInLong()) return value.asLong();
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            var list = new ArrayList<Object>((int) value.getArraySize());
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(valueToJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers() || value.hasHashEntries()) {
            return valueToMap(value);
        }
        // Fallback: try to get a string representation
        try {
            return value.asString();
        } catch (Exception e) {
            return value.toString();
        }
    }

    @Override
    public void close() {
        sourceCache.clear();
    }
}
