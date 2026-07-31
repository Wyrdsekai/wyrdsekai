package org.wyrdsekai.scripting.codemode;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Track A Phase 1 — JS-script runtime for the {@code run_script}
 * tool. Phase 1 wires only the tool-call surface; the free-form prompt-shape
 * (where the model writes JS as completion) is deferred to Phase 2 pending a
 * 4B/9B parse-rate gate. Security boundary mirrors {@code ItemScriptExecutor}:
 * GraalJS isolate per call, no host class access, no IO, no fetch — only the
 * caller-supplied typed namespace plus a captured {@code console}.
 */
public final class CodeModeExecutor {

    private static final Logger log = LoggerFactory.getLogger(CodeModeExecutor.class);

    /** Default per-call timeout — improvisation tier (§10). */
    public static final long DEFAULT_TIMEOUT_MS = 5_000;

    /** Workbench (refinement) tier timeout — §5.1 "longer budgets in the studio". */
    public static final long WORKBENCH_TIMEOUT_MS = 30_000;

    /** Default per-call script byte cap — improvisation tier. */
    public static final int DEFAULT_MAX_SCRIPT_BYTES = 4 * 1024;

    /** Workbench (refinement) tier script byte cap — §5.1. */
    public static final int WORKBENCH_MAX_SCRIPT_BYTES = 16 * 1024;

    /**
     * composition tier. The workbench-as-studio
     * tier opts into longer timeouts, larger byte caps, and (in due course)
     * deeper validators (Phase 2b). Improvisation is the default temperature
     * in any room; the studio is reserved for deliberate refinement.
     */
    public enum WorkbenchTier {
        /** Default — improvisation in any room. 5s, 4KB cap. */
        IMPROVISATION,
        /** Workbench-only — refinement. 30s, 16KB cap, deeper validators (Phase 2b). */
        REFINEMENT;

        public long timeoutMs() {
            return this == REFINEMENT ? WORKBENCH_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
        }

        public int maxScriptBytes() {
            return this == REFINEMENT ? WORKBENCH_MAX_SCRIPT_BYTES : DEFAULT_MAX_SCRIPT_BYTES;
        }

        /** Journal label per spec §5.1.b — "refinement" vs "improvisation". */
        public String journalLabel() {
            return this == REFINEMENT ? "refinement" : "improvisation";
        }
    }

    private static final Engine SHARED_ENGINE = Engine.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build();

    /** Result of a single script run. */
    public record CodeModeResult(
        boolean success,
        List<String> log,
        String error,
        Object returnValue,
        long opsUsed,
        long durationMs
    ) {
        public static CodeModeResult ok(List<String> log, Object returnValue, long durationMs) {
            return new CodeModeResult(true, List.copyOf(log), null, returnValue, log.size(), durationMs);
        }
        public static CodeModeResult fail(List<String> log, String error, long durationMs) {
            return new CodeModeResult(false, List.copyOf(log), error, null, log.size(), durationMs);
        }
    }

    private CodeModeExecutor() {}

    /** Run with the default 5s timeout (improvisation tier). */
    public static CodeModeResult run(String script, Map<String, Map<String, Function<Object[], Object>>> namespace) {
        return run(script, namespace, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_SCRIPT_BYTES);
    }

    /**
     * Run with a {@link WorkbenchTier} controlling timeout and byte cap.
     * Convenience for callers (CompanionActor) that already know the tier
     * from the caller's current room —.
     */
    public static CodeModeResult run(String script,
                                     Map<String, Map<String, Function<Object[], Object>>> namespace,
                                     WorkbenchTier tier) {
        var t = tier == null ? WorkbenchTier.IMPROVISATION : tier;
        return run(script, namespace, t.timeoutMs(), t.maxScriptBytes());
    }

    /** Backward-compatible 3-arg form — no byte cap enforcement. */
    public static CodeModeResult run(
            String script,
            Map<String, Map<String, Function<Object[], Object>>> namespace,
            long timeoutMs) {
        return run(script, namespace, timeoutMs, Integer.MAX_VALUE);
    }

    /**
     * Execute {@code script} with the typed namespace bundle bound at top-level.
     *
     * <p>The bundle shape is {@code { namespaceName -> { methodName -> callable } }}.
     * Each callable receives the JS arguments as an {@code Object[]}; the return
     * value is converted back to a JS-friendly value by GraalJS automatically.
     *
     * <p>Captures {@code console.log/warn/error} into the result's log buffer.
     * Surfaces {@code [warn]} / {@code [error]} prefixes for non-info entries.
     *
     * <p>{@code script} is the JS source. The script is executed at top level; its
     * return value is the value of the last expression evaluated, accessible via
     * an injected {@code __cm_run()} wrapper that runs the user code as a function
     * body. Plain top-level scripts therefore work without requiring the model to
     * emit a wrapping function.
     */
    public static CodeModeResult run(
            String script,
            Map<String, Map<String, Function<Object[], Object>>> namespace,
            long timeoutMs,
            int maxScriptBytes) {
        if (script == null || script.isBlank()) {
            return CodeModeResult.fail(List.of(), "empty script", 0);
        }
        // b — byte cap. Improvisation tier 4KB; workbench
        // tier 16KB. Cap measured on UTF-8 bytes (script.length() is a chars
        // approximation, which is a sane upper bound for Latin-1-heavy JS).
        if (maxScriptBytes > 0 && script.length() > maxScriptBytes) {
            log.warn("CodeMode script exceeds byte cap: {} > {}", script.length(), maxScriptBytes);
            return CodeModeResult.fail(List.of(),
                "script too large: " + script.length() + " bytes > " + maxScriptBytes
                    + " cap (use refinement tier for larger scripts)", 0);
        }
        long start = System.currentTimeMillis();

        // TODO Phase 2: tighter sandbox policy via Context resourceLimits + statementLimit.
        //   Today the timeout (Thread.sleep + context.close(true)) is the only bound on
        //   runaway scripts. ItemScriptExecutor uses the same single-bound model in prod;
        // when hardening lands, both will adopt statementLimit.
        var hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .build();

        var capturedLog = new CopyOnWriteArrayList<String>();
        var opsCounter = new AtomicLong(0);

        Context ctx = Context.newBuilder("js")
            .engine(SHARED_ENGINE)
            .allowHostAccess(hostAccess)
            .allowIO(false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowHostClassLookup(name -> false)
            .allowHostClassLoading(false)
            .build();

        Thread timeoutThread = scheduleTimeout(ctx, timeoutMs);
        try {
            var bindings = ctx.getBindings("js");

            // Bind the captured console — info / warn / error.
            bindings.putMember("__cm_console", new ConsoleBridge(capturedLog, opsCounter));
            ctx.eval("js", """
                var console = {
                    log:   function() { __cm_console.log(Array.prototype.slice.call(arguments)); },
                    info:  function() { __cm_console.log(Array.prototype.slice.call(arguments)); },
                    warn:  function() { __cm_console.warn(Array.prototype.slice.call(arguments)); },
                    error: function() { __cm_console.err(Array.prototype.slice.call(arguments)); }
                };
                """);

            // Bind each namespace.method as a JS-callable host function.
            // ProxyExecutable + ProxyObject are the supported way to expose a
            // host function/object as JS-invokable in GraalJS — host classes
            // with @HostAccess.Export methods aren't callable as JS functions
            // on their own (you'd need .invoke() syntax in JS, which leaks
            // the binding shape).
            if (namespace != null) {
                for (var nsEntry : namespace.entrySet()) {
                    var nsName = nsEntry.getKey();
                    var methods = nsEntry.getValue();
                    if (nsName == null || nsName.isBlank() || methods == null) continue;
                    var memberMap = new LinkedHashMap<String, Object>();
                    for (var mEntry : methods.entrySet()) {
                        var mName = mEntry.getKey();
                        var fn = mEntry.getValue();
                        if (mName == null || mName.isBlank() || fn == null) continue;
                        memberMap.put(mName, makeProxyExecutable(nsName, mName, fn, opsCounter));
                    }
                    bindings.putMember(nsName, ProxyObject.fromMap(memberMap));
                }
            }

            // Wrap the user script in a function so we can capture its return value
            // and isolate it from the bindings scope (a stray top-level `return`
            // inside the user's script would otherwise be illegal).
            var wrapped = "(function(){\n" + script + "\n})()";
            var src = Source.newBuilder("js", wrapped, "code-mode-script.js").buildLiteral();

            var ret = ctx.eval(src);
            Object javaRet = valueToJava(ret);

            long elapsed = System.currentTimeMillis() - start;
            return CodeModeResult.ok(new ArrayList<>(capturedLog), javaRet, elapsed);

        } catch (PolyglotException pe) {
            long elapsed = System.currentTimeMillis() - start;
            String err;
            if (pe.isCancelled()) {
                err = "script timed out at op " + opsCounter.get() + " after " + timeoutMs + "ms";
            } else if (pe.isHostException()) {
                Throwable host = pe.asHostException();
                err = "host error: " + (host != null ? host.getMessage() : pe.getMessage());
            } else if (pe.isGuestException()) {
                err = "script error: " + pe.getMessage();
            } else {
                err = "polyglot error: " + pe.getMessage();
            }
            log.warn("CodeMode script failed: {}", err);
            return CodeModeResult.fail(new ArrayList<>(capturedLog), err, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("CodeMode unexpected error: {}", e.getMessage());
            return CodeModeResult.fail(new ArrayList<>(capturedLog),
                "execution error: " + e.getMessage(), elapsed);
        } finally {
            timeoutThread.interrupt();
            try {
                ctx.close(true);
            } catch (Exception ignored) {
                // already closed by timeout thread
            }
        }
    }

    /**
     * Console bridge — captures emit calls into a thread-safe buffer with prefix
     * markers. The script side is wired by an inline JS shim (see {@code run}).
     */
    public static final class ConsoleBridge {
        private final List<String> sink;
        private final AtomicLong ops;

        ConsoleBridge(List<String> sink, AtomicLong ops) {
            this.sink = sink;
            this.ops = ops;
        }

        @HostAccess.Export
        public void log(Value args) {
            ops.incrementAndGet();
            sink.add(formatArgs(args, null));
        }

        @HostAccess.Export
        public void warn(Value args) {
            ops.incrementAndGet();
            sink.add(formatArgs(args, "[warn]"));
        }

        @HostAccess.Export
        public void err(Value args) {
            ops.incrementAndGet();
            sink.add(formatArgs(args, "[error]"));
        }

        private static String formatArgs(Value args, String prefix) {
            var sb = new StringBuilder();
            if (prefix != null) sb.append(prefix).append(' ');
            if (args == null || args.isNull()) {
                return sb.toString().trim();
            }
            if (args.hasArrayElements()) {
                long n = args.getArraySize();
                for (long i = 0; i < n; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(stringify(args.getArrayElement(i)));
                }
            } else {
                sb.append(stringify(args));
            }
            return sb.toString();
        }

        private static String stringify(Value v) {
            if (v == null || v.isNull()) return "null";
            if (v.isString()) return v.asString();
            if (v.isBoolean()) return Boolean.toString(v.asBoolean());
            if (v.isNumber()) {
                if (v.fitsInLong()) return Long.toString(v.asLong());
                return Double.toString(v.asDouble());
            }
            if (v.hasArrayElements()) {
                var sb = new StringBuilder("[");
                long n = v.getArraySize();
                for (long i = 0; i < n; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(stringify(v.getArrayElement(i)));
                }
                return sb.append("]").toString();
            }
            if (v.hasMembers()) {
                var sb = new StringBuilder("{");
                boolean first = true;
                for (var k : v.getMemberKeys()) {
                    if (!first) sb.append(", ");
                    sb.append(k).append(": ").append(stringify(v.getMember(k)));
                    first = false;
                }
                return sb.append("}").toString();
            }
            return v.toString();
        }
    }

    /**
     * Build a {@link ProxyExecutable} that wraps a Java {@link Function}.
     * Each call increments the op counter, unwraps Value-typed args into
     * plain Java values (so handlers can deal with String/Map/List/Number
     * directly), and re-wraps the return value via {@link ProxyObject} /
     * {@link org.graalvm.polyglot.proxy.ProxyArray} when the handler returns
     * a Map or List — needed for property/index access from the JS side.
     */
    static ProxyExecutable makeProxyExecutable(
            String ns, String method,
            Function<Object[], Object> fn,
            AtomicLong ops) {
        return arguments -> {
            ops.incrementAndGet();
            Object[] javaArgs;
            if (arguments == null) {
                javaArgs = new Object[0];
            } else {
                javaArgs = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    javaArgs[i] = valueToJava(arguments[i]);
                }
            }
            Object result;
            try {
                result = fn.apply(javaArgs);
            } catch (RuntimeException re) {
                throw new RuntimeException(ns + "." + method + ": " + re.getMessage(), re);
            }
            return wrapForGuest(result);
        };
    }

    /**
     * Wrap a Java value so the JS side can use property / index syntax. Maps
     * become {@link ProxyObject}s, Lists become
     * {@link org.graalvm.polyglot.proxy.ProxyArray}s; primitives pass through.
     */
    @SuppressWarnings("unchecked")
    static Object wrapForGuest(Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) {
            var pmap = new LinkedHashMap<String, Object>();
            for (var e : m.entrySet()) {
                pmap.put(String.valueOf(e.getKey()), wrapForGuest(e.getValue()));
            }
            return ProxyObject.fromMap(pmap);
        }
        if (o instanceof List<?> list) {
            var arr = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = wrapForGuest(list.get(i));
            return ProxyArray.fromArray(arr);
        }
        return o;
    }

    private static Thread scheduleTimeout(Context ctx, long timeoutMs) {
        return Thread.ofVirtual()
            .name("code-mode-timeout")
            .start(() -> {
                try {
                    Thread.sleep(timeoutMs);
                    ctx.close(true);
                    log.warn("CodeMode script cancelled after {}ms timeout", timeoutMs);
                } catch (InterruptedException ie) {
                    // script finished before timeout
                }
            });
    }

    /**
     * If {@code o} is a {@link Value} with members, return them as a
     * String→Object map (recursively unwrapping nested values). Returns
     * {@code null} otherwise. Used by callers that can't see the polyglot
     * package directly (core doesn't transitively expose it).
     */
    public static Map<String, Object> unwrapValueMembers(Object o) {
        if (!(o instanceof Value v)) return null;
        if (!v.hasMembers()) return null;
        var out = new LinkedHashMap<String, Object>();
        for (var k : v.getMemberKeys()) {
            out.put(k, valueToJava(v.getMember(k)));
        }
        return out;
    }

    /** If {@code o} is a {@link Value} string, return it; null otherwise. */
    public static String unwrapValueString(Object o) {
        if (o instanceof Value v && v.isString()) return v.asString();
        return null;
    }

    /** Convert a guest Value to a Java-friendly object (mirrors ItemScriptExecutor). */
    public static Object valueToJava(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) {
            if (value.fitsInInt()) return value.asInt();
            if (value.fitsInLong()) return value.asLong();
            return value.asDouble();
        }
        if (value.isString()) return value.asString();
        if (value.hasArrayElements()) {
            var list = new ArrayList<Object>((int) value.getArraySize());
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(valueToJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            var map = new LinkedHashMap<String, Object>();
            for (var key : value.getMemberKeys()) {
                map.put(key, valueToJava(value.getMember(key)));
            }
            return map;
        }
        try {
            return value.asString();
        } catch (Exception e) {
            return value.toString();
        }
    }
}
