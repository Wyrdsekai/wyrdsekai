package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.room.RoomClosing;
import org.wyrdsekai.core.test.TestDb;

import java.sql.DriverManager;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record a made room and a made item now carry: who it was made for, when anyone was last in it,
 * whether a person has been, whether it is closed up or put away. A made room had a maker's name and a
 * date and nothing else (household node, 2026-10-02).
 */
@Tag("integration")
class WhatTheHouseKeepsAboutMadeThingsTest {

    private String jdbc;
    private MadeThingsStore store;
    private RoomMetadataService metadata;

    @BeforeEach
    void setUp() {
        jdbc = TestDb.createInMemory();
        store = new MadeThingsStore(jdbc);
        metadata = new RoomMetadataService(jdbc);
    }

    @Test
    void a_room_made_before_the_upgrade_starts_its_quiet_days_at_the_upgrade() throws Exception {
        metadata.register("old-room-1", "Old Room", "companion-created", "mia");
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("UPDATE rooms SET last_entered_at = NULL, created_at = 1 WHERE room_id = 'old-room-1'");
            MadeThingsStore.ensure(conn);
        }
        var r = store.room("old-room-1").orElseThrow();
        assertThat(r.madeFor()).as("nobody recorded who asked").isNull();
        assertThat(r.lastEnteredAt()).isNotNull();
        assertThat(System.currentTimeMillis() - r.lastEnteredAt()).isLessThan(60_000);
        assertThat(r.closed()).isFalse();
    }

    @Test
    void who_it_was_made_for_is_kept_whichever_of_the_two_writes_lands_first() {
        // The stamp before the room's own registration...
        store.stampRoomMade("nook-1", "Dusk Reading Nook", "companion-created", "mia", MadeThingsStore.OWN, 3);
        metadata.register("nook-1", "Dusk Reading Nook", "companion-created", "mia");
        // ...and after it.
        metadata.register("shelf-2", "A Shelf For Him", "companion-created", "mia");
        store.stampRoomMade("shelf-2", "A Shelf For Him", "companion-created", "mia", "did:key:z6MkExamplePerson", 2);
        var own = store.room("nook-1").orElseThrow();
        assertThat(own.madeFor()).isEqualTo(MadeThingsStore.OWN);
        assertThat(own.askedFor()).isFalse();
        assertThat(own.baselineObjects()).isEqualTo(3);
        assertThat(own.createdBy()).isEqualTo("mia");
        var asked = store.room("shelf-2").orElseThrow();
        assertThat(asked.askedFor()).isTrue();
        assertThat(metadata.listRooms()).hasSize(2);
    }

    @Test
    void a_persons_entry_is_kept_apart_from_anyones() {
        store.stampRoomMade("nook-1", "Dusk Reading Nook", "companion-created", "mia", MadeThingsStore.OWN, 3);
        store.roomEntered("nook-1", 1_000L, false);
        assertThat(store.room("nook-1").orElseThrow().aPersonHasBeenIn()).isFalse();
        assertThat(store.room("nook-1").orElseThrow().lastEnteredAt()).isEqualTo(1_000L);
        store.roomEntered("nook-1", 2_000L, true);
        var r = store.room("nook-1").orElseThrow();
        assertThat(r.aPersonHasBeenIn()).isTrue();
        assertThat(r.lastPersonAt()).isEqualTo(2_000L);
        store.roomEntered("nook-1", 3_000L, false);
        assertThat(store.room("nook-1").orElseThrow().lastPersonAt()).as("a companion's later entry does not erase it").isEqualTo(2_000L);
    }

    @Test
    void a_closed_up_room_keeps_its_row_and_its_doorways_and_is_found_by_name() {
        store.stampRoomMade("the-listening-room-4821", "The Listening Room", "companion-created", "mia", MadeThingsStore.OWN, 2);
        assertThat(new RoomClosing(store, Set.of("nexus"), cmd -> { }).findOpen("The Listening Room"))
            .as("an open room is found by its name, to be closed by name").isPresent();
        store.roomClosed("the-listening-room-4821", 5_000L, "nexus\tto-the-listening-room-4821\n");
        assertThat(new RoomClosing(store, Set.of("nexus"), cmd -> { }).findOpen("The Listening Room")).isEmpty();
        assertThat(store.closedRoomIds()).containsExactly("the-listening-room-4821");
        var closing = new RoomClosing(store, Set.of("nexus"), cmd -> { });
        assertThat(closing.findClosed("The Listening Room")).isPresent();
        assertThat(closing.findClosed("listening room")).isPresent();
        assertThat(closing.findClosed("the-listening-room-4821")).isPresent();
        assertThat(closing.findClosed("the-listening-room")).isPresent();
        assertThat(closing.findClosed("to-the-listening-room-4821")).as("by its doorway's direction").isPresent();
        assertThat(closing.findClosed("A path to The Listening Room")).as("by its doorway's label").isPresent();
        assertThat(closing.findClosed("The Quiet Room")).isEmpty();
        assertThat(store.room("the-listening-room-4821").orElseThrow().closedExits()).contains("nexus\tto-the-listening-room-4821");
        store.roomOpened("the-listening-room-4821", 9_000L);
        var open = store.room("the-listening-room-4821").orElseThrow();
        assertThat(open.closed()).isFalse();
        assertThat(open.lastEnteredAt()).as("opening it counts as someone having been in it").isEqualTo(9_000L);
        assertThat(store.closedRoomIds()).isEmpty();
        assertThat(closing.findClosed("The Listening Room")).isEmpty();
    }

    @Test
    void a_room_the_steward_keeps_is_held_from_then_on() {
        store.stampRoomMade("the-room-you-asked-me-to-build-7", "the room you asked me to build", "companion-created", "mia",
            MadeThingsStore.OWN, 2);
        assertThat(store.room("the-room-you-asked-me-to-build-7").orElseThrow().askedFor()).isFalse();
        assertThat(store.keepRoom("the-room-you-asked-me-to-build-7", "did:key:z6MkExamplePerson")).isTrue();
        var kept = store.room("the-room-you-asked-me-to-build-7").orElseThrow();
        assertThat(kept.askedFor()).isTrue();
        assertThat(org.wyrdsekai.core.room.RoomKeeping.heldBecause(kept, Set.of(), Set.of("mia"))).isEqualTo("a person asked for it");
        assertThat(store.keepRoom("no-such-room", "x")).isFalse();
    }

    @Test
    void she_is_told_in_one_sentence_what_was_closed_and_how_to_get_it_back() {
        var told = RoomClosing.toldLine(new RoomClosing.Result(true, "", "nook-1", "Dusk Reading Nook", 9));
        assertThat(told).isEqualTo("Dusk Reading Nook was closed up: nobody had been in it for 9 days. "
            + "It is kept as it was. Go to it by name, or by what it was, and it opens again.");
    }

    @Test
    void a_made_item_is_recorded_used_put_away_and_taken_out() {
        store.itemMade("memory_chest", "crafted", "mia", MadeThingsStore.OWN);
        store.itemMade("pinboard", "scripted", "mia", "did:key:z6MkExamplePerson");
        store.itemUsed("memory_chest", 4_000L, false);
        store.itemUsed("never_recorded", 4_000L, true);
        var items = store.items();
        assertThat(items).hasSize(2);
        var chest = items.stream().filter(i -> i.itemId().equals("memory_chest")).findFirst().orElseThrow();
        assertThat(chest.lastUsedAt()).isEqualTo(4_000L);
        assertThat(chest.aPersonHasUsed()).isFalse();
        assertThat(items.stream().filter(i -> i.itemId().equals("pinboard")).findFirst().orElseThrow().askedFor()).isTrue();
        store.itemPutAway("memory_chest", 6_000L);
        assertThat(store.items().stream().filter(i -> i.itemId().equals("memory_chest")).findFirst().orElseThrow().putAway()).isTrue();
        store.itemTakenOut("memory_chest", 7_000L);
        var out = store.items().stream().filter(i -> i.itemId().equals("memory_chest")).findFirst().orElseThrow();
        assertThat(out.putAway()).isFalse();
        assertThat(out.lastUsedAt()).isEqualTo(7_000L);
    }
}
