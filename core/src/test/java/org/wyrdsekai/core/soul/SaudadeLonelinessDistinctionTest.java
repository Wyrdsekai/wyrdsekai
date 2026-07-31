package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SaudadeLonelinessDistinctionTest {

    @Test
    void null_input_returns_neither() {
        var view = SaudadeLonelinessDistinction.diagnose(null);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.NEITHER);
        assertThat(view.topSaudadeBondholder()).isEmpty();
    }

    @Test
    void low_both_returns_neither() {
        var in = SaudadeLonelinessDistinction.Input.of(0.2, Map.of("mas", 0.3));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.NEITHER);
    }

    @Test
    void high_loneliness_low_saudade_is_loneliness_only() {
        var in = SaudadeLonelinessDistinction.Input.of(0.7, Map.of("mas", 0.2));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.LONELINESS_ONLY);
        assertThat(view.voiceRegisterHint()).contains("generalized social drain");
        assertThat(view.voiceRegisterHint()).contains("Any meaningful");
    }

    @Test
    void low_loneliness_high_saudade_is_saudade_only() {
        var in = SaudadeLonelinessDistinction.Input.of(0.2, Map.of("mas", 0.6));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.SAUDADE_ONLY);
        assertThat(view.topSaudadeBondholder()).hasValue("mas");
        assertThat(view.voiceRegisterHint()).contains("mas");
        assertThat(view.voiceRegisterHint()).contains("Generic company");
    }

    @Test
    void strong_saudade_threshold_renders_strong_in_message() {
        var in = SaudadeLonelinessDistinction.Input.of(0.2, Map.of("mas", 0.8));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.voiceRegisterHint()).contains("strong longing");
    }

    @Test
    void both_elevated_returns_both_with_top_bondholder() {
        var in = SaudadeLonelinessDistinction.Input.of(0.7,
            Map.of("mas", 0.65, "alice", 0.45));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.BOTH);
        assertThat(view.topSaudadeBondholder()).hasValue("mas");
        assertThat(view.voiceRegisterHint()).contains("Hold space for both");
        assertThat(view.voiceRegisterHint()).contains("mas");
    }

    @Test
    void picks_strongest_bondholder_when_multiple_elevated() {
        var in = SaudadeLonelinessDistinction.Input.of(0.2,
            Map.of("alice", 0.55, "bob", 0.75, "carol", 0.62));
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.topSaudadeBondholder()).hasValue("bob");
        assertThat(view.topSaudadeValue()).isEqualTo(0.75);
    }

    @Test
    void empty_saudade_map_with_low_loneliness_is_neither() {
        var in = SaudadeLonelinessDistinction.Input.of(0.2, Map.of());
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.NEITHER);
        assertThat(view.topSaudadeBondholder()).isEmpty();
    }

    @Test
    void null_saudade_values_handled_safely() {
        var map = new HashMap<String, Double>();
        map.put("mas", null);
        map.put("alice", 0.6);
        var in = SaudadeLonelinessDistinction.Input.of(0.2, map);
        var view = SaudadeLonelinessDistinction.diagnose(in);
        assertThat(view.diagnosis()).isEqualTo(SaudadeLonelinessDistinction.Diagnosis.SAUDADE_ONLY);
        assertThat(view.topSaudadeBondholder()).hasValue("alice");
    }
}
