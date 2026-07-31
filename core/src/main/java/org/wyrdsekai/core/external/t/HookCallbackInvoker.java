package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * (Phase T) — bridges
 * {@link InboundDispatchService} → an item's hook function inside the
 * GraalJS sandbox.
 *
 * <p>Resolves the script source via {@link ScriptedItemLoader} (overridable
 * for tests), builds a per-call {@link ItemWorldApiProvider} via the injected
 * provider factory, and calls
 * {@link ItemScriptExecutor#executeHook} with the event payload.</p>
 *
 * <p>Why this is its own class: the dispatch service is concerned with
 * "should we deliver this?" — registry lookup + rate-limit evaluation. The
 * invoker is concerned with "how do we deliver this?" — capability set,
 * provider hookup, sandbox call. Splitting them keeps {@link InboundDispatchService}
 * testable without spinning up GraalJS.</p>
 */
public class HookCallbackInvoker {

    private static final Logger log = LoggerFactory.getLogger(HookCallbackInvoker.class);

    /** Resolves an item id → JS source. Default uses {@link ScriptedItemLoader}. */
    private final Function<String, String> sourceResolver;

    /** Builds a per-call provider for {@code (itemId, agentId)}. May return null. */
    private final BiFunction<String, String, ItemWorldApiProvider> providerFactory;

    /** Resolves the capability set for {@code itemId}. May return UNRESTRICTED. */
    private final Function<String, ItemCapabilitySet> capsResolver;

    private final ItemScriptExecutor executor;

    public HookCallbackInvoker(ItemScriptExecutor executor,
                                 Function<String, String> sourceResolver,
                                 BiFunction<String, String, ItemWorldApiProvider> providerFactory,
                                 Function<String, ItemCapabilitySet> capsResolver) {
        this.executor = executor == null ? new ItemScriptExecutor() : executor;
        this.sourceResolver = sourceResolver != null ? sourceResolver : defaultSourceResolver();
        this.providerFactory = providerFactory != null ? providerFactory : (_, _) -> null;
        this.capsResolver = capsResolver != null ? capsResolver : (_) -> ItemCapabilitySet.UNRESTRICTED;
    }

    /** Default executor + ScriptedItemLoader-backed source resolver. */
    public static HookCallbackInvoker defaults() {
        return new HookCallbackInvoker(new ItemScriptExecutor(),
            defaultSourceResolver(), (_, _) -> null,
            _ -> ItemCapabilitySet.UNRESTRICTED);
    }

    private static Function<String, String> defaultSourceResolver() {
        return itemId -> ScriptedItemLoader.get().get(itemId)
            .map(d -> d.scriptSource())
            .orElse(null);
    }

    /**
     * Invoke {@code hookName} on the script for {@code itemId} with {@code event}.
     * Returns the script's result map, or a {@code {error}} map on failure
     * (missing source, missing hook, runtime exception, capability denial).
     */
    public Map<String, Object> invoke(String itemId, String agentId, String hookName,
                                          InboundEvent event) {
        var source = sourceResolver.apply(itemId);
        if (source == null || source.isBlank()) {
            log.warn("inbound dispatch: no script source for itemId={}", itemId);
            return Map.of("error", "no_script_source", "itemId", itemId);
        }
        var provider = providerFactory.apply(itemId, agentId);
        var caps = capsResolver.apply(itemId);
        var payload = event.toScriptObject();
        try {
            return executor.executeHook(itemId, source, hookName, payload, provider, caps);
        } catch (Throwable t) {
            log.warn("inbound dispatch: invoker threw for itemId={} hook={}: {}",
                itemId, hookName, t.getMessage());
            return Map.of("error", "invoker_threw", "message", String.valueOf(t.getMessage()));
        }
    }
}
