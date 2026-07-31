package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link CodingTaskBackend}s and
 * {@link BackendAdapter}s.
 *
 * <p>Backends and adapters are looked up by their stable {@code name()} /
 * {@code namespace()} string. The {@link CodingTaskItemBridge} consults
 * this registry on every inbound event; the GraalJS world API consults it
 * for {@code world.codingBackendFor(...)}.</p>
 *
 * <p>Phase 1a wires CodePlane only; the registry is sized for the eventual
 * 5–6 backends.</p>
 */
public class BackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);
    private static final BackendRegistry INSTANCE = new BackendRegistry();

    private final Map<String, CodingTaskBackend> backends = new ConcurrentHashMap<>();
    private final Map<String, BackendAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * Public constructor permits per-test / per-bridge instances. Production
     * code paths should prefer the {@link #get() singleton} so backend
     * lookups by GraalJS / item bridge / cost policy all see the same set
     * of backends.
     */
    public BackendRegistry() {}

    /** The process-wide singleton. */
    public static BackendRegistry get() {
        return INSTANCE;
    }

    /** Register a backend under its {@link CodingTaskBackend#name()} key. */
    public void register(CodingTaskBackend backend) {
        if (backend == null) return;
        backends.put(backend.name(), backend);
        log.info("Registered coding backend: {} (tier={})", backend.name(), backend.tier());
    }

    /** Register an adapter under its {@link BackendAdapter#namespace()} key. */
    public void register(BackendAdapter adapter) {
        if (adapter == null) return;
        adapters.put(adapter.namespace(), adapter);
        log.info("Registered backend adapter: {}", adapter.namespace());
    }

    /** Look up a backend by name. */
    public Optional<CodingTaskBackend> backendFor(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(backends.get(name));
    }

    /** Look up an adapter by namespace (matches inbound ZoneBroadcast.namespace()). */
    public Optional<BackendAdapter> adapterFor(String namespace) {
        if (namespace == null) return Optional.empty();
        return Optional.ofNullable(adapters.get(namespace));
    }

    /** All currently-registered backends, in insertion order. */
    public Collection<CodingTaskBackend> backends() {
        return new LinkedHashMap<>(backends).values();
    }

    /** All currently-registered adapters, in insertion order. */
    public Collection<BackendAdapter> adapters() {
        return new LinkedHashMap<>(adapters).values();
    }

    /** Test-only: clear all registrations. */
    public void clear() {
        backends.clear();
        adapters.clear();
    }
}
