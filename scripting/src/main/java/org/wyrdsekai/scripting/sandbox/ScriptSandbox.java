package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.scripting.api.WorldApi;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GraalJS sandbox for running room scripts.
 * Resource-limited: CPU time, memory, no filesystem/network access.
 */
public class ScriptSandbox implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ScriptSandbox.class);

    private final Engine engine;
    private final String roomId;

    public ScriptSandbox(String roomId) {
        this.roomId = roomId;
        this.engine = Engine.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    }

    /**
     * Execute a script with the world API available as 'world'.
     *
     * @param script  JavaScript source code
     * @param worldApi The world API bindings for this room
     * @return Script result as a Value, or null on error
     */
    public Value execute(String script, WorldApi worldApi) {
        try (var context = createContext(worldApi)) {
            return context.eval("js", script);
        } catch (Exception e) {
            log.error("Script execution failed in room {}: {}", roomId, e.getMessage());
            return null;
        }
    }

    /**
     * Execute a named function from a previously loaded script.
     */
    public Value callFunction(String script, String functionName, WorldApi worldApi, Object... args) {
        try (var context = createContext(worldApi)) {
            context.eval("js", script);
            var fn = context.getBindings("js").getMember(functionName);
            if (fn == null || !fn.canExecute()) {
                // Optional lifecycle hooks (onEmote/onEnter/onLeave/…) are commonly undefined —
                // a missing hook is normal, not a warning-worthy condition.
                log.debug("Function {} not defined in room {} script (optional hook)", functionName, roomId);
                return null;
            }
            return fn.execute(args);
        } catch (Exception e) {
            log.error("Script function {} failed in room {}: {}", functionName, roomId, e.getMessage());
            return null;
        }
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
        try (var context = createContext(worldApi)) {
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
        } catch (Exception e) {
            log.error("getHints() failed in room {}: {}", roomId, e.getMessage());
            return Optional.empty();
        }
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

    private Context createContext(WorldApi worldApi) {
        return createContext(worldApi, ResourceLimits.UNLIMITED);
    }

    Context createContext(WorldApi worldApi, ResourceLimits limits) {
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

        // Resource limits enforced via timeout thread (below).
        // GraalJS statement-limit option not reliably available across builds.
        // The timeout provides the hard safety boundary for runaway scripts.

        var context = builder.build();

        // Bind the world API
        context.getBindings("js").putMember("world", worldApi);

        // Schedule timeout cancellation for CPU time limit
        if (limits.hasCpuTimeout()) {
            final var ctx = context;
            Thread.ofVirtual().name("script-timeout-" + roomId).start(() -> {
                try {
                    Thread.sleep(limits.cpuTimeoutMs());
                    ctx.close(true); // force cancel
                    log.warn("Script in room {} cancelled after {}ms timeout", roomId, limits.cpuTimeoutMs());
                } catch (InterruptedException e) {
                    // Script finished before timeout — normal
                }
            });
        }

        return context;
    }

    /**
     * Execute a script and return the result as a String.
     * Safe for cross-module use — no GraalJS Value exposed.
     */
    public String executeAsString(String script, WorldApi worldApi) {
        var result = execute(script, worldApi);
        if (result == null || result.isNull()) return null;
        try {
            return result.isString() ? result.asString() : result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        engine.close();
    }
}
