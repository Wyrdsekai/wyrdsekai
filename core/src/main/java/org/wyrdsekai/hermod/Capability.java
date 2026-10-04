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
    Instant advertisedAt,
    byte[] publicKey) {            // the device's Ed25519 key (SPKI), what its envelopes are verified against

    /** An advert as it was before devices carried their key: no key, so no envelope of its is verifiable. */
    public Capability(String deviceId, String householdId, String capabilityClass, List<String> models,
                      List<String> residentDataDomains, boolean charging, boolean idle, double loadFactor,
                      Instant advertisedAt) {
        this(deviceId, householdId, capabilityClass, models, residentDataDomains, charging, idle, loadFactor, advertisedAt, null);
    }
}
