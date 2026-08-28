package org.wyrdsekai.core.room;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomObject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds evolve between releases; an existing world must CONVERGE on new
 * foundation furnishings at boot. Before this arc, re-seeding an existing
 * room was a pure no-op — the grant stone shipped in foundation-rooms.json
 * never appeared in any ward room built before it (found live 2026-08-14).
 *
 * <p>The backfill is deliberately narrow, and this test pins BOTH edges:
 * a new non-takeable fixture is added; a takeable seed object is NOT —
 * it may legitimately live in someone's inventory by now, and re-adding
 * would mint a duplicate. Existing objects are never overwritten.</p>
 */
@Tag("integration")
class RoomActorSeedBackfillTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.room.RoomEvent" = jackson-json
              "org.wyrdsekai.core.room.RoomState" = jackson-json
              "org.wyrdsekai.core.room.RoomCommand" = jackson-json
              "org.wyrdsekai.core.room.RoomNotification" = jackson-json
              "org.wyrdsekai.core.room.RoomResponse" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    private EventSourcedBehaviorTestKit<RoomCommand, RoomEvent, RoomState> kit;

    @BeforeEach
    void setUp() {
        kit = EventSourcedBehaviorTestKit.create(
            testKit.system(),
            RoomActor.create("seed-backfill-test", null, null, null, null));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void reseed_backfills_new_fixture_but_never_takeables() {
        var logbook = new RoomObject("ward-logbook", "logbook", "Terse assessments.", false);

        // The world as originally built: one fixture.
        var first = kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "The Ward Room", "Quiet and fortified.", "foundation",
            List.of(), List.of(logbook), ref));
        assertThat(first.reply()).isInstanceOf(RoomResponse.Ok.class);

        // A later release's seed: same fixture, plus a NEW fixture and a NEW
        // takeable. Reseed happens at every boot.
        var stone = new RoomObject("hermod-grant-stone", "grant stone",
            "A palm-sized stone that remembers what the household has granted.", false);
        var trinket = new RoomObject("seed-trinket", "trinket", "Pocketable.", true);
        var second = kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "The Ward Room", "Quiet and fortified.", "foundation",
            List.of(), List.of(logbook, stone, trinket), ref));

        var snapshot = ((RoomResponse.Ok) second.reply()).snapshot();
        var ids = snapshot.objects().stream().map(RoomObject::id).toList();
        assertThat(ids)
            .as("new non-takeable fixture must be backfilled into the existing room")
            .contains("hermod-grant-stone")
            .as("existing fixture must survive untouched")
            .contains("ward-logbook")
            .as("takeable seed objects must never be backfilled — duplicate risk")
            .doesNotContain("seed-trinket");

        // Third reseed with the same seed set is a clean no-op (idempotent).
        var third = kit.<RoomResponse>runCommand(ref -> new RoomCommand.CreateRoom(
            "The Ward Room", "Quiet and fortified.", "foundation",
            List.of(), List.of(logbook, stone, trinket), ref));
        assertThat(((RoomResponse.Ok) third.reply()).snapshot().objects())
            .hasSameSizeAs(snapshot.objects());
    }
}
