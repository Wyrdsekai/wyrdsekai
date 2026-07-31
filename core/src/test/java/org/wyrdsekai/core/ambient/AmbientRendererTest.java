package org.wyrdsekai.core.ambient;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.embodiment.AmbientPhase;
import org.wyrdsekai.common.embodiment.AmbientTone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AmbientRenderer} — the Layer 5
 * room×phase → (descriptor, imprint) lookup.
 */
class AmbientRendererTest {

    @Test
    void allTwentyTwoFoundationRoomsHaveTones() {
        var tones = AmbientRenderer.foundationTones();
        assertThat(tones).hasSize(22);
        // Spot-check a few load-bearing rooms.
        assertThat(tones).containsEntry("library", AmbientTone.SOFT);
        assertThat(tones).containsEntry("the-forge", AmbientTone.WARM);
        assertThat(tones).containsEntry("vault", AmbientTone.DIM);
        assertThat(tones).containsEntry("nexus", AmbientTone.BRIGHT);
    }

    @Test
    void allFourProvisionerKindsHaveTones() {
        var provisioners = AmbientRenderer.provisionerTones();
        assertThat(provisioners.keySet()).containsExactlyInAnyOrder(
            "study", "workshop", "home", "parlor");
    }

    @Test
    void provisionerRoomIdInheritsToneFromKindPrefix() {
        assertThat(AmbientRenderer.toneFor("study-d4f1a23b")).isEqualTo(AmbientTone.SOFT);
        assertThat(AmbientRenderer.toneFor("home-companion-7"))  .isEqualTo(AmbientTone.WARM);
        assertThat(AmbientRenderer.toneFor("parlor-2"))           .isEqualTo(AmbientTone.WARM);
        assertThat(AmbientRenderer.toneFor("workshop-coder-a"))  .isEqualTo(AmbientTone.WARM);
    }

    @Test
    void unknownRoomFallsBackToSoft() {
        assertThat(AmbientRenderer.toneFor("unknown-room")).isEqualTo(AmbientTone.SOFT);
        assertThat(AmbientRenderer.toneFor(null)).isEqualTo(AmbientTone.SOFT);
    }

    @Test
    void descriptorReturnsPerRoomKeyWhenAvailable() {
        // Foundation room → per-room descriptor that mentions the room's character.
        // The English library entry mentions "library" in the rendered text.
        var libraryMidday = AmbientRenderer.descriptor("library", AmbientPhase.MIDDAY, "en");
        assertThat(libraryMidday).isNotNull();
        assertThat(libraryMidday).isNotBlank();
        assertThat(libraryMidday.toLowerCase()).contains("page");
    }

    @Test
    void descriptorRendersForAllFoundationRoomsAndPhases() {
        for (var roomId : AmbientRenderer.foundationTones().keySet()) {
            for (var phase : AmbientPhase.values()) {
                var line = AmbientRenderer.descriptor(roomId, phase, "en");
                assertThat(line)
                    .as("room=%s phase=%s must render a non-blank descriptor", roomId, phase)
                    .isNotNull()
                    .isNotBlank();
                assertThat(line.length())
                    .as("descriptor for %s/%s should be a one-line sensory tag", roomId, phase)
                    .isLessThan(400);
            }
        }
    }

    @Test
    void descriptorRendersForAllProvisionerKindsAndPhases() {
        for (var kind : AmbientRenderer.provisionerTones().keySet()) {
            // Foundation rooms named "workshop" and "parlor" exist, so use the
            // canonical kind for the per-room key (a real provisioner room
            // would be "study-<uuid>" etc., but the catalog keys are kind-keyed).
            for (var phase : AmbientPhase.values()) {
                var line = AmbientRenderer.descriptor(kind + "-test-uuid", phase, "en");
                assertThat(line)
                    .as("provisioner %s phase=%s must render", kind, phase)
                    .isNotNull()
                    .isNotBlank();
            }
        }
    }

    @Test
    void descriptorLocaleFallsBackToEnglish() {
        // Unknown locale ("zz") should still return the English fallback rather than blank.
        var line = AmbientRenderer.descriptor("library", AmbientPhase.NIGHT, "zz");
        assertThat(line).isNotNull().isNotBlank();
    }

    @Test
    void descriptorEsAndJaReturnTranslatedText() {
        var en = AmbientRenderer.descriptor("library", AmbientPhase.MIDDAY, "en");
        var es = AmbientRenderer.descriptor("library", AmbientPhase.MIDDAY, "es");
        var ja = AmbientRenderer.descriptor("library", AmbientPhase.MIDDAY, "ja");
        assertThat(en).isNotBlank();
        assertThat(es).isNotBlank().isNotEqualTo(en);
        assertThat(ja).isNotBlank().isNotEqualTo(en).isNotEqualTo(es);
    }

    @Test
    void canonicalRoomKindStripsProvisionerSuffixes() {
        assertThat(AmbientRenderer.canonicalRoomKind("study-abc-123")).isEqualTo("study");
        assertThat(AmbientRenderer.canonicalRoomKind("parlor-7")).isEqualTo("parlor");
        assertThat(AmbientRenderer.canonicalRoomKind("home-companion-x")).isEqualTo("home");
        // Foundation rooms pass through unchanged.
        assertThat(AmbientRenderer.canonicalRoomKind("library")).isEqualTo("library");
        assertThat(AmbientRenderer.canonicalRoomKind("the-forge")).isEqualTo("the-forge");
    }

    @Test
    void imprintIsNonEmptyForEveryRoomAndPhase() {
        for (var roomId : AmbientRenderer.foundationTones().keySet()) {
            for (var phase : AmbientPhase.values()) {
                var imprint = AmbientRenderer.imprint(roomId, phase);
                assertThat(imprint).isNotNull();
                assertThat(imprint.tanks())
                    .as("imprint for %s/%s must have at least one tank delta", roomId, phase)
                    .isNotEmpty();
            }
        }
    }

    @Test
    void imprintDawnAndMiddayNudgeEnergy() {
        var libraryDawn = AmbientRenderer.imprint("library", AmbientPhase.DAWN);
        var libraryMidday = AmbientRenderer.imprint("library", AmbientPhase.MIDDAY);
        assertThat(libraryDawn.tanks()).containsKey("energy");
        assertThat(libraryMidday.tanks()).containsKey("energy");
        assertThat(libraryDawn.tanks().get("energy")).isGreaterThan(0.0);
        assertThat(libraryMidday.tanks().get("energy")).isGreaterThan(0.0);
    }

    @Test
    void imprintDuskAndNightBuildEquanimity() {
        var libraryDusk = AmbientRenderer.imprint("library", AmbientPhase.DUSK);
        var libraryNight = AmbientRenderer.imprint("library", AmbientPhase.NIGHT);
        assertThat(libraryDusk.tanks()).containsKey("equanimity");
        assertThat(libraryNight.tanks()).containsKey("equanimity");
        assertThat(libraryDusk.tanks().get("equanimity")).isGreaterThan(0.0);
        assertThat(libraryNight.tanks().get("equanimity")).isGreaterThan(0.0);
    }

    @Test
    void brightRoomAtMiddayAmplifiesEnergyAboveDimRoom() {
        // BRIGHT (nexus) at MIDDAY should hit energy harder than DIM (vault) at MIDDAY.
        var bright = AmbientRenderer.imprint("nexus", AmbientPhase.MIDDAY);
        var dim = AmbientRenderer.imprint("vault", AmbientPhase.MIDDAY);
        assertThat(bright.tanks().get("energy"))
            .isGreaterThan(dim.tanks().get("energy"));
    }

    @Test
    void dimRoomAtNightDeepensEquanimityAboveBrightRoom() {
        // DIM (vault) at NIGHT should deepen equanimity beyond BRIGHT (nexus) at NIGHT.
        var dim = AmbientRenderer.imprint("vault", AmbientPhase.NIGHT);
        var bright = AmbientRenderer.imprint("nexus", AmbientPhase.NIGHT);
        assertThat(dim.tanks().get("equanimity"))
            .isGreaterThan(bright.tanks().get("equanimity"));
    }

    @Test
    void warmRoomAtNightAddsSoothing() {
        // WARM (parlor) at NIGHT should include a soothing imprint.
        var warm = AmbientRenderer.imprint("parlor", AmbientPhase.NIGHT);
        assertThat(warm.tanks()).containsKey("soothing");
        assertThat(warm.tanks().get("soothing")).isGreaterThan(0.0);
    }

    @Test
    void isFoundationRoomDistinguishesProvisionerIds() {
        assertThat(AmbientRenderer.isFoundationRoom("library")).isTrue();
        assertThat(AmbientRenderer.isFoundationRoom("study-abc")).isFalse();
        assertThat(AmbientRenderer.isFoundationRoom("home-x")).isFalse();
    }

    @Test
    void nullPhaseReturnsEmptyImprint() {
        var imprint = AmbientRenderer.imprint("library", null);
        assertThat(imprint).isNotNull();
        assertThat(imprint.tanks()).isEmpty();
    }
}
