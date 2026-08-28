package org.wyrdsekai.core.external;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.LinkedHashMap;
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
        // Map.copyOf rejects null VALUES, not just a null map — so a single unset
        // argument used to take the whole call down with a message-less NPE, two layers
        // below anything that knew which adapter was being called. Drop what was not
        // set; the adapter already validates what it requires.
        if (args == null) {
            args = Map.of();
        } else {
            var clean = new LinkedHashMap<String, Object>();
            for (var e : args.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) clean.put(e.getKey(), e.getValue());
            }
            args = Map.copyOf(clean);
        }
    }

    public static AdapterRequest of(String namespace, String method,
                                      Map<String, Object> args) {
        return new AdapterRequest(namespace, method, args, ItemCapabilitySet.UNRESTRICTED, null);
    }
}
