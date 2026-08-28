package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulMaintenanceCycle;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.wyrdsekai.core.soul.EmotionalCharge;
import org.wyrdsekai.core.soul.GenomeProfile;

/**
 * Live end-to-end soul lifecycle — Birth → Interact → Sleep → Forge →
 * Wake → Verify — driven against a real LLM endpoint.
 *
 * <p>Extracted from the old {@code SoulLifecycleTest.LiveLifecycle} nested
 * class so the experimental infra ({@link InferenceHelper}) stays in the
 * {@code experimentTest} source set. The remaining
 * {@code SoulLifecycleTest} (framework / engagement / maintenance /
 * relationship / transit tests) is LLM-free and ships with the OSS
 * release; this file does not.</p>
 *
 * <p>Gated on {@code SOUL_EXPERIMENT_URL}. Defaults the model to
 * {@code qwen2.5:7b} via {@code SOUL_EXPERIMENT_MODEL}.</p>
 */
@EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
class SoulLifecycleLiveTest {

    @Test
    void fullCycleWithLLM() throws Exception {
        var secret = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(secret);
        var identity = AgentIdentity.generate(secret);

        var profile = new AgentProfile("LiveTest", "live-test", "agent",
            "Test", "You are a test companion.", 4096, 256, 0.7,
            identity.did());

        var manifest = SoulManifest.birth(
            identity.did(),
            identity.did().substring("did:key:".length()),
            // GenomeProfile has no defaults() — resilient() is the closest
            // "neutral baseline" factory and is what prior experiments used.
            identity.keyLog(), profile,
            GenomeProfile.defaults());

        var events = new ArrayList<WorldEvent>();
        var saidEvents = new ArrayList<WorldEvent.Said>();
        var charges = new ArrayList<EmotionalCharge>();

        for (int i = 0; i < 10; i++) {
            var said = new WorldEvent.Said("test-room", Instant.now(),
                "player-1", "Operator", "Tell me about architecture " + i);
            events.add(said);
            saidEvents.add(said);
            charges.add(new EmotionalCharge(0.4f,
                "curiosity", "genuine", 0.8f, Map.of(), "test"));
        }

        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var helper = new InferenceHelper(url, model);
        BiFunction<String, String, String> infer =
            (sys, user) -> {
                try { return helper.chat(sys, user); }
                catch (Exception e) { return null; }
            };

        var newManifest = SoulMaintenanceCycle.runCycle(
            identity, manifest, events,
            List.of(VitalitySnapshot.defaults()),
            events, charges, saidEvents, infer);

        System.out.println("=== Full Lifecycle Test ===");
        System.out.println("  Manifest v" + newManifest.manifestVersion());
        System.out.println("  Fingerprint markers: "
            + newManifest.fingerprint().stylisticMarkers().size());
        System.out.println("  Memory nodes: " + newManifest.memory().nodes().size());
        System.out.println("  Relationships: " + newManifest.relationships().size());
        System.out.println("  Fragments: " + newManifest.soulFragments().size());
        System.out.println("  Hash: " + newManifest.contentHash());

        assertEquals(2, newManifest.manifestVersion());
        assertTrue(newManifest.memory().nodes().size() > 0, "Should have memories");
        assertTrue(newManifest.relationships().size() > 0, "Should have relationships");
    }
}
