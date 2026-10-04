package org.wyrdsekai.server.hermod;

import org.wyrdsekai.hermod.Capability;

import java.util.Collection;
import java.util.Set;

/**
 * Whether another machine of the household advertises a brain right now, as the inference router
 * asks before sending a companion's own-time turn across (the Hermod first errand, 2026-10-03).
 * A phone is not a brain for this: it refuses the full task type the turn rides on.
 */
public final class HouseholdBrains {

    private HouseholdBrains() {}

    /** The device classes that take a companion's full turn, tools intact. */
    public static final Set<String> BRAIN_CLASSES = Set.of("llm.local-gpu", "llm.local-cpu");

    public static boolean someoneElseCanTake(Collection<Capability> advertised, String selfDeviceId) {
        if (advertised == null) return false;
        for (var c : advertised) {
            if (c == null || c.deviceId() == null || c.deviceId().equals(selfDeviceId)) continue;
            if (BRAIN_CLASSES.contains(c.capabilityClass())) return true;
        }
        return false;
    }
}
