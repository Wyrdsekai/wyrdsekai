package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.core.agent.HouseFacts;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.test.TestDb;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room that was closed up and an item that was put away can be found again by what they were, by
 * anyone in the house, and there are two tiers: the archive, and cold storage for what has sat in
 * the archive a long while.
 *
 * <p>The steward, 2026-10-02: "if I go, I want to bring back that nice room with the greenhouse, or
 * something: how do we find it" and "I think we need 2 archives: a fold/archive and a cold storage."
 * A closed-up room had only its name to be found by, and a closed room is not running, so nothing
 * could look into it.
 */
@Tag("integration")
class WhatWasPutAwayCanBeFoundTest {

    private static final long DAY = Duration.ofDays(1).toMillis();

    private String jdbc;
    private MadeThingsStore store;

    @BeforeEach
    void setUp() {
        jdbc = TestDb.createInMemory();
        store = new MadeThingsStore(jdbc);
        MadeThingsStore.install(store);
    }

    @AfterEach
    void tearDown() {
        MadeThingsStore.install(null);
    }

    private void closedRoom(String id, String name, String summary, long closedAt) {
        store.stampRoomMade(id, name, "companion-created", "mia", MadeThingsStore.OWN, 1);
        store.roomClosed(id, closedAt, "nexus\tto-" + id + "\n", summary);
    }

    @Test
    void the_note_written_at_closing_says_what_the_room_was_and_what_was_in_it() {
        var snap = new RoomSnapshot("glass-garden-1", "The Glass Garden",
            "A greenhouse of fogged panes.\n  Ferns lean toward the lamp.", "companion-created", List.of(), List.of(),
            List.of(new RoomObject("o1", "seed tray", "A tray of seeds", false), new RoomObject("o2", "brass watering can", "", true)),
            List.of());
        assertThat(RoomClosing.summaryOf(snap))
            .isEqualTo("A greenhouse of fogged panes. Ferns lean toward the lamp. In it: seed tray, brass watering can.");
        assertThat(RoomClosing.summaryOf(null)).isNull();
    }

    @Test
    void the_small_words_of_a_question_do_not_search() {
        assertThat(MadeArchive.words("that nice room with the greenhouse")).containsExactly("greenhouse");
        assertThat(MadeArchive.words("the room")).isEmpty();
        assertThat(MadeArchive.words("Moss-Lantern, the old one")).containsExactly("moss", "lantern");
    }

    @Test
    void a_closed_up_room_is_found_by_what_it_was_and_an_open_one_is_not_in_the_archive() {
        long now = System.currentTimeMillis();
        closedRoom("glass-garden-1", "The Glass Garden", "A greenhouse of fogged panes. In it: seed tray.", now - 3 * DAY);
        closedRoom("tide-room-2", "The Tide Room", "A room of salt and slow water.", now - 9 * DAY);
        store.stampRoomMade("open-greenhouse-3", "Open Greenhouse", "companion-created", "mia", MadeThingsStore.OWN, 1);

        var found = MadeArchive.rooms(store.rooms(), "that nice room with the greenhouse", false);
        assertThat(found).extracting(MadeArchive.Found::name).containsExactly("The Glass Garden");
        assertThat(MadeArchive.clearly(found).id()).isEqualTo("glass-garden-1");
        assertThat(found.getFirst().what()).contains("greenhouse");

        assertThat(MadeArchive.rooms(store.rooms(), "", false)).as("no words: the whole archive, newest closed first")
            .extracting(MadeArchive.Found::name).containsExactly("The Glass Garden", "The Tide Room");
        assertThat(MadeArchive.rooms(store.rooms(), "lighthouse", false)).isEmpty();
    }

    @Test
    void a_companion_going_by_what_it_was_needs_every_word_to_fit() {
        long now = System.currentTimeMillis();
        closedRoom("glass-garden-1", "The Glass Garden", "A quiet greenhouse of fogged panes.", now - 3 * DAY);
        closedRoom("tide-room-2", "The Tide Room", "A quiet room of salt and slow water.", now - 9 * DAY);
        var loose = MadeArchive.rooms(store.rooms(), "quiet harbour", false);
        assertThat(loose).as("a person's search shows anything a word finds").hasSize(2);
        assertThat(MadeArchive.holdingEveryWord(loose, "quiet harbour")).as("one shared word opens nothing for her").isEmpty();
        assertThat(MadeArchive.holdingEveryWord(MadeArchive.rooms(store.rooms(), "quiet greenhouse", false), "quiet greenhouse"))
            .extracting(MadeArchive.Found::id).containsExactly("glass-garden-1");
        assertThat(MadeArchive.clearly(MadeArchive.rooms(store.rooms(), "quiet", false)))
            .as("two that fit equally are named back, not guessed between").isNull();
    }

    @Test
    void a_room_long_in_the_archive_goes_to_cold_storage_and_comes_out_when_it_is_opened() {
        long now = System.currentTimeMillis();
        closedRoom("glass-garden-1", "The Glass Garden", "A greenhouse of fogged panes.", now - 100 * DAY);
        closedRoom("tide-room-2", "The Tide Room", "A room of salt and slow water.", now - 9 * DAY);
        var closing = new RoomClosing(store, Set.of("nexus"), cmd -> { });

        assertThat(closing.coldPass(0)).as("0 days: nothing ever goes cold").isZero();
        assertThat(closing.coldPass(90)).isEqualTo(1);
        assertThat(closing.coldPass(90)).as("once").isZero();
        var garden = store.room("glass-garden-1").orElseThrow();
        assertThat(garden.cold()).isTrue();
        assertThat(garden.closed()).as("cold is still kept, still closed").isTrue();
        assertThat(store.room("tide-room-2").orElseThrow().cold()).isFalse();

        assertThat(closing.archive("greenhouse", false)).as("not in the everyday archive").isEmpty();
        assertThat(closing.archive("greenhouse", true)).extracting(MadeArchive.Found::name).containsExactly("The Glass Garden");
        assertThat(closing.findClosed("The Glass Garden")).as("a cold room still answers to its name").isPresent();
        var listing = ArchiveCommands.find("", false);
        assertThat(listing.rooms()).extracting(MadeArchive.Found::name).containsExactly("The Tide Room");
        assertThat(listing.coldRooms()).isEqualTo(1);

        store.roomOpened("glass-garden-1", now);
        var open = store.room("glass-garden-1").orElseThrow();
        assertThat(open.closed()).isFalse();
        assertThat(open.cold()).isFalse();
        store.roomClosed("glass-garden-1", now, "", "A greenhouse of fogged panes.");
        assertThat(store.room("glass-garden-1").orElseThrow().cold()).as("closed again, it starts in the archive").isFalse();
    }

    @Test
    void a_put_away_item_keeps_its_name_and_what_it_does_and_has_the_same_two_tiers() {
        long now = System.currentTimeMillis();
        store.itemMade("mia/moss_lantern", "crafted", "mia", MadeThingsStore.OWN, "Moss Lantern", "A lantern that glows when the garden is watered.");
        store.itemMade("mia/tide_clock", "crafted", "mia", MadeThingsStore.OWN, "Tide Clock", "Tells the hour by the sea.");
        store.enrollItem("mia/old_thing", "crafted", "mia");
        store.enrollItem("mia/old_thing", "crafted", "mia", "Old Thing", "Made before names were kept.");
        assertThat(store.items()).filteredOn(i -> i.itemId().equals("mia/old_thing")).first()
            .satisfies(i -> assertThat(i.shownName()).as("a name missing from the record is filled in").isEqualTo("Old Thing"));

        assertThat(MadeArchive.items(store.items(), "garden", false)).as("nothing is put away yet").isEmpty();
        store.itemPutAway("mia/moss_lantern", now - 100 * DAY);
        store.itemPutAway("mia/tide_clock", now - 2 * DAY);
        assertThat(MadeArchive.items(store.items(), "the one for the garden", false))
            .extracting(MadeArchive.Found::name).containsExactly("Moss Lantern");

        var lantern = store.items().stream().filter(i -> i.itemId().equals("mia/moss_lantern")).findFirst().orElseThrow();
        assertThat(MadeArchive.dueForCold(lantern.putAwayAt(), lantern.coldAt(), 90, now)).isTrue();
        assertThat(MadeArchive.dueForCold(lantern.putAwayAt(), lantern.coldAt(), 0, now)).isFalse();
        store.itemCold("mia/moss_lantern", now);
        assertThat(MadeArchive.items(store.items(), "garden", false)).isEmpty();
        assertThat(MadeArchive.items(store.items(), "garden", true)).hasSize(1);

        var brought = ArchiveCommands.bringBack("Moss Lantern", "en", true, true);
        assertThat(brought.ok()).as("cold storage answers to the name").isTrue();
        assertThat(brought.kind()).isEqualTo("item");
        assertThat(brought.message()).startsWith("Moss Lantern is out again.");
        var back = store.items().stream().filter(i -> i.itemId().equals("mia/moss_lantern")).findFirst().orElseThrow();
        assertThat(back.putAway()).isFalse();
        assertThat(back.cold()).isFalse();

        var byWhat = ArchiveCommands.bringBack("the clock that tells the hour by the sea", "en", true, true);
        assertThat(byWhat.ok()).as("the archive answers to what a thing was").isTrue();
        assertThat(byWhat.name()).isEqualTo("Tide Clock");
        assertThat(ArchiveCommands.bringBack("lighthouse", "en", true, true).ok()).isFalse();
    }

    @Test
    void a_person_in_the_house_is_shown_the_archive_and_how_to_bring_a_thing_back() {
        long now = System.currentTimeMillis();
        closedRoom("glass-garden-1", "The Glass Garden", "A greenhouse of fogged panes.", now - 3 * DAY);
        closedRoom("deep-cellar-9", "The Deep Cellar", "Cold stone.", now - 200 * DAY);
        store.roomCold("deep-cellar-9", now);
        store.itemMade("mia/tide_clock", "crafted", "mia", MadeThingsStore.OWN, "Tide Clock", "Tells the hour by the sea.");
        store.itemPutAway("mia/tide_clock", now - 2 * DAY);

        var shown = ArchiveCommands.command("did:key:z6MkExamplePerson", "archive", "en", false);
        assertThat(shown).contains("Closed-up rooms:").contains("The Glass Garden — made by mia, closed up 3 days ago — A greenhouse")
            .contains("Put-away items:").contains("Tide Clock").contains("Bring one back with: reopen <name>")
            .contains("1 more in cold storage. See them with: archive cold").doesNotContain("Deep Cellar");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "archive greenhouse", "en", false))
            .contains("The Glass Garden").doesNotContain("Tide Clock");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "archive cold", "en", false))
            .contains("Rooms in cold storage:").contains("The Deep Cellar");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "archive lighthouse", "en", false))
            .startsWith("Nothing closed up or put away fits \"lighthouse\".");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "reopen", "en", false)).startsWith("reopen <name>");
        assertThat(ArchiveCommands.command(null, "archive", "en", false)).isEqualTo("Sign in first to look through the archive.");
        assertThat(ArchiveCommands.command("visitor-1", "archive", "en", true)).as("a visitor from another household")
            .isEqualTo("The archive belongs to this household.");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "archive", "es", false)).contains("Salas cerradas:");
        assertThat(ArchiveCommands.command("did:key:z6MkExamplePerson", "archive", "ja", false)).contains("閉じた部屋:");
    }

    @Test
    void the_words_are_commands_on_every_surface_and_nothing_else_is() {
        assertThat(CommandParser.parse("archive", "en", Map.of())).isInstanceOf(ParsedCommand.Archive.class);
        assertThat(CommandParser.parse("archive cold greenhouse", "en", Map.of())).isEqualTo(new ParsedCommand.Archive("archive cold greenhouse"));
        assertThat(CommandParser.parse("reopen the glass garden", "en", Map.of())).isEqualTo(new ParsedCommand.Archive("reopen the glass garden"));
        assertThat(ArchiveCommands.isCommand("archive greenhouse")).isTrue();
        assertThat(ArchiveCommands.isCommand("Reopen The Glass Garden")).isTrue();
        assertThat(ArchiveCommands.isCommand("archives are dusty")).isFalse();
        assertThat(ArchiveCommands.isCommand("I want to reopen the garden")).isFalse();
    }

    @Test
    void every_line_a_person_is_shown_exists_in_all_three_languages() {
        for (var key : List.of("archive.sign_in", "archive.visitor", "archive.unavailable", "archive.usage", "archive.empty",
                "archive.cold.empty", "archive.none_fit", "archive.cold.none_fit", "archive.head.rooms", "archive.head.items",
                "archive.head.cold.rooms", "archive.head.cold.items", "archive.row.room", "archive.row.item", "archive.more",
                "archive.how", "archive.cold.hint", "archive.reopen.usage", "archive.reopen.room", "archive.reopen.way",
                "archive.reopen.item", "archive.reopen.several", "archive.reopen.none", "archive.reopen.failed")) {
            for (var lang : List.of("en", "es", "ja")) {
                assertThat(ScriptMessageCatalog.forLang(lang).get(key)).as(lang + " " + key).isNotEqualTo(key);
            }
        }
        assertThat(ScriptMessageCatalog.forLang("es").get("archive.usage")).isNotEqualTo(ScriptMessageCatalog.english().get("archive.usage"));
    }

    @Test
    void a_house_on_the_older_record_gains_the_archive_columns() throws Exception {
        assertThat(SchemaInitializer.SCHEMA_VERSION).isGreaterThanOrEqualTo(21);
        try (var conn = DriverManager.getConnection(jdbc)) {
            MadeThingsStore.ensureArchive(conn);
            MadeThingsStore.ensureArchive(conn);
        }
        closedRoom("glass-garden-1", "The Glass Garden", "A greenhouse.", 5_000L);
        assertThat(store.room("glass-garden-1").orElseThrow().closedSummary()).isEqualTo("A greenhouse.");
        var rec = store.room("glass-garden-1").orElseThrow();
        assertThat(ArchiveCommands.waysIn(rec)).as("where to walk to find it again").containsExactly("nexus");
    }

    @Test
    void she_is_told_in_her_facts_what_of_hers_is_kept_so_she_does_not_say_she_has_forgotten_it() {
        long now = System.currentTimeMillis();
        assertThat(HouseFacts.keptLine(0, 0)).isNull();
        assertThat(HouseFacts.keptLine(1, 0))
            .isEqualTo("Kept for you, not lost: 1 room you made is closed up. Going to a room by its name, or by what it was, opens it again.");
        assertThat(HouseFacts.keptLine(35, 4))
            .isEqualTo("Kept for you, not lost: 35 rooms you made are closed up and 4 items you made are put away."
                + " Going to a room by its name, or by what it was, opens it again. Naming an item takes it out again.");

        closedRoom("glass-garden-1", "The Glass Garden", "A greenhouse.", now - 3 * DAY);
        closedRoom("deep-cellar-9", "The Deep Cellar", "Cold stone.", now - 200 * DAY);
        store.roomCold("deep-cellar-9", now);
        store.stampRoomMade("rose-room-1", "Rose Room", "companion-created", "rose", MadeThingsStore.OWN, 1);
        store.roomClosed("rose-room-1", now, "", "Another companion's room.");
        store.itemMade("companion-mia/tide_clock", "crafted", "companion-mia", MadeThingsStore.OWN, "Tide Clock", "Tells the hour.");
        store.itemPutAway("companion-mia/tide_clock", now - DAY);
        try {
            HouseFacts.set("companion-mia", "THE FACTS OF YOUR HOUSE, from your own record:\n- You are mia.");
            ArchiveCommands.refreshKept("companion-mia", "mia");
            assertThat(HouseFacts.blockFor("companion-mia"))
                .as("hers only, and cold storage is out of her everyday sight")
                .endsWith("- You are mia.\nKept for you, not lost: 1 room you made is closed up and 1 item you made is put away."
                    + " Going to a room by its name, or by what it was, opens it again. Naming an item takes it out again.");
            store.roomOpened("glass-garden-1", now);
            store.itemTakenOut("companion-mia/tide_clock", now);
            ArchiveCommands.refreshKept("companion-mia", "mia");
            assertThat(HouseFacts.blockFor("companion-mia")).as("nothing of hers is away: no line")
                .isEqualTo("THE FACTS OF YOUR HOUSE, from your own record:\n- You are mia.");
        } finally {
            HouseFacts.set("companion-mia", null);
            HouseFacts.setKept("companion-mia", null);
        }
    }
}
