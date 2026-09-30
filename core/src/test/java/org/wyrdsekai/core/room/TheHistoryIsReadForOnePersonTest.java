package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.memory.MemoryOrigin;
import org.wyrdsekai.core.memory.MemoryReader;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit W4 (2026-09-28): the conversation history in every prompt was her unfiltered recent
 * events, other people's tells included. Each line keeps its origin through all three tiers.
 */
class TheHistoryIsReadForOnePersonTest {

    private static final String ALICE = "did:key:alice";
    private static final String BOB = "did:key:bob";

    private static WorldEvent.Said line(String who, String text) {
        return new WorldEvent.Said("hearth", Instant.now(), who, who, text);
    }

    @Test
    @DisplayName("Bob's turn never reads Alice's tell, in the hot lines or the older summaries")
    void aliceTellStaysHers() {
        var policy = new RoomMemoryPolicy(2, 3, 3);
        policy.add(line(ALICE, "[message from Alice: I am pregnant]"), MemoryOrigin.privateTo(ALICE));
        policy.add(line(BOB, "nice weather"), MemoryOrigin.openFrom(BOB));
        policy.add(line(ALICE, "[message from Alice: please keep it quiet]"), MemoryOrigin.privateTo(ALICE));
        policy.add(line(BOB, "the gate sticks"), MemoryOrigin.openFrom(BOB));

        var bob = MemoryReader.of(BOB, true);
        assertThat(policy.hotEvents(bob)).extracting(WorldEvent.Said::text).containsExactly("the gate sticks");
        assertThat(policy.buildMemoryContext(bob)).contains("nice weather").doesNotContain("pregnant");

        var alice = MemoryReader.of(ALICE, false);
        assertThat(policy.buildMemoryContext(alice)).contains("pregnant");
        assertThat(policy.hotEvents(alice)).extracting(WorldEvent.Said::text)
            .containsExactly("[message from Alice: please keep it quiet]", "the gate sticks");
    }

    @Test
    @DisplayName("a spike never merges two people's words into one entry")
    void spikeKeepsOriginsApart() {
        var policy = new RoomMemoryPolicy(1, 20, 5);
        for (int i = 0; i < 6; i++) {
            var who = i % 2 == 0 ? ALICE : BOB;
            policy.add(line(who, "line " + i + " from " + who), MemoryOrigin.privateTo(who));
        }
        policy.handleSpike();
        var bob = policy.buildMemoryContext(MemoryReader.of(BOB, false));
        assertThat(bob).doesNotContain(ALICE + ":");
    }

    @Test
    @DisplayName("the origin recorded for a line is found again for that line")
    void originOfALine() {
        var policy = new RoomMemoryPolicy(5, 5, 5);
        var said = line(ALICE, "hello");
        policy.add(said, MemoryOrigin.privateTo(ALICE));
        assertThat(policy.originOf(said)).isEqualTo(MemoryOrigin.privateTo(ALICE));
        assertThat(policy.originOf(line(ALICE, "hello"))).as("by instance, not by value").isNull();
    }
}
