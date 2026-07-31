package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-evolving Forge tests (#410). Pure parsing + validation + apply
 * checks via a canned inference callback and an in-memory SoulStore.
 */
class VoiceProfileForgeTest {

    private InMemorySoulStore store;
    private VoiceProfileService service;
    private VoiceProfileForge forge;
    private static final String DID = "did:key:z6Mk-forge-test";

    @BeforeEach
    void setUp() {
        store = new InMemorySoulStore();
        var profile = new AgentProfile("Wyrd", "wyrd", "agent", "desc",
            "You are Wyrd.", 8192, 1024, 0.7, null);
        var manifest = SoulManifest.birth(DID, "mb", List.of(), profile,
            GenomeProfile.defaults());
        store.store(manifest);
        service = new VoiceProfileService(store);
        forge = new VoiceProfileForge(service);
    }

    // ─── Parsing ───────────────────────────────────────────────────

    @Test
    void parses_set_proposal_from_clean_json() {
        var json = "{\"action\":\"set\",\"key\":\"greeting-tone\","
            + "\"value\":\"warm, brief\",\"reason\":\"recent turns lean stiff\"}";
        var p = VoiceProfileForge.parseProposal(json).orElseThrow();
        assertThat(p.action()).isEqualTo(VoiceProfileForge.Action.SET);
        assertThat(p.key()).isEqualTo("greeting-tone");
        assertThat(p.value()).isEqualTo("warm, brief");
        assertThat(p.reason()).contains("stiff");
    }

    @Test
    void parses_unset_proposal() {
        var json = "{\"action\":\"unset\",\"key\":\"old-clause\","
            + "\"reason\":\"redundant\"}";
        var p = VoiceProfileForge.parseProposal(json).orElseThrow();
        assertThat(p.action()).isEqualTo(VoiceProfileForge.Action.UNSET);
        assertThat(p.key()).isEqualTo("old-clause");
        assertThat(p.value()).isNull();
    }

    @Test
    void no_change_returns_empty() {
        var json = "{\"action\":\"no_change\",\"reason\":\"voice is stable\"}";
        assertThat(VoiceProfileForge.parseProposal(json)).isEmpty();
    }

    @Test
    void extracts_json_from_prose_wrapped_output() {
        // Some models wrap in markdown / explanation — the extractor must
        // tolerate leading/trailing prose.
        var wrapped = "Here is the revision I propose:\n\n"
            + "{\"action\":\"set\",\"key\":\"pacing\",\"value\":\"slow, measured\","
            + "\"reason\":\"rushed turns\"}\n\nLet me know if you disagree.";
        var p = VoiceProfileForge.parseProposal(wrapped).orElseThrow();
        assertThat(p.key()).isEqualTo("pacing");
    }

    @Test
    void garbage_returns_empty() {
        assertThat(VoiceProfileForge.parseProposal(null)).isEmpty();
        assertThat(VoiceProfileForge.parseProposal("")).isEmpty();
        assertThat(VoiceProfileForge.parseProposal("not json at all")).isEmpty();
        assertThat(VoiceProfileForge.parseProposal("{}")).isEmpty();
        assertThat(VoiceProfileForge.parseProposal("{\"action\":\"bogus\"}")).isEmpty();
    }

    // ─── Validation ────────────────────────────────────────────────

    @Test
    void validates_key_pattern() {
        var bad = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "Invalid Key!", "v", "r");
        assertThat(VoiceProfileForge.validate(VoiceProfile.empty(), bad))
            .contains("invalid key");
    }

    @Test
    void validates_value_length() {
        var longValue = "x".repeat(VoiceProfileForge.MAX_VALUE_LEN + 1);
        var p = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "k", longValue, "r");
        assertThat(VoiceProfileForge.validate(VoiceProfile.empty(), p))
            .contains("too long");
    }

    @Test
    void validates_clause_cap() {
        var saturated = buildProfileWithNClauses(VoiceProfileForge.MAX_CLAUSES);
        var addOne = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "new-key", "v", "r");
        assertThat(VoiceProfileForge.validate(saturated, addOne))
            .contains("MAX_CLAUSES");

        // But overwriting an existing key is fine — doesn't grow the map.
        var overwrite = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "k0", "new-val", "r");
        assertThat(VoiceProfileForge.validate(saturated, overwrite)).isNull();
    }

    @Test
    void requires_reason() {
        var p = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "k", "v", "");
        assertThat(VoiceProfileForge.validate(VoiceProfile.empty(), p))
            .contains("missing reason");
    }

    @Test
    void valid_proposal_passes() {
        var p = new VoiceProfileForge.ProposedRevision(
            VoiceProfileForge.Action.SET, "greeting-tone", "warm", "reason text");
        assertThat(VoiceProfileForge.validate(VoiceProfile.empty(), p)).isNull();
    }

    // ─── End-to-end runOnce ────────────────────────────────────────

    @Test
    void runOnce_applies_valid_set_proposal() {
        var applied = forge.runOnce(DID, List.of("recent reflective turn"),
            prompt -> "{\"action\":\"set\",\"key\":\"pacing\","
                + "\"value\":\"slow, measured\",\"reason\":\"recent turns lean rushed\"}")
            .orElseThrow();

        assertThat(applied.action()).isEqualTo(VoiceProfileForge.Action.SET);
        assertThat(applied.key()).isEqualTo("pacing");

        // Verify the service actually persisted the change.
        var vp = service.get(DID).orElseThrow();
        assertThat(vp.clauses()).containsEntry("pacing", "slow, measured");
        assertThat(vp.history().getLast().author()).isEqualTo("forge");
        assertThat(vp.history().getLast().reason()).startsWith("forge:");
    }

    @Test
    void runOnce_skips_when_profile_is_frozen() {
        service.setClause(DID, "k", "v", "seed", "steward");
        service.freeze(DID, "steward");

        // Callback must NEVER fire — frozen short-circuits before inference.
        var callbackFired = new boolean[]{false};
        var result = forge.runOnce(DID, List.of("turn"), prompt -> {
            callbackFired[0] = true;
            return "{\"action\":\"set\",\"key\":\"pacing\",\"value\":\"v\",\"reason\":\"r\"}";
        });

        assertThat(result).isEmpty();
        assertThat(callbackFired[0])
            .as("frozen profile must short-circuit before calling the meta-LLM")
            .isFalse();
    }

    @Test
    void runOnce_drops_invalid_proposal_silently() {
        var result = forge.runOnce(DID, List.of("turn"),
            prompt -> "{\"action\":\"set\",\"key\":\"BAD KEY\","
                + "\"value\":\"v\",\"reason\":\"r\"}");

        assertThat(result).isEmpty();
        // No mutation landed.
        assertThat(service.get(DID).orElseThrow().revision()).isZero();
    }

    @Test
    void runOnce_drops_on_no_change() {
        var result = forge.runOnce(DID, List.of("turn"),
            prompt -> "{\"action\":\"no_change\",\"reason\":\"voice stable\"}");

        assertThat(result).isEmpty();
        assertThat(service.get(DID).orElseThrow().revision()).isZero();
    }

    @Test
    void runOnce_drops_on_callback_failure() {
        var result = forge.runOnce(DID, List.of("turn"), prompt -> {
            throw new RuntimeException("inference timeout");
        });
        assertThat(result).isEmpty();
    }

    @Test
    void runOnce_applies_unset_and_removes_clause() {
        service.setClause(DID, "to-remove", "old", "seed", "steward");

        forge.runOnce(DID, List.of("turn"),
            prompt -> "{\"action\":\"unset\",\"key\":\"to-remove\","
                + "\"reason\":\"redundant\"}");

        assertThat(service.get(DID).orElseThrow().clauses())
            .doesNotContainKey("to-remove");
    }

    @Test
    void prompt_includes_current_clauses_and_sample_turns() {
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("existing", "value");
        var profile = VoiceProfile.empty().withClauses(clauses, "seed", "steward");

        var prompt = VoiceProfileForge.buildPrompt(profile, List.of("sample turn one"));
        assertThat(prompt).contains("Current voice profile");
        assertThat(prompt).contains("existing: value");
        assertThat(prompt).contains("sample turn one");
        assertThat(prompt).contains("no_change");  // must document the skip option
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private static VoiceProfile buildProfileWithNClauses(int n) {
        var clauses = new LinkedHashMap<String, String>();
        for (int i = 0; i < n; i++) clauses.put("k" + i, "v" + i);
        return VoiceProfile.empty().withClauses(clauses, "seed", "steward");
    }

    private static final class InMemorySoulStore implements SoulStore {
        private final Map<String, TreeMap<Integer, SoulManifest>> byDid = new HashMap<>();

        @Override
        public void store(SoulManifest manifest) {
            byDid.computeIfAbsent(manifest.did(), k -> new TreeMap<>())
                 .put(manifest.manifestVersion(), manifest);
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
        @Override public int count() {
            return byDid.values().stream().mapToInt(Map::size).sum();
        }
    }
}
