package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies canonical-first reads.
 *
 * <p>The crucial behaviour: when a manifest is persisted with a sub-record,
 * the canonical table holds the truth. {@link SqlSoulStore#latest} hydrates
 * the manifest's sub-record fields from canonical tables, so even if the
 * blob's copy is stale (e.g. after a crash between dual-write and blob
 * write), readers see the canonical value.
 */
class SqlSoulStorePhase3aTest {

    @TempDir Path workspace;

    private SoulManifest birth(String did) {
        var profile = new AgentProfile(
            "Test", "test", "agent", "test agent", "You are Test.",
            8192, 1024, 0.7, null);
        return SoulManifest.birth(did, "z6Mk", List.of(),
            profile, GenomeProfile.defaults());
    }

    @Test
    void hydrateReplacesBlobFragmentsWithCanonical() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a.db"));
        var fragmentStore = new SoulFragmentStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                fragmentStore, null, null, null)) {
            var did = "did:key:test-frags";
            var blobFragments = List.of(
                SoulFragment.unembedded("blob-only", "memory", "Blob", "blob text"));
            var manifest = birth(did).withFragments(blobFragments);

            // Store: dual-write fires, fragmentStore now has 1 row from blob.
            soulStore.store(manifest);

            // Replace canonical table contents with a different fragment list
            // (simulating an out-of-band write or a stale blob).
            var canonicalFragments = List.of(
                SoulFragment.unembedded("canonical", "personality", "Canonical", "true"));
            fragmentStore.replaceAll(did, canonicalFragments);

            // Read should return canonical, not blob.
            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.soulFragments()).hasSize(1);
            assertThat(loaded.soulFragments().get(0).id()).isEqualTo("canonical");
        }
    }

    @Test
    void hydrateReplacesBlobWorldKnowledgeWithCanonical() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a-wk.db"));
        var wkStore = new WorldKnowledgeStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, null, wkStore, null)) {
            var did = "did:key:test-wk";
            var blobMap = new LinkedHashMap<String, String>();
            blobMap.put("starterKit", "explorer");
            soulStore.store(birth(did).withWorldKnowledge(blobMap));

            // Out-of-band canonical update.
            var canonicalMap = Map.of("starterKit", "scholar", "extra", "value");
            wkStore.replaceAll(did, canonicalMap);

            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.worldKnowledge().get("starterKit")).isEqualTo("scholar");
            assertThat(loaded.worldKnowledge().get("extra")).isEqualTo("value");
        }
    }

    @Test
    void hydrateReplacesBlobBondsWithCanonical() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a-bonds.db"));
        var bondStore = new BondStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, bondStore, null, null)) {
            var did = "did:key:alice";
            soulStore.store(birth(did));  // no bonds in blob

            var bondA = Bond.acquaintance(did, "did:key:bob");
            var bondB = Bond.acquaintance(did, "did:key:carol");
            bondStore.save(bondA);
            bondStore.save(bondB);

            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.bonds()).hasSize(2);
        }
    }

    @Test
    void hydrateReplacesBlobVoiceProfileWithCanonical() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a-voice.db"));
        var voiceStore = new VoiceProfileStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, null, null, voiceStore)) {
            var did = "did:key:voice";
            var blobVoice = VoiceProfile.empty().withClauses(
                Map.of("greeting-tone", "warm but spare"),
                "first edit", "test");
            soulStore.store(birth(did).withVoiceProfile(blobVoice));

            // Out-of-band canonical update: replace clauses.
            var canonicalVoice = VoiceProfile.empty().withClauses(
                Map.of("greeting-tone", "crisp", "ending", "no signoff"),
                "later edit", "test");
            voiceStore.save(did, canonicalVoice);

            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.voiceProfile().clauses().get("greeting-tone"))
                .isEqualTo("crisp");
            assertThat(loaded.voiceProfile().clauses()).containsKey("ending");
        }
    }

    @Test
    void phase3bStripsSubRecordsFromBlob() {
        // Phase 3b: with canonical store wired, store() nulls the blob's
        // sub-record fields. The data lives only in the canonical table.
        // Reads still see fragments because hydrateFromCanonical fills
        // them back in.
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3b-strip.db"));
        var fragmentStore = new SoulFragmentStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                fragmentStore, null, null, null)) {
            var did = "did:key:strip";
            var fragments = List.of(
                SoulFragment.unembedded("frag1", "memory", "F1", "text"));
            soulStore.store(birth(did).withFragments(fragments));

            // Round-trip: read returns the fragments via hydration.
            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.soulFragments()).hasSize(1);

            // Wipe the canonical table: hydration always-assigns when
            // store is wired, so the result is the empty list (NOT the
            // null that Phase 3b would imply if the blob were the only
            // source). This preserves the "never null" invariant for
            // existing callers that do .size() / .stream() unguarded.
            fragmentStore.replaceAll(did, List.of());
            var afterWipe = soulStore.latest(did).orElseThrow();
            assertThat(afterWipe.soulFragments()).isNotNull();
            assertThat(afterWipe.soulFragments()).isEmpty();
        }
    }

    @Test
    void legacyConstructorBehavesAsBefore() {
        // 1-/2-arg constructors → all canonical stores null → no hydration,
        // pure blob round-trip.
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a-legacy.db"));
        try (var soulStore = new SqlSoulStore(jdbc)) {
            var did = "did:key:legacy";
            var manifest = birth(did)
                .withFragments(List.of(
                    SoulFragment.unembedded("a", "memory", "A", "alpha")))
                .withWorldKnowledge(Map.of("k", "v"));
            soulStore.store(manifest);

            var loaded = soulStore.latest(did).orElseThrow();
            assertThat(loaded.soulFragments()).hasSize(1);
            assertThat(loaded.worldKnowledge()).hasSize(1);
        }
    }

    @Test
    void loadByVersionAlsoHydrates() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("p3a-version.db"));
        var fragmentStore = new SoulFragmentStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                fragmentStore, null, null, null)) {
            var did = "did:key:versioned";
            soulStore.store(birth(did));

            fragmentStore.replaceAll(did, List.of(
                SoulFragment.unembedded("post-store", "memory", "PS", "later")));

            var loaded = soulStore.load(did, 1).orElseThrow();
            assertThat(loaded.soulFragments()).hasSize(1);
            assertThat(loaded.soulFragments().get(0).id()).isEqualTo("post-store");
        }
    }
}
