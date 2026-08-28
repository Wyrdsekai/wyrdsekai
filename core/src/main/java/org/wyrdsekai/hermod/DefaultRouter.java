package org.wyrdsekai.hermod;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Routes THIS device's originating envelopes off the local table view.
 * Ranking: capability-class match is mandatory; a data-domain task may
 * only go where that domain is RESIDENT (compute travels to the data);
 * then prefer idle, charging, lightly loaded. Placement is a proposal —
 * on refusal the caller simply asks for the next candidate.
 */
public final class DefaultRouter implements Router {

    private final CapabilityTable table;
    private final Clock clock;

    public DefaultRouter(CapabilityTable table, Clock clock) {
        this.table = table;
        this.clock = clock;
    }

    @Override
    public List<Capability> candidates(TaskEnvelope e) {
        return table.snapshot(clock.instant()).stream()
            .filter(c -> c.capabilityClass().equals(e.capabilityClass()))
            .filter(c -> !e.requiresGrant() || c.residentDataDomains().contains(e.dataDomain()))
            .sorted(Comparator
                .comparing((Capability c) -> !c.idle())
                .thenComparing(c -> !c.charging())
                .thenComparing(Capability::loadFactor))
            .toList();
    }

    @Override
    public Optional<Capability> place(TaskEnvelope e, List<Capability> remaining) {
        return remaining.isEmpty() ? Optional.empty() : Optional.of(remaining.get(0));
    }
}
