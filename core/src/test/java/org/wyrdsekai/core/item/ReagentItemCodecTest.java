package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReagentItemCodecTest {

    @Nested
    class DecodeTests {

        @Test
        void roundtrip_encode_decode() {
            var def = ReagentItemCodec.create(
                Map.of("energy", 0.2, "errorPressure", -0.15),
                600, "Fatigue recedes.", true, 15);

            String json = ReagentItemCodec.encode(def);
            var decoded = ReagentItemCodec.decode(json);

            assertThat(decoded).isNotNull();
            assertThat(decoded.version()).isEqualTo(1);
            assertThat(decoded.vitalityEffects()).containsEntry("energy", 0.2);
            assertThat(decoded.vitalityEffects()).containsEntry("errorPressure", -0.15);
            assertThat(decoded.durationTicks()).isEqualTo(600);
            assertThat(decoded.promptOverlay()).isEqualTo("Fatigue recedes.");
            assertThat(decoded.consumable()).isTrue();
            assertThat(decoded.tokenEstimate()).isEqualTo(15);
        }

        @Test
        void decode_null_returns_null() {
            assertThat(ReagentItemCodec.decode((String) null)).isNull();
        }

        @Test
        void decode_invalid_json_returns_null() {
            assertThat(ReagentItemCodec.decode("not json")).isNull();
        }

        @Test
        void decode_wrong_category_returns_null() {
            var item = SoulItem.create("skill", "test", "{}", "did:x", 0.5);
            assertThat(ReagentItemCodec.decode(item)).isNull();
        }

        @Test
        void decode_missing_fields_uses_defaults() {
            var decoded = ReagentItemCodec.decode("{\"version\": 1}");

            assertThat(decoded).isNotNull();
            assertThat(decoded.vitalityEffects()).isEmpty();
            assertThat(decoded.durationTicks()).isEqualTo(300); // default
            assertThat(decoded.tokenEstimate()).isEqualTo(10);  // default
            assertThat(decoded.consumable()).isFalse();
        }
    }

    @Nested
    class DurationTests {

        @Test
        void effective_duration_clamped_to_max() {
            var def = ReagentItemCodec.create(
                Map.of("energy", 0.5), 5000, null, true, 10);
            assertThat(def.durationTicks()).isEqualTo(ReagentItemCodec.ReagentDefinition.MAX_DURATION);
            assertThat(def.effectiveDuration()).isEqualTo(1800);
        }

        @Test
        void effective_duration_normal() {
            var def = ReagentItemCodec.create(
                Map.of("energy", 0.1), 300, null, true, 10);
            assertThat(def.effectiveDuration()).isEqualTo(300);
        }
    }

    @Nested
    class ToSoulItemTests {

        @Test
        void creates_valid_soul_item() {
            var def = ReagentItemCodec.create(
                Map.of("energy", 0.2), 600, "Warm draught.", true, 15);
            var item = ReagentItemCodec.toSoulItem("Restoring Draught", def, "did:agent:1", 0.2);

            assertThat(item.category()).isEqualTo("reagent");
            assertThat(item.label()).isEqualTo("Restoring Draught");
            assertThat(item.creatorDid()).isEqualTo("did:agent:1");
            assertThat(item.significance()).isEqualTo(0.2);
        }

        @Test
        void tags_include_consumable() {
            var def = ReagentItemCodec.create(Map.of(), 300, null, true, 10);
            var item = ReagentItemCodec.toSoulItem("Tonic", def, "did:x", 0.2);

            assertThat(item.tags()).contains("consumable");
            assertThat(item.tags()).contains("reagent");
        }

        @Test
        void non_consumable_omits_tag() {
            var def = ReagentItemCodec.create(Map.of(), 300, null, false, 10);
            var item = ReagentItemCodec.toSoulItem("Aura", def, "did:x", 0.2);

            assertThat(item.tags()).doesNotContain("consumable");
        }
    }
}
