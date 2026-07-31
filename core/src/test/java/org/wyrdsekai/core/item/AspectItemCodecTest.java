package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AspectItemCodecTest {

    @Nested
    class DecodeTests {

        @Test
        void roundtrip_encode_decode() {
            var def = AspectItemCodec.create(
                "You are in focused mode.", Map.of("focus", 0.15, "rapport", -0.05),
                "wearing glasses", "garment", 40);

            String json = AspectItemCodec.encode(def);
            var decoded = AspectItemCodec.decode(json);

            assertThat(decoded).isNotNull();
            assertThat(decoded.version()).isEqualTo(1);
            assertThat(decoded.promptOverlay()).isEqualTo("You are in focused mode.");
            assertThat(decoded.vitalityShifts()).containsEntry("focus", 0.15);
            assertThat(decoded.vitalityShifts()).containsEntry("rapport", -0.05);
            assertThat(decoded.selfDescription()).isEqualTo("wearing glasses");
            assertThat(decoded.slotHint()).isEqualTo("garment");
            assertThat(decoded.tokenEstimate()).isEqualTo(40);
        }

        @Test
        void decode_null_returns_null() {
            assertThat(AspectItemCodec.decode((String) null)).isNull();
        }

        @Test
        void decode_blank_returns_null() {
            assertThat(AspectItemCodec.decode("  ")).isNull();
        }

        @Test
        void decode_invalid_json_returns_null() {
            assertThat(AspectItemCodec.decode("not json")).isNull();
        }

        @Test
        void decode_wrong_category_returns_null() {
            var item = SoulItem.create("skill", "test", "{}", "did:x", 0.5);
            assertThat(AspectItemCodec.decode(item)).isNull();
        }

        @Test
        void decode_missing_fields_uses_defaults() {
            var decoded = AspectItemCodec.decode("{\"version\": 1}");

            assertThat(decoded).isNotNull();
            assertThat(decoded.vitalityShifts()).isEmpty();
            assertThat(decoded.slotHint()).isEqualTo("garment");
            assertThat(decoded.tokenEstimate()).isEqualTo(20);
            assertThat(decoded.promptOverlay()).isNull();
        }

        @Test
        void decode_soul_item() {
            var def = AspectItemCodec.create(
                "Overlay text", Map.of("focus", 0.1),
                "wearing robe", "garment", 30);
            var item = AspectItemCodec.toSoulItem("Researcher's Robe", def, "did:agent:1", 0.4);
            var decoded = AspectItemCodec.decode(item);

            assertThat(decoded).isNotNull();
            assertThat(decoded.promptOverlay()).isEqualTo("Overlay text");
        }
    }

    @Nested
    class DefinitionTests {

        @Test
        void has_prompt_overlay() {
            var def = AspectItemCodec.create("Text", Map.of(), null, "garment", 20);
            assertThat(def.hasPromptOverlay()).isTrue();

            var noOverlay = AspectItemCodec.create(null, Map.of(), null, "garment", 20);
            assertThat(noOverlay.hasPromptOverlay()).isFalse();

            var blankOverlay = AspectItemCodec.create("  ", Map.of(), null, "garment", 20);
            assertThat(blankOverlay.hasPromptOverlay()).isFalse();
        }

        @Test
        void has_vitality_shifts() {
            var withShifts = AspectItemCodec.create(null, Map.of("focus", 0.1), null, "garment", 20);
            assertThat(withShifts.hasVitalityShifts()).isTrue();

            var noShifts = AspectItemCodec.create(null, Map.of(), null, "garment", 20);
            assertThat(noShifts.hasVitalityShifts()).isFalse();
        }
    }

    @Nested
    class ToSoulItemTests {

        @Test
        void creates_valid_soul_item() {
            var def = AspectItemCodec.create(
                "Focused overlay", Map.of("focus", 0.15),
                "wearing glasses", "garment", 40);
            var item = AspectItemCodec.toSoulItem("Focused Mode", def, "did:agent:1", 0.4);

            assertThat(item.category()).isEqualTo("aspect");
            assertThat(item.label()).isEqualTo("Focused Mode");
            assertThat(item.creatorDid()).isEqualTo("did:agent:1");
            assertThat(item.significance()).isEqualTo(0.4);
            assertThat(item.text()).contains("focus");
        }

        @Test
        void tags_include_name_and_slot() {
            var def = AspectItemCodec.create(null, Map.of(), "carrying compass", "accessory", 10);
            var item = AspectItemCodec.toSoulItem("Wayfinder Compass", def, "did:agent:1", 0.3);

            assertThat(item.tags()).contains("wayfinder-compass");
            assertThat(item.tags()).contains("accessory");
        }
    }
}
