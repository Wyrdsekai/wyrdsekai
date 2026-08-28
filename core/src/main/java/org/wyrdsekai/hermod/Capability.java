package org.wyrdsekai.hermod;

import java.time.Instant;
import java.util.List;

/**
 * What one device advertises to the household. Advertisement only —
 * carries no obligation; admission stays with the device.
 */
public record Capability(
    String deviceId,
    String householdId,
    String capabilityClass,        // e.g. "llm.a1b", "llm.dense-9b", "llm.a3b-30b", "embed"
    List<String> models,
    List<String> residentDataDomains, // data that lives HERE and must not travel
    boolean charging,
    boolean idle,
    double loadFactor,             // 0..1
    Instant advertisedAt) {
}
