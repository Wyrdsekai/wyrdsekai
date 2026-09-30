package org.wyrdsekai.e2e.infra;

import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A companion with a real did:key and a soul manifest signed by it, held in an in-memory soul store.
 * A cross-zone relocation carries the manifest and the target refuses one that is missing or not
 * signed by the key the DID names (2026-09-28).
 */
public record SignedSoul(AgentProfile profile, SoulManifest manifest, SoulStore store) {

    public static SignedSoul create(String name, String entityId) throws Exception {
        var secret = new byte[32];
        Arrays.fill(secret, (byte) 0x2a);
        var identity = AgentIdentity.generate(secret);
        var profile = new AgentProfile(name, entityId, "agent", "Companion in Wyrdsekai",
            "You are " + name + ".", 4096, 256, 0.7, identity.did());
        var unsigned = SoulManifest.forge(identity.did(), identity.did().substring("did:key:".length()),
            identity.keyLog(), null, 1, profile, "I am " + name + ".", List.of(), 3, "",
            GenomeProfile.defaults(), List.of(), CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
        var manifest = unsigned.signed(Base64.getDecoder().decode(identity.sign(unsigned.canonicalBytes(), secret)));
        var store = new MemoryStore();
        store.store(manifest);
        return new SignedSoul(profile, manifest, store);
    }

    /** Minimal in-memory {@link SoulStore}: latest manifest per DID. */
    static final class MemoryStore implements SoulStore {
        private final Map<String, List<SoulManifest>> byDid = new ConcurrentHashMap<>();

        @Override public void store(SoulManifest m) {
            byDid.computeIfAbsent(m.did(), d -> new ArrayList<>()).add(m);
        }
        @Override public Optional<SoulManifest> load(String did, int version) {
            return history(did).stream().filter(m -> m.manifestVersion() == version).findFirst();
        }
        @Override public Optional<SoulManifest> latest(String did) {
            var h = byDid.getOrDefault(did, List.of());
            return h.isEmpty() ? Optional.empty() : Optional.of(h.getLast());
        }
        @Override public List<SoulManifest> history(String did) {
            return List.copyOf(byDid.getOrDefault(did, List.of()));
        }
        @Override public void archive(String did, String reason) {
            byDid.remove(did);
        }
        @Override public boolean exists(String did) {
            return byDid.containsKey(did);
        }
        @Override public int count() {
            return byDid.size();
        }
    }
}
