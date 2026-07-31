package org.wyrdsekai.core.external;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

/**
 * unified shape for an adapter invocation.
 *
 * <p>Carries the dispatch tuple ({@code namespace}, {@code method},
 * {@code args}) plus the active capability set so adapters can perform
 * fine-grained gating beyond the runtime's namespace-level check.</p>
 */
public record AdapterRequest(
    String namespace,
    String method,
    Map<String, Object> args,
    ItemCapabilitySet capabilities,
    String itemId
) {
    public AdapterRequest {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public static AdapterRequest of(String namespace, String method,
                                      Map<String, Object> args) {
        return new AdapterRequest(namespace, method, args, ItemCapabilitySet.UNRESTRICTED, null);
    }
}
