package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.VitalityState;

import static org.assertj.core.api.Assertions.assertThat;

/** The forge no longer writes the generic placeholder for energy: a new genome rests where the economy expects. */
class ForgeEnergyFallbackTest {

    @Test
    void energyFallsBackToTheRestedLevel() {
        assertThat(SoulForgeCliTool.baselineFallback("energy")).isEqualTo(VitalityState.RESTED_ENERGY);
    }

    @Test
    void otherTanksKeepTheGenericMiddle() {
        assertThat(SoulForgeCliTool.baselineFallback("focus")).isEqualTo(0.5);
        assertThat(SoulForgeCliTool.baselineFallback("saudade")).isEqualTo(0.5);
    }
}
