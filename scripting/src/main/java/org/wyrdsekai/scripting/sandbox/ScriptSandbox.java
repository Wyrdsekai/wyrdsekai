package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.scripting.api.WorldApi;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * GraalJS sandbox for running room scripts: no filesystem, network, threads or native access,
 * and every evaluation bounded by {@link ResourceLimits} (a statement limit and a CPU-time
 * watchdog). A hook that runs past its limit is stopped, a WARN names the room and the hook,
 * and the caller carries on as if the hook had failed.
 */
public class ScriptSandbox implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ScriptSandbox.class);

    private final Engine engine;
    private final String roomId;
    private final ResourceLimits limits;
    private final org.graalvm.polyglot.ResourceLimits statementLimit;   // null when unlimited

    public ScriptSandbox(String roomId) {
        this(roomId, ResourceLimits.ROOM_SCRIPT);
    }

    public ScriptSandbox(String roomId, ResourceLimits limits) {
        this.roomId = roomId;
        this.limits = limits == null ? ResourceLimits.ROOM_SCRIPT : limits;
        this.engine = Engine.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        this.statementLimit = this.limits.hasStatementLimit()
            ? org.graalvm.polyglot.ResourceLimits.newBuilder()
                .statementLimit(this.limits.statementLimit(), null)
                .build()
            : null;
    }

    public ResourceLimits limits() {
        return limits;
    }

    /**
     * Execute a script with the world API available as 'world'.
     *
     * @param script  JavaScript source code
     * @param worldApi The world API bindings for this room
     * @return Script result as a Value, or null on error
     */
    public Value execute(String script, WorldApi worldApi) {
        return run("top level", worldApi, null, context -> context.eval("js", script));
    }

    /**
     * Execute a named function from a previously loaded script.
     */
    public Value callFunction(String script, String functionName, WorldApi worldApi, Object... args) {
        return run(functionName, worldApi, null, context -> {
            context.eval("js", script);
            var fn = context.getBindings("js").getMember(functionName);
            if (fn == null || !fn.canExecute()) {
                // Optional lifecycle hooks (onEmote/onEnter/onLeave/…) are commonly undefined —
                // a missing hook is normal, not a warning-worthy condition.
                log.debug("Function {} not defined in room {} script (optional hook)", functionName, roomId);
                return null;
            }
            return fn.execute(args);
        });
    }

    /**
     * Execute a named function, ignoring the return value.
     * Used for fire-and-forget hooks like onEnter, onSay, etc.
     */
    public void callHook(String script, String functionName, WorldApi worldApi, Object... args) {
        callFunction(script, functionName, worldApi, args);
    }

    /**
     * Call the getHints() function and parse result into Hint records.
     * Returns empty if no function or no valid result.
     */
    public Optional<List<Hint>> callHintsFunction(String script, WorldApi worldApi) {
        // Must parse Values inside the same context — Values are invalidated when context closes
        return run("getHints", worldApi, Optional.<List<Hint>>empty(), context -> {
            context.eval("js", script);
            var fn = context.getBindings("js").getMember("getHints");
            if (fn == null || !fn.canExecute()) return Optional.empty();

            var result = fn.execute();
            if (result == null || !result.hasArrayElements()) return Optional.empty();

            var hints = new ArrayList<Hint>();
            for (long i = 0; i < result.getArraySize(); i++) {
                var elem = result.getArrayElement(i);
                var label = getMemberString(elem, "label", "");
                var intent = getMemberString(elem, "intent", "");
                var action = getMemberString(elem, "action", "say");
                var labelKey = getMemberString(elem, "labelKey", null);
                hints.add(new Hint(label, intent, action, labelKey));
            }
            return Optional.of(hints);
        });
    }

    private static String getMemberString(Value obj, String key, String defaultValue) {
        if (obj.hasMember(key)) {
            var member = obj.getMember(key);
            if (member != null && member.isString()) {
                return member.asString();
            }
        }
        return defaultValue;
    }

    /**
     * One bounded evaluation: a fresh context, the statement limit, the CPU watchdog, and a
     * clear log line when either stops the script. {@code what} names the hook for the log.
     */
    private <T> T run(String what, WorldApi worldApi, T onFailure, Function<Context, T> body) {
        try (var context = createContext(worldApi)) {
            var watch = limits.hasCpuTimeout()
                ? ScriptWatchdog.watch(context, limits.cpuTimeoutMs()) : null;
            try {
                return body.apply(context);
            } catch (PolyglotException e) {
                if (e.isResourceExhausted()) {
                    log.warn("Room {} script stopped in {}: it ran past its limit of {} statements"
                        + " (WYRDSEKAI_ROOM_SCRIPT_STATEMENTS)", roomId, what, limits.statementLimit());
                } else if (e.isCancelled() && watch != null && watch.tripped()) {
                    log.warn("Room {} script stopped in {}: it used more than {} ms of CPU time"
                        + " (WYRDSEKAI_ROOM_SCRIPT_CPU_MS)", roomId, what, limits.cpuTimeoutMs());
                } else {
                    log.error("Script {} failed in room {}: {}", what, roomId, e.getMessage());
                }
                return onFailure;
            } finally {
                if (watch != null) watch.close();
            }
        } catch (Exception e) {
            log.error("Script {} failed in room {}: {}", what, roomId, e.getMessage());
            return onFailure;
        }
    }

    Context createContext(WorldApi worldApi) {
        var builder = Context.newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                // e2e 2026-07-11: without these, world.mcp() results (java.util.Map)
                // read as undefined in room scripts — the Study shell's ls/cat
                // degraded to "Not found" while succeeding host-side. Matches
                // ItemScriptExecutor.createContext, which always set both.
                .allowMapAccess(true)
                .allowListAccess(true)
                .build())
            .allowIO(false)
            .allowCreateThread(false)
            .allowNativeAccess(false);
        if (statementLimit != null) {
            builder.resourceLimits(statementLimit);
        }

        var context = builder.build();
        context.getBindings("js").putMember("world", worldApi);
        return context;
    }

    /**
     * Execute a script and return the result as a String.
     * Safe for cross-module use — no GraalJS Value exposed.
     */
    public String executeAsString(String script, WorldApi worldApi) {
        return run("top level", worldApi, null, context -> {
            var result = context.eval("js", script);
            if (result == null || result.isNull()) return null;
            return result.isString() ? result.asString() : result.toString();
        });
    }

    @Override
    public void close() {
        engine.close();
    }
}
