package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An own-time turn that offers tools is a working turn; only the proactive observation speaks. */
class OwnTimeActsTest {

    @Test
    void a_turn_with_tools_works_unless_it_is_for_speaking() {
        assertThat(CompanionActor.ownTimeTurnWorks(false, true)).isTrue();
        assertThat(CompanionActor.ownTimeTurnWorks(true, true)).isFalse();
        assertThat(CompanionActor.ownTimeTurnWorks(false, false)).isFalse();
    }

    @Test
    void the_working_mix_drops_the_floor_and_raises_the_work_adapter() {
        // slot 0 species, 1 styled, 2 work, 3 her night; working floor 0, work scale 1.0
        var working = CompanionActor.singleBrainAdapterMix(false, false, 4, 3, 0.3, 1, 0.4, 0.0, 2, 1.0);
        assertThat(working).containsEntry("adapter:0", 0.0).containsEntry("adapter:2", 1.0)
            .containsEntry("adapter:1", 0.0).containsEntry("adapter:3", 0.0);
        var speaking = CompanionActor.singleBrainAdapterMix(false, true, 4, 3, 0.3, 1, 0.4, 0.0, 2, 1.0);
        assertThat(speaking).containsEntry("adapter:0", 1.0).containsEntry("adapter:2", 0.0)
            .containsEntry("adapter:1", 0.4);
    }
}
