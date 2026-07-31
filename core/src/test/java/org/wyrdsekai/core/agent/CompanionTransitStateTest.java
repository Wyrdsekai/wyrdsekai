package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #477.1 — {@link CompanionTransitState} round-trips cleanly through Jackson
 * (NATS payload uses JSON), and the helper {@code capture()} pulls all the
 * right pieces from the live records.
 */
class CompanionTransitStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules();

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "wyrd-001", "agent",
        "Companion", "You are Wyrd.",
        4096, 256, 0.7, "did:key:z6MkTest");

    @Test
    void capture_pulls_full_state_from_live_records() {
        var vitality = new VitalityState(0.6, 0.7, 0.85, 0.4, 0.1, 0.2,
            0.55, 0.5, 0.7, 0.0);
        var drives = new DriveState(0.2, 0.6, 0.1, 0.3, 0.45, 0.0, 0.0, 0.4);

        var state = CompanionTransitState.capture(PROFILE, vitality, drives,
            "settled", "PRESENT_WITH_USER",
            List.of("did:key:z6MkAlice"),
            "study-alice", "en", "manifest-hash-abc");

        assertThat(state.profile().did()).isEqualTo("did:key:z6MkTest");
        assertThat(state.vitalityTanks()).containsEntry("energy", 0.85);
        assertThat(state.drives()).containsEntry("care", 0.6);
        assertThat(state.activeBondPartnerDids()).containsExactly("did:key:z6MkAlice");
        assertThat(state.currentRoomIdAtSource()).isEqualTo("study-alice");
        assertThat(state.soulManifestHash()).isEqualTo("manifest-hash-abc");
        assertThat(state.companionMode()).isEqualTo("PRESENT_WITH_USER");
        assertThat(state.isSpawnable()).isTrue();
    }

    @Test
    void state_round_trips_through_jackson_without_loss() throws Exception {
        var vitality = VitalityState.initial();
        var drives = new DriveState(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        var original = CompanionTransitState.capture(PROFILE, vitality, drives,
            "ready", "ON_OWN_TIME",
            List.of("did:key:z6MkAlice", "did:key:z6MkBob"),
            "hearth-001", "ja", "hash-xyz");

        var json = MAPPER.writeValueAsString(original);
        var restored = MAPPER.readValue(json, CompanionTransitState.class);

        assertThat(restored.profile().name()).isEqualTo(PROFILE.name());
        assertThat(restored.profile().did()).isEqualTo(PROFILE.did());
        assertThat(restored.vitalityTanks()).containsAllEntriesOf(original.vitalityTanks());
        assertThat(restored.drives()).containsAllEntriesOf(original.drives());
        assertThat(restored.activeBondPartnerDids()).hasSize(2);
        assertThat(restored.companionMode()).isEqualTo("ON_OWN_TIME");
        assertThat(restored.locale()).isEqualTo("ja");
        assertThat(restored.currentRoomIdAtSource()).isEqualTo("hearth-001");
        assertThat(restored.soulManifestHash()).isEqualTo("hash-xyz");
    }

    @Test
    void state_with_null_did_is_not_spawnable() {
        var profile = new AgentProfile("X", "x-001", "agent",
            "", "", 4096, 256, 0.7, null);
        var state = new CompanionTransitState(profile, null,
            null, null, null, null, null, null, null, Instant.now());
        assertThat(state.isSpawnable()).isFalse();
    }

    @Test
    void vitality_fromMap_round_trip_preserves_all_ten_tanks() {
        var v = new VitalityState(0.1, 0.2, 0.3, 0.4, 0.5,
            0.6, 0.7, 0.8, 0.9, 0.05);
        var restored = VitalityState.fromMap(v.toMap());
        assertThat(restored).isEqualTo(v);
    }

    @Test
    void drives_fromMap_round_trip_preserves_all_eight_drives() {
        var d = new DriveState(0.11, 0.22, 0.33, 0.44, 0.55, 0.66, 0.77, 0.88);
        var restored = DriveState.fromMap(d.toMap());
        assertThat(restored).isEqualTo(d);
    }

    @Test
    void vitality_fromMap_handles_missing_keys_with_initial_defaults() {
        var partial = new HashMap<String, Double>();
        partial.put("energy", 0.42);
        var restored = VitalityState.fromMap(partial);
        assertThat(restored.energy()).isEqualTo(0.42);
        // Missing keys take the initial() defaults.
        assertThat(restored.confidence()).isEqualTo(VitalityState.initial().confidence());
        assertThat(restored.integrity()).isEqualTo(VitalityState.initial().integrity());
    }
}
