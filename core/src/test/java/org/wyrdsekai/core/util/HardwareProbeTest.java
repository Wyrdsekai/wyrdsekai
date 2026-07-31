package org.wyrdsekai.core.util;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingModel;

import static org.assertj.core.api.Assertions.*;

/**
 * Smoke tests for {@link HardwareProbe}. The recommendation logic is pure
 * over RAM input, so we exercise the boundary cases directly against
 * {@link HardwareProbe#recommendedEmbeddingModelForRam(long)} rather than
 * trying to manipulate the host probe.
 */
class HardwareProbeTest {

    @Test
    void availableRamIsAtLeastOneGB() {
        // Whatever environment we're on (Linux container, dev laptop, CI runner),
        // it has more than 1 GB. The fallback path returns Math.max(1L, ...) so
        // even a hostile probe never returns 0.
        long ram = HardwareProbe.availableRamGB();
        assertThat(ram).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void recommendBgeM3ForLargeHosts() {
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(8))
            .isSameAs(EmbeddingModel.BGE_M3);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(16))
            .isSameAs(EmbeddingModel.BGE_M3);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(64))
            .isSameAs(EmbeddingModel.BGE_M3);
    }

    @Test
    void recommendE5BaseForMidHosts() {
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(4))
            .isSameAs(EmbeddingModel.E5_BASE);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(7))
            .isSameAs(EmbeddingModel.E5_BASE);
    }

    @Test
    void recommendE5SmallForLowRam() {
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(3))
            .isSameAs(EmbeddingModel.E5_SMALL);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(1))
            .isSameAs(EmbeddingModel.E5_SMALL);
    }

    @Test
    void boundaryAtFourGB() {
        // 4 → E5_BASE, 3 → E5_SMALL. Boundary is closed at 4 by spec.
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(4))
            .isSameAs(EmbeddingModel.E5_BASE);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(3))
            .isSameAs(EmbeddingModel.E5_SMALL);
    }

    @Test
    void boundaryAtEightGB() {
        // 8 → BGE_M3, 7 → E5_BASE. Boundary is closed at 8 by spec.
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(8))
            .isSameAs(EmbeddingModel.BGE_M3);
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(7))
            .isSameAs(EmbeddingModel.E5_BASE);
    }

    @Test
    void recommendationIsNeverNull() {
        // Defensive: even at 0 RAM (impossible in practice) we fall through to E5_SMALL.
        assertThat(HardwareProbe.recommendedEmbeddingModelForRam(0))
            .isSameAs(EmbeddingModel.E5_SMALL);
    }
}
