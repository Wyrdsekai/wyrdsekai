package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnotherMachineOfTheHouseIsABrainOnlyIfItSaysSoTest {

    private static Capability cap(String id, String cls) {
        return new Capability(id, "home", cls, List.of("m"), List.of(), true, true, 0.1, Instant.now(), null);
    }

    @Test
    void only_another_devices_gpu_or_cpu_brain_counts_never_this_node_or_a_phone() {
        assertThat(HouseholdBrains.someoneElseCanTake(List.of(), "me")).isFalse();
        assertThat(HouseholdBrains.someoneElseCanTake(List.of(cap("me", "llm.local-gpu")), "me")).as("my own advert").isFalse();
        assertThat(HouseholdBrains.someoneElseCanTake(List.of(cap("phone-1", "llm.phone"), cap("probe", "probe")), "me")).isFalse();
        assertThat(HouseholdBrains.someoneElseCanTake(List.of(cap("me", "llm.local-gpu"), cap("laptop", "llm.local-cpu")), "me")).isTrue();
        assertThat(HouseholdBrains.someoneElseCanTake(List.of(cap("gpu-box", "llm.local-gpu")), "me")).isTrue();
        assertThat(HouseholdBrains.someoneElseCanTake(null, "me")).isFalse();
    }
}
