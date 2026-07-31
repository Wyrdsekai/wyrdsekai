package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for the VoiceProfile edit path (#409).
 * Backed by an in-memory {@link SoulStore}; no actor system, no IO.
 */
class VoiceProfileServiceTest {

    private InMemorySoulStore store;
    private VoiceProfileService service;
    private static final String DID = "did:key:z6Mk-test";

    @BeforeEach
    void setUp() {
        store = new InMemorySoulStore();
        var profile = new AgentProfile("Wyrd", "wyrd", "agent", "desc",
            "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth(DID, "mb", List.of(), profile,
            GenomeProfile.defaults());
        store.store(manifest);
        service = new VoiceProfileService(store);
    }

    @Test
    void get_returns_empty_profile_when_manifest_has_none() {
        var vp = service.get(DID).orElseThrow();
        assertThat(vp.revision()).isZero();
        assertThat(vp.clauses()).isEmpty();
    }

    @Test
    void get_returns_empty_optional_when_manifest_is_missing() {
        assertThat(service.get("did:key:does-not-exist")).isEmpty();
    }

    @Test
    void setClause_bumps_revision_and_persists() {
        var vp = service.setClause(DID, "greeting-tone",
            "warm, brief", "seed", "steward:did:xyz");

        assertThat(vp.revision()).isEqualTo(1);
        assertThat(vp.clauses()).containsEntry("greeting-tone", "warm, brief");

        // Persisted via store — next load reflects the change.
        var reloaded = service.get(DID).orElseThrow();
        assertThat(reloaded.revision()).isEqualTo(1);
        assertThat(reloaded.clauses()).containsEntry("greeting-tone", "warm, brief");
    }

    @Test
    void setClause_is_additive_and_preserves_history() {
        service.setClause(DID, "k1", "v1", "first", "steward");
        var vp = service.setClause(DID, "k2", "v2", "second", "forge");

        assertThat(vp.revision()).isEqualTo(2);
        assertThat(vp.clauses()).containsOnly(
            Map.entry("k1", "v1"), Map.entry("k2", "v2"));
        assertThat(vp.history()).hasSize(2);
        assertThat(vp.history().getLast().author()).isEqualTo("forge");
    }

    @Test
    void unsetClause_removes_and_records_history() {
        service.setClause(DID, "k1", "v1", "seed", "steward");
        service.setClause(DID, "k2", "v2", "seed", "steward");
        var vp = service.unsetClause(DID, "k1", "drop k1", "steward");

        assertThat(vp.clauses()).doesNotContainKey("k1");
        assertThat(vp.clauses()).containsKey("k2");
        assertThat(vp.revision()).isEqualTo(3);
        assertThat(vp.history().getLast().reason()).isEqualTo("drop k1");
    }

    @Test
    void replaceClauses_swaps_whole_map() {
        service.setClause(DID, "old", "v", "seed", "steward");
        var replacement = new HashMap<String, String>();
        replacement.put("a", "1");
        replacement.put("b", "2");
        var vp = service.replaceClauses(DID, replacement, "bulk-import", "steward");

        assertThat(vp.clauses()).containsOnly(
            Map.entry("a", "1"), Map.entry("b", "2"));
        assertThat(vp.clauses()).doesNotContainKey("old");
    }

    @Test
    void freeze_then_setClause_is_rejected() {
        service.setClause(DID, "k", "v", "seed", "steward");
        service.freeze(DID, "steward");

        assertThatThrownBy(() ->
                service.setClause(DID, "k", "different", "forge-proposal", "forge"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void freeze_and_unfreeze_flip_the_flag_without_tripping_frozen_guard() {
        service.setClause(DID, "k", "v", "seed", "steward");
        var frozen = service.freeze(DID, "steward");
        assertThat(frozen.frozen()).isTrue();

        // Un-freeze must succeed even though the flag is set.
        var unfrozen = service.unfreeze(DID, "steward");
        assertThat(unfrozen.frozen()).isFalse();

        // Now writes are allowed again.
        var after = service.setClause(DID, "k", "new-value", "post-unfreeze", "steward");
        assertThat(after.clauses()).containsEntry("k", "new-value");
    }

    @Test
    void revertTo_restores_earlier_state() {
        service.setClause(DID, "k", "original", "seed", "steward");
        service.setClause(DID, "k", "revised", "forge-proposal", "forge");
        var reverted = service.revertTo(DID, 1, "steward:did:xyz");

        assertThat(reverted.clauses()).containsEntry("k", "original");
        assertThat(reverted.history().getLast().reason()).contains("reverted to revision 1");
    }

    @Test
    void revertTo_unknown_revision_throws() {
        service.setClause(DID, "k", "v", "seed", "steward");
        assertThatThrownBy(() -> service.revertTo(DID, 99, "steward"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void blank_key_or_value_rejected() {
        assertThatThrownBy(() -> service.setClause(DID, "  ", "v", "r", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setClause(DID, "k", "", "r", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setClause_on_missing_manifest_throws() {
        assertThatThrownBy(() ->
                service.setClause("did:key:nobody", "k", "v", "r", "a"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ─── In-memory SoulStore for tests ─────────────────────────────

    private static final class InMemorySoulStore implements SoulStore {
        private final Map<String, TreeMap<Integer, SoulManifest>> byDid = new HashMap<>();

        @Override
        public void store(SoulManifest manifest) {
            byDid.computeIfAbsent(manifest.did(), k -> new TreeMap<>())
                 .put(manifest.manifestVersion(), manifest);
        }

        @Override
        public Optional<SoulManifest> load(String did, int version) {
            var versions = byDid.get(did);
            return versions == null ? Optional.empty() : Optional.ofNullable(versions.get(version));
        }

        @Override
        public Optional<SoulManifest> latest(String did) {
            var versions = byDid.get(did);
            if (versions == null || versions.isEmpty()) return Optional.empty();
            return Optional.of(versions.lastEntry().getValue());
        }

        @Override
        public List<SoulManifest> history(String did) {
            var versions = byDid.get(did);
            if (versions == null) return List.of();
            return versions.descendingMap().values().stream().toList();
        }

        @Override
        public void archive(String did, String reason) { /* not exercised */ }

        @Override
        public boolean exists(String did) { return byDid.containsKey(did); }

        @Override
        public int count() {
            return byDid.values().stream().mapToInt(Map::size).sum();
        }
    }
}
