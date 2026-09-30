package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool is handed what the person typed, not the actor's envelope around it. The reply
 * instruction in that envelope reached the library card's query, and a small summariser
 * answered the instruction ({"action": "…) instead of the question.
 */
class AToolIsHandedThePersonsWordsTest {

    private static final String ENVELOPE =
        "[message from anonymous: find me a book about mythology in the library]\n"
        + "[When done, REPLY using: {\"action\": \"tell_agent\", \"target\": \"anonymous\", \"message\": \"<your findings>\"}]";

    @Test
    void theReplyInstructionIsNotPartOfTheRequest() {
        var said = CompanionActor.personsWords(ENVELOPE);
        assertThat(said).isEqualTo("find me a book about mythology in the library");
        assertThat(said).doesNotContain("tell_agent").doesNotContain("REPLY").doesNotContain("[");
    }

    @Test
    void aPlainLineIsLeftAsItIs() {
        assertThat(CompanionActor.personsWords("what is the capital of France?")).isEqualTo("what is the capital of France?");
        assertThat(CompanionActor.personsWords("[from sam] a note: keep the colon")).contains("keep the colon");
        assertThat(CompanionActor.personsWords(null)).isEmpty();
    }

    @Test
    void anInstructionLineTheStrippersDoNotKnowIsStillCut() {
        assertThat(CompanionActor.personsWords("look up lighthouses [When done, do something else]")).isEqualTo("look up lighthouses");
    }
}
