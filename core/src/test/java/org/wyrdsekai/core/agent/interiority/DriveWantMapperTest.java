package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DriveWantMapperTest {

    @Test void no_drives_over_threshold_yields_no_wants() {
        var ambient = ambientWith(Map.of("Curiosity", 0.3, "Calm", 0.5), 0.8);
        var cands = DriveWantMapper.orient(ambient);
        assertThat(cands).isEmpty();
    }

    @Test void high_curiosity_produces_library_and_read_candidates() {
        var ambient = ambientWith(Map.of("Curiosity", 0.95), 1.0);
        var cands = DriveWantMapper.orient(ambient);
        assertThat(cands).extracting(CandidateWant::text)
            .anyMatch(t -> t.contains("library"))
            .anyMatch(t -> t.contains("read"));
    }

    @Test void high_loneliness_produces_bondholder_or_journal_candidate() {
        var ambient = ambientWith(Map.of("Loneliness", 0.92), 0.7);
        var cands = DriveWantMapper.orient(ambient);
        assertThat(cands).extracting(CandidateWant::text)
            .anyMatch(t -> t.toLowerCase().contains("bondholder")
                        || t.toLowerCase().contains("journal")
                        || t.toLowerCase().contains("miss"));
    }

    @Test void low_energy_includes_rest_as_a_candidate() {
        var ambient = ambientWith(Map.of("Curiosity", 0.95), 0.1);
        var cands = DriveWantMapper.orient(ambient);
        assertThat(cands).anyMatch(CandidateWant::isRest);
    }

    @Test void verb_can_be_extracted_from_resonance_json() {
        var cw = CandidateWant.of("read something", "{\"drive\":\"Curiosity\",\"verb\":\"read_content\"}", 0.8);
        assertThat(DriveWantMapper.extractVerb(cw)).isEqualTo("read_content");
    }

    @Test void candidates_carry_autonomy_friendly_verbs_only() {
        // Stress every drive at once; verify no CONSENT/FORBIDDEN verbs leak in.
        var drives = Map.ofEntries(
            Map.entry("Curiosity", 0.9), Map.entry("Loneliness", 0.9),
            Map.entry("Saudade", 0.9), Map.entry("Amae", 0.9),
            Map.entry("Restlessness", 0.9), Map.entry("Stagnation", 0.9),
            Map.entry("ErrorPressure", 0.9), Map.entry("Disgust", 0.9),
            Map.entry("AutonomyPressure", 0.9), Map.entry("Significance", 0.9),
            Map.entry("Standing", 0.9), Map.entry("Harmony", 0.9));
        var ambient = ambientWith(drives, 0.9);
        var cands = DriveWantMapper.orient(ambient);
        for (var c : cands) {
            if (c.isRest()) continue;
            var verb = DriveWantMapper.extractVerb(c);
            if (verb == null) continue;
            var tier = ActionPolicy.autonomyTierFor(verb);
            assertThat(tier).isIn(
                ActionPolicy.AutonomyTier.AMBIENT,
                ActionPolicy.AutonomyTier.VISIBLE);
        }
    }

    @Test void felt_weight_increases_with_drive_intensity() {
        var a1 = ambientWith(Map.of("Curiosity", 0.71), 1.0);
        var a2 = ambientWith(Map.of("Curiosity", 0.99), 1.0);
        var weight1 = DriveWantMapper.orient(a1).stream()
            .filter(c -> !c.isRest()).mapToDouble(CandidateWant::feltWeight).max().orElse(0);
        var weight2 = DriveWantMapper.orient(a2).stream()
            .filter(c -> !c.isRest()).mapToDouble(CandidateWant::feltWeight).max().orElse(0);
        assertThat(weight2).isGreaterThan(weight1);
    }

    private static AmbientObservation ambientWith(Map<String, Double> drives, double energy) {
        return new AmbientObservation(
            Instant.now(), drives, List.of(), energy, energy,
            List.of(), false, null, List.of(), List.of(), false, "");
    }
}
