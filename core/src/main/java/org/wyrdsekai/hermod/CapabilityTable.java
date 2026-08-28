package org.wyrdsekai.hermod;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The local, eventually-consistent view every router reads. Merge is
 * last-writer-wins PER DEVICE on advertisedAt — a stale advertisement
 * can never regress a fresher one, so tables converge regardless of
 * gossip order. Entries expire; absence of advertisement is absence of
 * capability.
 */
public final class CapabilityTable {

    private final Map<String, Capability> byDevice = new ConcurrentHashMap<>();
    private final Duration ttl;

    public CapabilityTable(Duration ttl) {
        this.ttl = ttl;
    }

    /** LWW per device: returns true if the table changed. */
    public boolean merge(Capability incoming) {
        var updated = byDevice.merge(incoming.deviceId(), incoming,
            (old, in) -> in.advertisedAt().isAfter(old.advertisedAt()) ? in : old);
        return updated == incoming;
    }

    /** Live entries only, freshest first. */
    public List<Capability> snapshot(Instant now) {
        var cutoff = now.minus(ttl);
        byDevice.values().removeIf(c -> c.advertisedAt().isBefore(cutoff));
        return byDevice.values().stream()
            .sorted(Comparator.comparing(Capability::advertisedAt).reversed())
            .toList();
    }

    /** Wire a transport in; every received advertisement merges. */
    public void attach(GossipTransport transport) {
        transport.subscribe(this::merge);
    }
}
