package org.wyrdsekai.core.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * process-wide singleton registry of
 * {@link ExternalAdapter}s.
 *
 * <p>Adapters call {@link #register(ExternalAdapter)} at startup (typically
 * from a per-phase static initialiser block in {@code Main}/{@code CoreServices}).
 * The provider implementation in {@code ItemWorldApiProviderImpl} routes
 * dynamic {@code world.<namespace>.*} calls through {@link #invoke}.</p>
 */
public final class ExternalAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalAdapterRegistry.class);

    private static final ExternalAdapterRegistry INSTANCE = new ExternalAdapterRegistry();

    private final ConcurrentHashMap<String, ExternalAdapter> adapters = new ConcurrentHashMap<>();

    private ExternalAdapterRegistry() {}

    public static ExternalAdapterRegistry get() { return INSTANCE; }

    /** Register an adapter. Replaces a prior registration with the same namespace. */
    public void register(ExternalAdapter adapter) {
        if (adapter == null || adapter.namespace() == null || adapter.namespace().isBlank()) {
            throw new IllegalArgumentException("adapter must declare a namespace");
        }
        var prev = adapters.put(adapter.namespace(), adapter);
        if (prev == null) {
            log.info("Registered external adapter: {} ({})",
                adapter.namespace(), adapter.getClass().getSimpleName());
        } else {
            log.warn("Replaced external adapter: {} ({} → {})",
                adapter.namespace(), prev.getClass().getSimpleName(),
                adapter.getClass().getSimpleName());
        }
    }

    /** Unregister; mostly for tests. */
    public void unregister(String namespace) {
        adapters.remove(namespace);
    }

    public Optional<ExternalAdapter> lookup(String namespace) {
        return Optional.ofNullable(adapters.get(namespace));
    }

    public Set<String> namespaces() {
        return Set.copyOf(adapters.keySet());
    }

    /**
     * Every credential slot the registered adapters declare, as
     * {@code slot → namespace} (2026-07-31). {@code wyrd cred list} could
     * previously only show slots already SET, so there was no way to learn
     * that e.g. {@code github.token} is a thing the system understands —
     * this makes the inventory derive from the adapters themselves rather
     * than a hand-maintained list that would rot. Adapters that need no
     * credential (blank/null slot) are omitted.
     */
    public Map<String, String> credentialSlots() {
        var out = new TreeMap<String, String>();
        for (var a : adapters.values()) {
            var slot = a.credentialSlot();
            if (slot == null || slot.isBlank()) continue;
            out.put(slot, a.namespace());
        }
        return out;
    }

    /** Dispatch — returns the normalized response (or a fail-shaped placeholder). */
    public AdapterResponse invoke(AdapterRequest request) {
        var adapter = adapters.get(request.namespace());
        if (adapter == null) {
            return AdapterResponse.fail("adapter_unavailable",
                "no adapter registered for " + request.namespace(), false);
        }
        if (!adapter.capabilities().contains(request.method())) {
            return AdapterResponse.fail("unknown_method",
                request.namespace() + "." + request.method() + " is not supported", false);
        }
        try {
            return adapter.invoke(request);
        } catch (Exception e) {
            log.warn("adapter {} threw: {}", adapter.namespace(), e.getMessage());
            return AdapterResponse.fail("adapter_threw", e.getMessage(), true);
        }
    }

    /** Test-only: drop everything. */
    public void clearForTests() {
        adapters.clear();
    }
}
