package org.wyrdsekai.server.http;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rooms made because a person asked never close on their own, so they have to stay visible to the
 * steward with their age: clutter of that kind is the person's call (operator, 2026-10-02).
 */
class TheStewardSeesWhatWasMadeAndForWhomTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long NOW = 1_790_000_000_000L;

    private static RoomRecord room(String id, String by, String madeFor, long quietDays, Long personAt, Long closedAt) {
        return new RoomRecord(id, id, "companion-created", by, NOW - 60 * DAY, madeFor, NOW - quietDays * DAY, personAt, closedAt, null, 2);
    }

    @Test
    void the_list_shows_who_each_room_was_for_how_quiet_it_is_and_why_it_is_held() {
        var rows = RoomAdminRoutes.madeRows(List.of(
            room("own-quiet", "mia", MadeThingsStore.OWN, 5, null, null),
            room("asked-for", "mia", "did:key:z6MkExamplePerson", 44, null, null),
            room("been-in", "rose", MadeThingsStore.OWN, 12, NOW - 10 * DAY, null),
            room("closed-up", "rose", MadeThingsStore.OWN, 20, null, NOW - 3 * DAY),
            room("before-upgrade", "mia", null, 1, null, null),
            room("nexus", "system", null, 0, null, null),
            room("home-companion-mia", "system", null, 0, null, null),
            room("study-1f56", "system", null, 0, null, null),
            room("workshop-codezaiku-1f56", "system", null, 0, null, null)),
            Set.of("nexus"), Set.of("mia", "rose"), NOW);
        assertThat(rows).extracting(RoomAdminRoutes.MadeRow::roomId)
            .containsExactly("own-quiet", "asked-for", "been-in", "closed-up", "before-upgrade");
        assertThat(rows.get(4).madeFor()).isEqualTo("not recorded");
        assertThat(rows.get(4).held()).as("what the record cannot tell is held, and shown").isEqualTo("nobody recorded who it was made for");
        var asked = rows.get(1);
        assertThat(asked.madeFor()).isEqualTo("a person");
        assertThat(asked.held()).isEqualTo("a person asked for it");
        assertThat(asked.quietDays()).isEqualTo(44);
        assertThat(rows.get(0).madeFor()).isEqualTo("her own");
        assertThat(rows.get(0).held()).isEmpty();
        assertThat(rows.get(2).held()).isEqualTo("a person has been in it");
        assertThat(rows.get(3).closed()).isTrue();
        assertThat(rows.get(3).closedDays()).isEqualTo(3);
    }

    @Test
    void the_items_list_shows_the_same_for_what_she_crafted() {
        var rows = RoomAdminRoutes.madeItemRows(List.of(
            new MadeThingsStore.ItemRecord("companion-mia/memory_chest", "crafted", "companion-mia", MadeThingsStore.OWN,
                NOW - 60 * DAY, NOW - 45 * DAY, null, NOW - DAY),
            new MadeThingsStore.ItemRecord("companion-mia/simple_book", "crafted", "companion-mia", "did:key:z6MkExamplePerson",
                NOW - 60 * DAY, NOW - 50 * DAY, null, null)), NOW);
        assertThat(rows.get(0).item()).isEqualTo("memory_chest");
        assertThat(rows.get(0).putAway()).isTrue();
        assertThat(rows.get(0).unusedDays()).isEqualTo(45);
        assertThat(rows.get(1).madeFor()).isEqualTo("a person");
        assertThat(rows.get(1).held()).isEqualTo("a person asked for it");
    }

    @Test
    void the_search_rows_say_what_a_thing_was_and_how_long_it_has_been_away_and_cold_shows_in_the_lists() {
        var closed = new RoomRecord("glass-garden-1", "The Glass Garden", "companion-created", "mia", NOW - 60 * DAY,
            MadeThingsStore.OWN, NOW - 30 * DAY, null, NOW - 12 * DAY, "nexus\tto-glass-garden-1\n", 2,
            "A greenhouse of fogged panes. In it: seed tray.", null);
        var cold = new RoomRecord("deep-cellar-9", "The Deep Cellar", "companion-created", "mia", NOW - 300 * DAY,
            MadeThingsStore.OWN, NOW - 250 * DAY, null, NOW - 200 * DAY, "", 2, "Cold stone.", NOW - 100 * DAY);
        var found = org.wyrdsekai.core.room.MadeArchive.rooms(List.of(closed, cold), "the room with the greenhouse", false);
        var rows = RoomAdminRoutes.archiveRows(found, NOW);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().name()).isEqualTo("The Glass Garden");
        assertThat(rows.getFirst().days()).isEqualTo(12);
        assertThat(rows.getFirst().what()).startsWith("A greenhouse of fogged panes.");
        assertThat(RoomAdminRoutes.archiveRows(org.wyrdsekai.core.room.MadeArchive.rooms(List.of(closed, cold), "", true), NOW))
            .extracting(RoomAdminRoutes.ArchiveRow::name).containsExactly("The Deep Cellar");
        var made = RoomAdminRoutes.madeRows(List.of(closed, cold), Set.of("nexus"), Set.of("mia"), NOW);
        assertThat(made).extracting(RoomAdminRoutes.MadeRow::cold).containsExactly(false, true);
    }
}
