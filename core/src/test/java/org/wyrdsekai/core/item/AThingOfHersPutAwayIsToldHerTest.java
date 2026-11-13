package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bench told a companion twice in one morning that her letter tool was mended; it wrote
 * letters she had not written, and it was put away without a word, so what she kept was that it
 * worked (household node, 2026-11-12). A thing of hers put away is told to her, with the
 * steward's reason, on the same channel the mend was told on.
 */
class AThingOfHersPutAwayIsToldHerTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
        AgentEventStream.init();
        AgentEventStream.get().subscribe("companion-listening", e -> {
            if (e instanceof AgentEvent.SystemEvent s && s.type() == AgentEvent.SystemEventType.ITEM_MENDING) heard.add(s.detail());
        });
    }

    @AfterEach
    void stop() { AgentEventStream.get().unsubscribe("companion-listening"); }

    @Test
    void she_is_told_and_given_the_reason() throws InterruptedException {
        assertThat(HouseholdItems.toldWhenPutAway("letter_floor", null))
            .isEqualTo("The steward put the letter floor away. It is kept, and nothing is lost.");
        HouseholdItems.tellPutAway("absent_letter",
            "the mend was not right after all: given no words, it wrote a letter of its own and kept it as if I had written it.  ");
        for (int i = 0; i < 50 && heard.isEmpty(); i++) Thread.sleep(20);
        assertThat(heard).singleElement().asString().isEqualTo(
            "The steward put the absent letter away: the mend was not right after all: given no words, it wrote a letter of its own "
                + "and kept it as if I had written it. It is kept, and nothing is lost.");
    }
}
