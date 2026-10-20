package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rented AMD box shipped a stand-in {@code nvidia-smi} that printed "nvidia-smi not found. This is
 * AMD country." and exited 0 (2026-10-20). The node read the clean exit as a GPU; the launcher the
 * same, and planned nothing on the MI300X beside it. A card is a thing that answers with a name and
 * a memory size.
 */
class AStubNvidiaSmiIsNotACardTest {

    @Test
    @DisplayName("a name and a memory size is a card; a sentence is not; nothing is not")
    void aCardHasMemory() {
        assertThat(NodeCapabilities.nvidiaReportsACard("NVIDIA GeForce RTX 4060 Ti, 16380")).isTrue();
        assertThat(NodeCapabilities.nvidiaReportsACard("NVIDIA GeForce RTX 5060 Ti, 16311\nNVIDIA RTX A6000, 49140")).isTrue();
        assertThat(NodeCapabilities.nvidiaReportsACard("nvidia-smi not found. This is AMD country.")).isFalse();
        assertThat(NodeCapabilities.nvidiaReportsACard("No devices were found")).isFalse();
        assertThat(NodeCapabilities.nvidiaReportsACard("")).isFalse();
        assertThat(NodeCapabilities.nvidiaReportsACard(null)).isFalse();
    }
}
