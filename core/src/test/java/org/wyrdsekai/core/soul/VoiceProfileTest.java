package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-unit tests for the VoiceProfile data model (task #407).
 * No actor harness, no IO — just record semantics, history accounting,
 * frozen-flag enforcement, revert, and JSON round-trip.
 */
class VoiceProfileTest {

    @Test
    void empty_profile_has_zero_revision_and_no_clauses() {
        var vp = VoiceProfile.empty();
        assertThat(vp.revision()).isZero();
        assertThat(vp.frozen()).isFalse();
        assertThat(vp.clauses()).isEmpty();
        assertThat(vp.history()).isEmpty();
    }

    @Test
    void withClauses_bumps_revision_and_records_history() {
        var vp = VoiceProfile.empty()
                .withClauses(Map.of("greeting-tone", "warm, brief"), "seed", "steward:did:xyz");

        assertThat(vp.revision()).isEqualTo(1);
        assertThat(vp.clauses()).containsEntry("greeting-tone", "warm, brief");
        assertThat(vp.history()).hasSize(1);
        var first = vp.history().getFirst();
        assertThat(first.fromRevision()).isZero();
        assertThat(first.toRevision()).isEqualTo(1);
        assertThat(first.reason()).isEqualTo("seed");
        assertThat(first.author()).isEqualTo("steward:did:xyz");
        assertThat(first.clausesBefore()).isEmpty();
    }

    @Test
    void withClauses_captures_previous_state_in_history() {
        var vp = VoiceProfile.empty()
                .withClauses(Map.of("k1", "v1"), "seed", "steward")
                .withClauses(Map.of("k1", "v1", "k2", "v2"), "forge adds k2", "forge");

        assertThat(vp.revision()).isEqualTo(2);
        assertThat(vp.clauses()).containsOnly(Map.entry("k1", "v1"), Map.entry("k2", "v2"));
        assertThat(vp.history()).hasSize(2);
        // Second history entry captured the state as-of revision 1 (before the forge add).
        var second = vp.history().get(1);
        assertThat(second.clausesBefore()).containsExactly(Map.entry("k1", "v1"));
        assertThat(second.fromRevision()).isEqualTo(1);
        assertThat(second.toRevision()).isEqualTo(2);
        assertThat(second.author()).isEqualTo("forge");
    }

    @Test
    void frozen_profile_rejects_mutation() {
        var frozen = VoiceProfile.empty()
                .withClauses(Map.of("k", "v"), "seed", "steward")
                .withFrozen(true);

        assertThatThrownBy(() ->
                frozen.withClauses(Map.of("k", "different"), "forge", "forge"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void withFrozen_is_not_blocked_by_frozen_flag_itself() {
        // The steward must be able to un-freeze.
        var frozen = VoiceProfile.empty().withFrozen(true);
        var unfrozen = frozen.withFrozen(false);
        assertThat(unfrozen.frozen()).isFalse();
    }

    @Test
    void withFrozen_with_reason_appends_history_entry() {
        // bug_010 — three-arg withFrozen records audit history so freeze
        // events are visible (not just silently flipping the flag).
        var vp = VoiceProfile.empty()
                .withClauses(Map.of("k", "v"), "seed", "steward")
                .withFrozen(true, "policy review", "steward:operator");

        assertThat(vp.frozen()).isTrue();
        assertThat(vp.revision()).isEqualTo(2);   // bumped from 1
        assertThat(vp.history()).hasSize(2);
        var last = vp.history().getLast();
        assertThat(last.reason()).contains("policy review").contains("[freeze]");
        assertThat(last.author()).isEqualTo("steward:operator");
        assertThat(last.fromRevision()).isEqualTo(1);
        assertThat(last.toRevision()).isEqualTo(2);
    }

    @Test
    void withFrozen_unfreeze_with_reason_appends_history_entry() {
        var frozen = VoiceProfile.empty()
                .withFrozen(true, "freeze for review", "steward");
        var unfrozen = frozen.withFrozen(false, "review complete", "steward");

        assertThat(unfrozen.frozen()).isFalse();
        assertThat(unfrozen.revision()).isEqualTo(2);
        assertThat(unfrozen.history()).hasSize(2);
        assertThat(unfrozen.history().getLast().reason())
                .contains("review complete").contains("[unfreeze]");
    }

    @Test
    void revertTo_restores_earlier_state_and_records_revert() {
        var v1 = VoiceProfile.empty()
                .withClauses(Map.of("k", "original"), "seed", "steward");
        var v2 = v1.withClauses(Map.of("k", "revised"), "forge proposal", "forge");

        var reverted = v2.revertTo(1, "steward:did:xyz").orElseThrow();

        assertThat(reverted.clauses()).containsEntry("k", "original");
        assertThat(reverted.revision()).isEqualTo(3);
        assertThat(reverted.history()).hasSize(3);
        assertThat(reverted.history().getLast().reason()).contains("reverted to revision 1");
        assertThat(reverted.history().getLast().author()).isEqualTo("steward:did:xyz");
    }

    @Test
    void revertTo_unknown_revision_returns_empty() {
        var vp = VoiceProfile.empty().withClauses(Map.of("k", "v"), "seed", "steward");
        assertThat(vp.revertTo(99, "steward")).isEmpty();
    }

    @Test
    void promptBlock_renders_clauses_as_system_prompt() {
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("greeting-tone", "warm, brief");
        clauses.put("reflective-pacing", "slow, sentence-per-breath");
        var vp = VoiceProfile.empty().withClauses(clauses, "seed", "steward");

        var block = vp.promptBlock();
        assertThat(block).startsWith("[voice guidance]");
        assertThat(block).contains("- greeting-tone: warm, brief");
        assertThat(block).contains("- reflective-pacing: slow, sentence-per-breath");
        // Preserved insertion order.
        assertThat(block.indexOf("greeting-tone"))
                .isLessThan(block.indexOf("reflective-pacing"));
    }

    @Test
    void promptBlock_returns_null_when_no_clauses() {
        assertThat(VoiceProfile.empty().promptBlock()).isNull();
    }

    @Test
    void jackson_round_trip_preserves_all_fields() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("a", "1");
        clauses.put("b", "2");
        var vp = VoiceProfile.empty()
                .withClauses(clauses, "seed", "steward")
                .withClauses(Map.of("a", "1", "b", "2", "c", "3"), "forge add c", "forge");

        var json = mapper.writeValueAsString(vp);
        var decoded = mapper.readValue(json, VoiceProfile.class);

        assertThat(decoded.revision()).isEqualTo(vp.revision());
        assertThat(decoded.frozen()).isEqualTo(vp.frozen());
        assertThat(decoded.clauses()).containsAllEntriesOf(vp.clauses());
        assertThat(decoded.history()).hasSize(vp.history().size());
        assertThat(decoded.history().getLast().author()).isEqualTo("forge");
    }

    @Test
    void jackson_handles_missing_optional_fields_gracefully() throws Exception {
        // A manifest written before the VoiceProfile existed won't have history
        // or clauses on disk — partial JSON must still deserialize.
        var mapper = new ObjectMapper().findAndRegisterModules();
        var partial = "{\"revision\":0,\"frozen\":false}";
        var decoded = mapper.readValue(partial, VoiceProfile.class);
        assertThat(decoded.clauses()).isEmpty();
        assertThat(decoded.history()).isEmpty();
    }

    @Test
    void soul_manifest_voiceProfile_is_nullable_and_round_trips() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        // A manifest with null voiceProfile (the backwards-compat case).
        var m = SoulManifest.birth(
                "did:key:test", "mb",
                List.of(),
                new AgentProfile(
                        "Wyrd", "wyrd", "agent", "desc", "prompt", 8192, 1024, 0.7, null),
                GenomeProfile.defaults());

        assertThat(m.voiceProfile()).isNull();

        var json = mapper.writeValueAsString(m);
        var decoded = mapper.readValue(json, SoulManifest.class);
        assertThat(decoded.voiceProfile()).isNull();

        // withVoiceProfile round trip.
        var withVp = m.withVoiceProfile(VoiceProfile.empty()
                .withClauses(Map.of("k", "v"), "seed", "steward"));
        assertThat(withVp.voiceProfile()).isNotNull();
        assertThat(withVp.voiceProfile().revision()).isEqualTo(1);
    }
}
