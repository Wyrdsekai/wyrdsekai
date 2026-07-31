package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies the canonical-store
 * reader methods on {@link SoulStore} return data sourced from the
 * canonical tables (via the hydrate path), not from the stripped blob.
 */
class SoulStoreCanonicalReadersTest {

    @TempDir Path workspace;

    private SoulManifest birth(String did) {
        var profile = new AgentProfile(
            "Test", "test", "agent", "test agent", "You are Test.",
            8192, 1024, 0.7, null);
        return SoulManifest.birth(did, "z6Mk", List.of(),
            profile, GenomeProfile.defaults());
    }

    @Test
    void fragmentsForReturnsCanonicalData() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3d.db"));
        var fragmentStore = new SoulFragmentStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                fragmentStore, null, null, null)) {
            var did = "did:key:p3d-frags";
            var fragments = List.of(
                SoulFragment.unembedded("a", "memory", "A", "alpha"),
                SoulFragment.unembedded("b", "memory", "B", "beta"));
            soulStore.store(birth(did).withFragments(fragments));

            var read = soulStore.fragmentsFor(did);
            assertThat(read).hasSize(2);
            assertThat(read.get(0).id()).isEqualTo("a");
        }
    }

    @Test
    void bondsForReturnsCanonicalData() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3d-bonds.db"));
        var bondStore = new BondStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, bondStore, null, null)) {
            var did = "did:key:p3d-alice";
            soulStore.store(birth(did));
            bondStore.save(Bond.acquaintance(did, "did:key:p3d-bob"));

            var read = soulStore.bondsFor(did);
            assertThat(read).hasSize(1);
        }
    }

    @Test
    void voiceProfileForReturnsCanonicalData() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3d-voice.db"));
        var voiceStore = new VoiceProfileStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, null, null, voiceStore)) {
            var did = "did:key:p3d-voice";
            var voice = VoiceProfile.empty().withClauses(
                Map.of("greeting-tone", "warm but spare"),
                "first edit", "test");
            soulStore.store(birth(did).withVoiceProfile(voice));

            var read = soulStore.voiceProfileFor(did);
            assertThat(read).isPresent();
            assertThat(read.get().clauses().get("greeting-tone"))
                .isEqualTo("warm but spare");
        }
    }

    @Test
    void worldKnowledgeForReturnsCanonicalData() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3d-wk.db"));
        var wkStore = new WorldKnowledgeStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, null, wkStore, null)) {
            var did = "did:key:p3d-wk";
            soulStore.store(birth(did).withWorldKnowledge(
                Map.of("starterKit", "explorer")));

            var read = soulStore.worldKnowledgeFor(did);
            assertThat(read.get("starterKit")).isEqualTo("explorer");
        }
    }

    @Test
    void readersReturnEmptyForUnknownDid() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3d-empty.db"));
        try (var soulStore = new SqlSoulStore(jdbc)) {
            assertThat(soulStore.fragmentsFor("did:key:nope")).isEmpty();
            assertThat(soulStore.bondsFor("did:key:nope")).isEmpty();
            assertThat(soulStore.voiceProfileFor("did:key:nope")).isEmpty();
            assertThat(soulStore.worldKnowledgeFor("did:key:nope")).isEmpty();
        }
    }
}
