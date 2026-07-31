package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriveModulatedSamplingTest {

    @Test
    void defaults_with_null_state() {
        var params = DriveModulatedSampling.compute(null, null);
        assertEquals(DriveModulatedSampling.BASE_TEMPERATURE, params.temperature(), 0.001);
        assertEquals(DriveModulatedSampling.BASE_TOP_P, params.topP(), 0.001);
        assertEquals(DriveModulatedSampling.BASE_MAX_TOKENS, params.maxTokens());
    }

    @Test
    void neutral_drives_produce_near_base() {
        var drives = DriveState.initial(); // all zeros
        var tanks = VitalityState.initial();
        var params = DriveModulatedSampling.compute(drives, tanks);

        // With all drives at 0, temperature should be near base but adjusted by confidence
        assertTrue(Math.abs(params.temperature() - DriveModulatedSampling.BASE_TEMPERATURE) < 0.15,
            "Neutral drives should produce near-base temperature: " + params.temperature());
    }

    @Test
    void high_creativity_increases_temperature() {
        var creative = new DriveState(0, 0, 0, 0, 0, 0, 0, 0.9); // creativity=0.9
        var neutral = DriveState.initial();
        var tanks = VitalityState.initial();

        var creativeParams = DriveModulatedSampling.compute(creative, tanks);
        var neutralParams = DriveModulatedSampling.compute(neutral, tanks);

        assertTrue(creativeParams.temperature() > neutralParams.temperature(),
            "Creativity should increase temperature: " +
            creativeParams.temperature() + " vs " + neutralParams.temperature());
    }

    @Test
    void high_grief_decreases_temperature() {
        var grieving = new DriveState(0, 0, 0, 0, 0, 0.9, 0, 0); // grief=0.9
        var neutral = DriveState.initial();
        var tanks = VitalityState.initial();

        var griefParams = DriveModulatedSampling.compute(grieving, tanks);
        var neutralParams = DriveModulatedSampling.compute(neutral, tanks);

        assertTrue(griefParams.temperature() < neutralParams.temperature(),
            "Grief should decrease temperature: " +
            griefParams.temperature() + " vs " + neutralParams.temperature());
    }

    @Test
    void high_grief_reduces_max_tokens() {
        var grieving = new DriveState(0, 0, 0, 0, 0, 0.9, 0, 0);
        var neutral = DriveState.initial();
        var tanks = VitalityState.initial();

        var griefParams = DriveModulatedSampling.compute(grieving, tanks);
        var neutralParams = DriveModulatedSampling.compute(neutral, tanks);

        assertTrue(griefParams.maxTokens() < neutralParams.maxTokens(),
            "Grief should reduce max tokens: " +
            griefParams.maxTokens() + " vs " + neutralParams.maxTokens());
    }

    @Test
    void low_energy_reduces_max_tokens() {
        var drives = DriveState.initial();
        var full = VitalityState.initial();  // energy=1.0
        var exhausted = new VitalityState(1.0, 0.7, 0.2, 0.7, 0.0, 0.5, 0.5, 0.8);

        var fullParams = DriveModulatedSampling.compute(drives, full);
        var exhaustedParams = DriveModulatedSampling.compute(drives, exhausted);

        assertTrue(exhaustedParams.maxTokens() < fullParams.maxTokens(),
            "Low energy should reduce max tokens: " +
            exhaustedParams.maxTokens() + " vs " + fullParams.maxTokens());
    }

    @Test
    void high_confidence_increases_top_p() {
        var drives = DriveState.initial();
        var confident = new VitalityState(1.0, 0.95, 0.8, 0.8, 0.0, 0.5, 0.7, 0.8);
        var unsure = new VitalityState(1.0, 0.1, 0.8, 0.8, 0.0, 0.5, 0.7, 0.8);

        var confidentParams = DriveModulatedSampling.compute(drives, confident);
        var unsureParams = DriveModulatedSampling.compute(drives, unsure);

        assertTrue(confidentParams.topP() > unsureParams.topP(),
            "High confidence should increase top_p: " +
            confidentParams.topP() + " vs " + unsureParams.topP());
    }

    @Test
    void seeking_increases_presence_penalty() {
        var seeking = new DriveState(0.9, 0, 0, 0, 0, 0, 0, 0); // seeking=0.9
        var neutral = DriveState.initial();
        var tanks = VitalityState.initial();

        var seekingParams = DriveModulatedSampling.compute(seeking, tanks);
        var neutralParams = DriveModulatedSampling.compute(neutral, tanks);

        assertTrue(seekingParams.presencePenalty() > neutralParams.presencePenalty(),
            "Seeking should increase presence penalty (less repetition)");
    }

    @Test
    void all_parameters_stay_within_bounds() {
        // Extreme drive states
        var maxDrives = new DriveState(1, 1, 1, 1, 1, 1, 1, 1);
        var minDrives = DriveState.initial();
        var maxTanks = VitalityState.initial();
        var minTanks = new VitalityState(0.1, 0.1, 0.1, 0.1, 1.0, 0.1, 0.1, 0.1);

        for (var drives : new DriveState[]{maxDrives, minDrives}) {
            for (var tanks : new VitalityState[]{maxTanks, minTanks}) {
                var params = DriveModulatedSampling.compute(drives, tanks);
                assertTrue(params.temperature() >= 0.45 && params.temperature() <= 0.95,
                    "Temperature out of bounds: " + params.temperature());
                assertTrue(params.topP() >= 0.60 && params.topP() <= 0.95,
                    "Top-p out of bounds: " + params.topP());
                assertTrue(params.maxTokens() >= 64 && params.maxTokens() <= 512,
                    "Max tokens out of bounds: " + params.maxTokens());
                assertTrue(params.presencePenalty() >= 0.5 && params.presencePenalty() <= 2.0,
                    "Presence penalty out of bounds: " + params.presencePenalty());
                assertTrue(params.repetitionPenalty() >= 0.9 && params.repetitionPenalty() <= 1.3,
                    "Repetition penalty out of bounds: " + params.repetitionPenalty());
            }
        }
    }

    @Test
    void play_plus_creativity_produces_highest_temperature() {
        var playful = new DriveState(0, 0, 0.9, 0, 0, 0, 0, 0.9); // play + creativity
        var tanks = VitalityState.initial();
        var params = DriveModulatedSampling.compute(playful, tanks);
        assertTrue(params.temperature() > 0.80, "Play+creativity should be high temp: " + params.temperature());
    }

    @Test
    void grief_plus_low_energy_produces_minimal_output() {
        var grieving = new DriveState(0, 0, 0, 0, 0, 0.9, 0, 0);
        var exhausted = new VitalityState(0.5, 0.4, 0.15, 0.5, 0.3, 0.2, 0.3, 0.3);
        var params = DriveModulatedSampling.compute(grieving, exhausted);

        assertTrue(params.temperature() < 0.65, "Should be low temp: " + params.temperature());
        assertTrue(params.maxTokens() < 120, "Should be very short: " + params.maxTokens());
    }
}
