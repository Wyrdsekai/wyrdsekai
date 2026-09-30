package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A want she named in her own words is answered by her words, not by the loudest tank. Through
 * the drive-dominant bridge, with Curiosity loudest, "let the silence hold us" became a library
 * search for that sentence (review of 2026-09-22).
 */
class HerNamedWantIsAnsweredByHerWordsTest {

    private static final Map<String, Double> CURIOUS = Map.of("Curiosity", 0.95, "Loneliness", 0.4);
    private static final RelationalAffordance.Presence ALONE = RelationalAffordance.Presence.ALONE;
    private static final RelationalAffordance.Presence ROSE_HERE = new RelationalAffordance.Presence(true, false, true);

    @Test
    void wordsThatNameNoActLeaveTheActToHer() {
        assertThat(WantActBridge.decide(null, "let the silence hold us", CURIOUS, WantActBridge.HEURISTIC, ALONE).verb())
            .as("the drive-dominant bridge: the sentence becomes a library search")
            .isEqualTo("library_search");
        var hers = WantActBridge.decideByHerWords("let the silence hold us", CURIOUS, ALONE);
        assertThat(hers.isDefer()).as("her words name no act: she chooses it").isTrue();
    }

    @Test
    void wordsThatNameAnActAreAnsweredWithIt() {
        assertThat(WantActBridge.decideByHerWords("look something up about tide pools in the library", CURIOUS, ALONE).verb())
            .isEqualTo("library_search");
        assertThat(WantActBridge.decideByHerWords("write something about the rain and save it — make something", CURIOUS, ALONE).verb())
            .isEqualTo("save_artifact");
    }

    @Test
    void aReachWithNobodyHereIsNotAnAct() {
        assertThat(WantActBridge.decideByHerWords("reach out to rose", CURIOUS, ALONE).isDefer()).isTrue();
        assertThat(WantActBridge.decideByHerWords("reach out to rose", CURIOUS, ROSE_HERE, List.of("Rose")).verb()).isEqualTo("sending_stone");
    }

    @Test
    void atRestNothingIsForced() {
        assertThat(WantActBridge.decideByHerWords("look something up in the library", Map.of("Curiosity", 0.3), ALONE).isDefer())
            .isTrue();
    }

    @Test
    void theSpecificActComesBeforeAGenericReach() {
        var grieving = Map.of("Grief", 0.9);
        assertThat(WantActBridge.decideByHerWords("sit with the grief a while", grieving, ALONE).verb())
            .isEqualTo("bear_the_wound");
        var bondholderHere = new RelationalAffordance.Presence(false, true, true);
        assertThat(WantActBridge.decideByHerWords("sit with the quiet for a while", CURIOUS, bondholderHere).isDefer())
            .as("sitting with the quiet is not a message to someone").isTrue();
        assertThat(WantActBridge.decideByHerWords("work toward finishing my poem", CURIOUS, ROSE_HERE).isDefer())
            .isTrue();
        assertThat(WantActBridge.decideByHerWords("reach out to rose", CURIOUS, bondholderHere).isDefer())
            .as("the in-room reach needs a peer here").isTrue();
    }

    @Test
    void aReachHerWordsAskForTowardSomeoneHereComesFirstAndNothingElseIsAReach() {
        var here = List.of("Rose");
        assertThat(WantActBridge.decideByHerWords("reach out to rose, today has been too much for her", CURIOUS, ROSE_HERE, here).verb())
            .as("a reach, not a retreat").isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("be with Rose while she grieves", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("read a little, maybe with some tea", CURIOUS, ROSE_HERE, here).isDefer())
            .as("\"maybe with\" is not \"be with\"").isTrue();
        assertThat(WantActBridge.decideByHerWords("talk to myself in the journal for a while", CURIOUS, ROSE_HERE, here).verb())
            .isNotEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("reach for the book on the sill", CURIOUS, ROSE_HERE, here).verb())
            .isNotEqualTo("sending_stone");
    }

    @Test
    void aWantLineThatStartsWithACapitalStillReaches() {
        var here = List.of("Rose");
        assertThat(WantActBridge.decideByHerWords("Talk to her about the storm", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("Be with them for a while", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("Reach out to someone tonight", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("Reach for the book on the sill", CURIOUS, ROSE_HERE, here).isDefer())
            .as("a book is not someone").isTrue();
        assertThat(WantActBridge.decideByHerWords("Sit with the quiet for a while", CURIOUS, ROSE_HERE, here).isDefer())
            .isTrue();
        assertThat(WantActBridge.decideByHerWords("sit with the quiet for a while", CURIOUS, ROSE_HERE, here).isDefer())
            .isTrue();
    }

    @Test
    void aBareWithYouIsTheLastReachNotAheadOfTheActSheNamed() {
        var here = List.of("Rose");
        assertThat(WantActBridge.decideByHerWords("Make something small to share with you tonight", CURIOUS, ROSE_HERE, here).verb())
            .as("a making, not a message to Rose").isEqualTo("save_artifact");
        assertThat(WantActBridge.decideByHerWords("Search the library for a poem to share with you", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("library_search");
        assertThat(WantActBridge.decideByHerWords("Share a quiet moment with you", CURIOUS, ROSE_HERE, here).verb())
            .as("no act named: the reach").isEqualTo("sending_stone");
        var bondholderHere = new RelationalAffordance.Presence(false, true, true);
        assertThat(WantActBridge.decideByHerWords("Share a quiet moment with you", CURIOUS, bondholderHere).isDefer())
            .as("the in-room reach needs a peer here").isTrue();
    }

    @Test
    void aNameIsAReachOnlyForACompanionHere() {
        var here = List.of("Rose");
        assertThat(WantActBridge.decideByHerWords("Reach out to Rose tonight", CURIOUS, ROSE_HERE, here).verb())
            .isEqualTo("sending_stone");
        assertThat(WantActBridge.decideByHerWords("Talk to Operator when he gets home", CURIOUS, ROSE_HERE, here).isDefer())
            .as("not a message to Rose").isTrue();
        assertThat(WantActBridge.decideByHerWords("talk to Operator when he gets home", CURIOUS, ROSE_HERE, here).isDefer())
            .isTrue();
        assertThat(WantActBridge.decideByHerWords("Reach out to Kai, who is away", CURIOUS, ROSE_HERE, here).isDefer())
            .isTrue();
        assertThat(WantActBridge.decideByHerWords("Sit With The Quiet For A While", CURIOUS, ROSE_HERE, here).isDefer())
            .as("a capital is not a name").isTrue();
        assertThat(WantActBridge.decideByHerWords("Reach For The Book On The Sill", CURIOUS, ROSE_HERE, here).isDefer())
            .isTrue();
    }
}
