package org.wyrdsekai.core.codemode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.ToolItem;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the typed-namespace bundle for a {@link CodeModeExecutor#run} call.
 *
 * <p>: the typed-API surface presented to the model is
 * <em>the</em> contract. Phase 1 wired only the {@code room.&lt;itemAlias&gt;}
 * surface — every scripted equipped item the companion can use becomes a
 * namespace whose methods delegate to {@link ItemScriptExecutor}. Behavior is
 * identical to a direct {@code {"action": "&lt;itemId&gt;", ...}} tool call;
 * code-mode is just composition sugar.
 *
 * <p>Phase 2a wires {@code world.peek}, {@code world.listInventory},
 * {@code mcp.search}, and {@code mcp.execute}. All four are best-effort:
 * any null provider degrades gracefully (returns null / empty list /
 * "not authorized" error) so a barebones test fixture or a cross-zone
 * hop without a remote view can still hand the namespace back.
 *
 * <p>Phase 2b deferred: full cross-zone {@code world.peek} via the
 * relay-session protocol. Today peek is local-zone only and returns
 * {@code null} for foreign aliases.
 */
public final class CodeModeNamespace {

    private static final Logger log = LoggerFactory.getLogger(CodeModeNamespace.class);

    /**
     * Provider hooks for the {@code world.*} and {@code mcp.*} surfaces. Pulled
     * out of {@link org.wyrdsekai.core.agent.CompanionActor} to keep the
     * namespace builder testable without spinning a full actor system.
     *
     * <p>All four methods are nullable on the way in (a {@code null} provider
     * means "service not available in this scope"). The namespace functions
     * surface that as {@code null} or a clean error per spec §8 / Appendix A.
     */
    public interface WorldPeekProvider {
        /**
         * Resolve a room alias to a snapshot. Returns {@code null} if the
         * alias doesn't resolve, the caller can't perceive that room, or
         * the target lives in another zone (Phase 2a — cross-zone peek
         * is deferred to Phase 2b).
         *
         * <p>Returned shape (per spec §8): {@code { name, description,
         * exits[], entities[{alias,kind}], items[{alias,kind}] }}. All
         * keys present even when empty; aligned with the Cloudflare-style
         * read-only contract.
         */
        Map<String, Object> peek(String roomAlias);
    }

    /** Search hook for MCP deferred-discovery (§Appendix A). */
    public interface McpSearchProvider {
        /**
         * Search registered MCP tools by query. Returns up to {@code k}
         * matches, each with {@code server, tool, description, schema}.
         * Implementation may use BM25, embedding similarity, or a flat
         * substring scan — the namespace just renders what comes back.
         */
        List<Map<String, Object>> search(String query, int k);
    }

    /** Execute hook for MCP namespaced invocation (§Appendix A). */
    public interface McpExecuteProvider {
        /**
         * Invoke {@code server.tool} with {@code args}. Throws
         * {@link SecurityException} on auth/grant denial — the namespace
         * surfaces that as {@code [error] mcp.execute: not authorized}.
         * Other exceptions propagate as host errors.
         */
        Object execute(String server, String tool, Map<String, Object> args) throws Exception;
    }

    private CodeModeNamespace() {}

    /**
     * Phase 1 entrypoint — backward compatible. Phase 2 surfaces are present
     * but degrade to {@code null} / empty / "not authorized" because no
     * providers are wired.
     */
    public static Map<String, Map<String, Function<Object[], Object>>> forActor(
            List<ToolItem> equippedScriptedItems,
            ItemScriptExecutor scriptExecutor,
            ItemWorldApiProvider provider) {
        return forActor(equippedScriptedItems, scriptExecutor, provider, null, null, null);
    }

    /**
     * Phase 2a entrypoint — accepts the three wired providers. Pass
     * {@code null} for any service the caller can't or shouldn't expose
     * (e.g. an emotional-context-suppressed turn).
     */
    public static Map<String, Map<String, Function<Object[], Object>>> forActor(
            List<ToolItem> equippedScriptedItems,
            ItemScriptExecutor scriptExecutor,
            ItemWorldApiProvider provider,
            WorldPeekProvider peekProvider,
            McpSearchProvider mcpSearch,
            McpExecuteProvider mcpExecute) {

        var bundle = new LinkedHashMap<String, Map<String, Function<Object[], Object>>>();

        // ── room.<alias>.<method> — equipped scripted items ─────────────
        if (equippedScriptedItems != null) {
            for (var item : equippedScriptedItems) {
                if (item == null || !item.isScripted()) continue;
                String alias = item.id();
                if (alias == null || alias.isBlank()) continue;

                var ns = new LinkedHashMap<String, Function<Object[], Object>>();
                Function<Object[], Object> invoker = args -> invokeItem(
                    scriptExecutor, provider, item, args);
                ns.put("invoke", invoker);

                // Common semantic aliases — same delegate, prettier signatures.
                // Item scripts dispatch on the param map; a no-op alias just
                // routes to invoke and lets the script's `params.query` etc.
                // do the work. Matches example surface.
                ns.put("search", invoker);
                ns.put("read", invoker);
                ns.put("forecast", invoker);
                ns.put("query", invoker);
                ns.put("write", invoker);
                ns.put("send", invoker);

                bundle.put(alias, ns);
            }
        }

        // ── world.* — Phase 2a wired ────────────────────────────────────
        var worldNs = new LinkedHashMap<String, Function<Object[], Object>>();

        // world.peek(roomAlias) — read-only cross-room snapshot.
        // Returns null when the alias doesn't resolve, the caller can't
        // perceive the target, or the target is in another zone (Phase 2b).
        worldNs.put("peek", args -> {
            String alias = positionalString(args);
            if (alias == null || alias.isBlank()) {
                log.debug("world.peek called with empty alias");
                return null;
            }
            if (peekProvider == null) {
                log.debug("world.peek: no peekProvider wired");
                return null;
            }
            try {
                return peekProvider.peek(alias);
            } catch (Exception e) {
                log.warn("world.peek('{}') failed: {}", alias, e.getMessage());
                return null;
            }
        });

        // world.listInventory() — equipped items for the caller.
        // Mirrors ItemWorldApiProvider.inventoryList() and tags equipped=true
        // for every entry (everything in the equipped set is, by definition,
        // equipped — Phase 2b will broaden this to also list carried-but-
        // unequipped items once they live in the same provider surface).
        worldNs.put("listInventory", args -> {
            if (provider == null) return List.of();
            try {
                var raw = provider.inventoryList();
                if (raw == null) return List.of();
                var out = new ArrayList<Map<String, Object>>(raw.size());
                for (var item : raw) {
                    if (item == null) continue;
                    var entry = new LinkedHashMap<String, Object>();
                    Object alias = item.get("name");
                    if (alias == null) alias = item.get("id");
                    entry.put("alias", alias != null ? String.valueOf(alias) : "");
                    entry.put("kind", item.getOrDefault("kind", "item"));
                    entry.put("equipped", true);
                    out.add(entry);
                }
                return out;
            } catch (Exception e) {
                log.warn("world.listInventory failed: {}", e.getMessage());
                return List.of();
            }
        });

        bundle.put("world", worldNs);

        // ── mcp.* — Phase 2a wired (Appendix A: deferred-discovery) ─────
        var mcpNs = new LinkedHashMap<String, Function<Object[], Object>>();

        // mcp.search(query, opts?) — top-K matches with name + schema.
        mcpNs.put("search", args -> {
            if (mcpSearch == null) {
                throw new RuntimeException("[error] mcp.search: not authorized");
            }
            String query = positionalString(args);
            int k = 5;
            if (args != null && args.length >= 2 && args[1] instanceof Map<?, ?> opts) {
                Object kRaw = opts.get("k");
                if (kRaw instanceof Number n) {
                    int requested = n.intValue();
                    if (requested > 0 && requested <= 50) k = requested;
                }
            }
            try {
                var results = mcpSearch.search(query == null ? "" : query, k);
                return results == null ? List.of() : results;
            } catch (SecurityException se) {
                throw new RuntimeException("[error] mcp.search: not authorized");
            } catch (Exception e) {
                log.warn("mcp.search('{}') failed: {}", query, e.getMessage());
                throw new RuntimeException("[error] mcp.search: " + e.getMessage());
            }
        });

        // mcp.execute(server, tool, args) — invoke a discovered tool.
        mcpNs.put("execute", args -> {
            if (mcpExecute == null) {
                throw new RuntimeException("[error] mcp.execute: not authorized");
            }
            String server = null;
            String tool = null;
            Map<String, Object> callArgs = Map.of();
            if (args != null) {
                if (args.length >= 1 && args[0] instanceof String s) server = s;
                if (args.length >= 2 && args[1] instanceof String s) tool = s;
                if (args.length >= 3 && args[2] instanceof Map<?, ?> m) {
                    callArgs = new LinkedHashMap<>();
                    for (var e : m.entrySet()) {
                        if (e.getKey() != null) {
                            callArgs.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                }
            }
            if (server == null || server.isBlank() || tool == null || tool.isBlank()) {
                throw new RuntimeException(
                    "[error] mcp.execute: server and tool are required");
            }
            try {
                return mcpExecute.execute(server, tool, callArgs);
            } catch (SecurityException se) {
                throw new RuntimeException("[error] mcp.execute: not authorized");
            } catch (Exception e) {
                log.warn("mcp.execute({}.{}) failed: {}", server, tool, e.getMessage());
                throw new RuntimeException("[error] mcp.execute: " + e.getMessage());
            }
        });

        bundle.put("mcp", mcpNs);

        return bundle;
    }

    /** Extract the first positional arg as a string (for peek / search). */
    private static String positionalString(Object[] args) {
        if (args == null || args.length == 0) return null;
        var first = args[0];
        if (first == null) return null;
        if (first instanceof String s) return s;
        if (isPolyglotValue(first)) {
            var s = CodeModeExecutor.unwrapValueString(first);
            if (s != null) return s;
        }
        return String.valueOf(first);
    }

    /** True if {@code o} is a {@code org.graalvm.polyglot.Value}. */
    private static boolean isPolyglotValue(Object o) {
        if (o == null) return false;
        var cn = o.getClass().getName();
        return "org.graalvm.polyglot.Value".equals(cn);
    }

    /**
     * Invoke a scripted item with a single argument that may be a JS object
     * (mapped to {@code params}) or a positional string treated as
     * {@code {query: <string>}}.
     */
    @SuppressWarnings("unchecked")
    static Object invokeItem(ItemScriptExecutor exec, ItemWorldApiProvider provider,
                              ToolItem item, Object[] args) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args != null && args.length > 0) {
            var first = args[0];
            if (first instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) {
                    if (e.getKey() != null) {
                        params.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            } else if (first instanceof String s) {
                params.put("query", s);
            } else if (first != null && isPolyglotValue(first)) {
                // GraalJS Value passed through. We don't import the type here
                // (core doesn't transitively expose polyglot — see scripting/
                // build.gradle.kts), so we duck-type via reflection through
                // CodeModeExecutor's helper.
                var members = CodeModeExecutor.unwrapValueMembers(first);
                if (members != null) {
                    params.putAll(members);
                } else {
                    var s = CodeModeExecutor.unwrapValueString(first);
                    if (s != null) params.put("query", s);
                    else params.put("value", first);
                }
            } else if (first != null) {
                params.put("value", first);
            }
        }
        try {
            return exec.execute(item.id(), item.script(), params, provider);
        } catch (Exception e) {
            log.warn("CodeMode item invoke failed for '{}': {}", item.id(), e.getMessage());
            return Map.of("error", "invoke failed: " + e.getMessage());
        }
    }
}
