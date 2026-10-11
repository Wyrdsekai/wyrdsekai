package org.wyrdsekai.core.soul;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household node, 2026-10-11 04:53: the forge's insert of one companion's manifest hit the
 * store's (did, version) key, the exception escaped the actor's side effect and the one forge the
 * node has stopped. Every sleep after that, for both companions, went unanswered until the next
 * restart (and the same had happened on 2026-10-02). A store that fails is now answered as an
 * error, and the forge is still there for the next manifest.
 */
@Tag("integration")
class AForgeThatCannotStoreStaysAliveTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.soul.ForgeEvent" = jackson-json
              "org.wyrdsekai.core.soul.ForgeCommand" = jackson-json
              "org.wyrdsekai.core.soul.ForgeState" = jackson-json
            }
            pekko.persistence.testkit.events.serialize = off
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    @DisplayName("a failed store is answered as an error, and the next manifest is forged and stored")
    void aFailedStoreIsAnsweredAndTheForgeLives() {
        var store = new CollidingStore();
        var forge = testKit.spawn(ForgeActor.create(
            ForgeActor.DEFAULT_REPLICA, ForgeActor.DEFAULT_REPLICAS,
            "pekko.persistence.testkit.query", store), "forge-under-test");   // the testkit read journal
        TestProbe<ForgeCommand.ForgeResult> probe = testKit.createTestProbe();

        var did = "did:key:z6Mk-forge-alive-test";
        var profile = new AgentProfile("Wyrd", "wyrd", "agent", "desc", "You are Wyrd.", 8192, 1024, 0.7, null);
        var v1 = SoulManifest.birth(did, "mb", List.of(), profile, GenomeProfile.defaults());
        store.store(v1);
        var v2 = v1.bumpedVersion();
        store.store(v2);   // another writer got to v2 first, as the inner-monologue weave did

        forge.tell(new ForgeCommand.Forge(v2, probe.ref()));
        var answer = probe.expectMessageClass(ForgeCommand.ForgeResult.Error.class, Duration.ofSeconds(20));
        assertThat(answer.message()).contains("could not store the manifest").contains("Soul store failed");

        var v3 = v2.bumpedVersion();
        forge.tell(new ForgeCommand.Forge(v3, probe.ref()));
        var ok = probe.expectMessageClass(ForgeCommand.ForgeResult.Ok.class, Duration.ofSeconds(20));
        assertThat(ok.message()).contains("v3");
        assertThat(store.latest(did)).isPresent();
        assertThat(store.latest(did).get().manifestVersion()).isEqualTo(3);
        assertThat(store.collisions).as("the colliding insert was refused by the store, not retried silently").isEqualTo(1);
    }

    /** Keyed on (did, version) like the SQL store, and refusing a second insert of the same key like it. */
    private static final class CollidingStore implements SoulStore {
        private final Map<String, TreeMap<Integer, SoulManifest>> byDid = new HashMap<>();
        int collisions = 0;

        @Override
        public void store(SoulManifest manifest) {
            var vs = byDid.computeIfAbsent(manifest.did(), k -> new TreeMap<>());
            if (vs.containsKey(manifest.manifestVersion())) {
                collisions++;
                throw new RuntimeException("Soul store failed", new IllegalStateException(
                    "UNIQUE constraint failed: soul_manifests.did, soul_manifests.version"));
            }
            vs.put(manifest.manifestVersion(), manifest);
        }

        @Override
        public Optional<SoulManifest> load(String did, int version) {
            var vs = byDid.get(did);
            return vs == null ? Optional.empty() : Optional.ofNullable(vs.get(version));
        }

        @Override
        public Optional<SoulManifest> latest(String did) {
            var vs = byDid.get(did);
            if (vs == null || vs.isEmpty()) return Optional.empty();
            return Optional.of(vs.lastEntry().getValue());
        }

        @Override
        public List<SoulManifest> history(String did) {
            var vs = byDid.get(did);
            return vs == null ? List.of() : vs.descendingMap().values().stream().toList();
        }

        @Override public void archive(String did, String reason) {}
        @Override public boolean exists(String did) { return byDid.containsKey(did); }
        @Override public int count() { return byDid.values().stream().mapToInt(Map::size).sum(); }
    }
}
