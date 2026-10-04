package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * operator, 2026-10-02: "wait so what happens to those rooms? u cant tell if they were asked for or
 * not." The first build treated every room made before the record as her own, which would have closed
 * up rooms a person asked for. The record could tell: on the household node the trail said 48 of 50
 * were made on her own time and 2 while talking with her person. The history is read once from the
 * trail and the rooms' journals; what it cannot tell is held.
 */
@Tag("integration")
class TheHouseReadsItsOwnHistoryOnceTest {

    private static final Instant MADE = Instant.parse("2026-09-30T11:32:00Z");
    private static final String PERSON = "did:key:z6MkExamplePersonOfTheHouse";
    private String jdbc;
    private MadeThingsStore store;
    @TempDir Path dir;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = TestDb.createInMemory();
        store = new MadeThingsStore(jdbc);
        var metadata = new RoomMetadataService(jdbc);
        metadata.register("the-erosion-garden-1", "the-erosion-garden", "companion-created", "mia");
        metadata.register("crystalline-hum-room-2", "crystalline-hum-room", "companion-created", "mia");
        metadata.register("no-trace-3", "No Trace", "companion-created", "rose");
        metadata.register("the-forge-of-quiet-things-4", "The Forge of Quiet Things", "companion-created", "system");
        metadata.register("nexus", "The Nexus", "foundation", "system");
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("UPDATE rooms SET created_at = " + MADE.getEpochSecond() + ", last_entered_at = NULL");
        }
    }

    private Path trail(String... lines) throws Exception {
        var f = dir.resolve("agent-activity.jsonl");
        Files.write(f, List.of(lines));
        return f;
    }

    private static String at(long secondsFromMaking) {
        return MADE.plusSeconds(secondsFromMaking).toString();
    }

    private void entered(String room, String entity, String type, Instant when, int seq) throws Exception {
        try (var conn = DriverManager.getConnection(jdbc); var ps = conn.prepareStatement(
                "INSERT INTO event_journal (persistence_id, sequence_number, writer, write_timestamp, adapter_manifest, "
                + "event_payload, event_ser_id, event_ser_manifest) VALUES (?, ?, 'w', ?, '', ?, 1, 'org.wyrdsekai.core.room.RoomEvent')")) {
            ps.setString(1, "Room|" + room);
            ps.setInt(2, seq);
            ps.setLong(3, when.toEpochMilli());
            ps.setBytes(4, ("{\"event\":{\"type\":\"entity_entered\",\"roomId\":\"" + room + "\",\"timestamp\":\"" + when
                + "\",\"entityId\":\"" + entity + "\",\"entityName\":\"x\",\"entityType\":\"" + type + "\"}}").getBytes());
            ps.executeUpdate();
        }
    }

    @Test
    void the_trail_says_who_each_older_room_was_made_for_and_what_it_cannot_say_is_held() throws Exception {
        var trail = trail(
            // Her own time: a tick of her own time two minutes before the making.
            "{\"ts\":\"" + at(-120) + "\",\"type\":\"tick\",\"agent\":\"mia\",\"chosenWant\":\"w1\"}",
            "{\"ts\":\"" + at(-5) + "\",\"type\":\"speak\",\"agent\":\"mia\",\"room\":\"nexus\",\"text\":\"Creating the-erosion-garden from the garden template, connected to nexus.\"}",
            // An older room, made before the maker's name was kept: rose's line says she made it.
            "{\"ts\":\"" + at(-200) + "\",\"type\":\"autonomy\",\"agent\":\"rose\"}",
            "{\"ts\":\"" + at(20) + "\",\"type\":\"speak\",\"agent\":\"rose\",\"room\":\"nexus\",\"text\":\"I made The Forge of Quiet Things, with a way back home.\"}",
            "not json at all");
        // crystalline-hum-room is made an hour later while talking with her person: her line a minute
        // before it is addressed to them, and there is no tick of her own time.
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("UPDATE rooms SET created_at = " + (MADE.getEpochSecond() + 3600) + " WHERE room_id = 'crystalline-hum-room-2'");
            st.execute("UPDATE rooms SET created_at = " + (MADE.getEpochSecond() + 7200) + " WHERE room_id = 'no-trace-3'");
        }
        Files.writeString(trail, "{\"ts\":\"" + MADE.plusSeconds(3600 - 60) + "\",\"type\":\"speak\",\"agent\":\"mia\",\"room\":\"nexus\",\"to\":\"operator\",\"toId\":\""
            + PERSON + "\",\"text\":\"The room is waiting. Let me show you both what I made.\"}\n", java.nio.file.StandardOpenOption.APPEND);

        var out = MadeThingsHistory.runOnce(jdbc, trail, Set.of("mia", "rose"), id -> id);
        assertThat(out.rooms()).isEqualTo(4);
        assertThat(out.own()).isEqualTo(2);
        assertThat(out.asked()).isEqualTo(1);
        assertThat(out.unrecorded()).isEqualTo(1);
        assertThat(out.makersNamed()).isEqualTo(1);

        assertThat(store.room("the-erosion-garden-1").orElseThrow().madeFor()).isEqualTo(MadeThingsStore.OWN);
        var asked = store.room("crystalline-hum-room-2").orElseThrow();
        assertThat(asked.madeFor()).isEqualTo(PERSON);
        assertThat(asked.askedFor()).isTrue();
        var forge = store.room("the-forge-of-quiet-things-4").orElseThrow();
        assertThat(forge.createdBy()).as("the trail names who made an older room").isEqualTo("rose");
        assertThat(forge.madeFor()).isEqualTo(MadeThingsStore.OWN);
        var unknown = store.room("no-trace-3").orElseThrow();
        assertThat(unknown.madeFor()).isNull();
        assertThat(RoomKeeping.heldBecause(unknown, Set.of("nexus"), Set.of("mia", "rose")))
            .isEqualTo("nobody recorded who it was made for");
        assertThat(store.room("nexus").orElseThrow().madeFor()).as("a founding room is not a made room").isNull();
    }

    @Test
    void quiet_days_are_counted_from_when_someone_was_really_last_there() throws Exception {
        var later = MADE.plusSeconds(3 * 86400);
        var personThere = MADE.plusSeconds(86400);
        entered("the-erosion-garden-1", "companion-mia", "agent", later, 1);
        entered("the-erosion-garden-1", "u-1", "player", personThere, 2);
        var trail = trail(
            "{\"ts\":\"" + at(-60) + "\",\"type\":\"tick\",\"agent\":\"mia\"}",
            "{\"ts\":\"" + MADE.plusSeconds(5 * 86400) + "\",\"type\":\"move\",\"agent\":\"mia\",\"from\":\"nexus\",\"to\":\"crystalline-hum-room-2\"}");
        MadeThingsHistory.runOnce(jdbc, trail, Set.of("mia", "rose"), id -> id);
        var garden = store.room("the-erosion-garden-1").orElseThrow();
        assertThat(garden.lastEnteredAt()).isEqualTo(later.toEpochMilli());
        assertThat(garden.lastPersonAt()).as("a person has been in it: it is held").isEqualTo(personThere.toEpochMilli());
        assertThat(store.room("crystalline-hum-room-2").orElseThrow().lastEnteredAt())
            .as("the trail knows where she walked").isEqualTo(MADE.plusSeconds(5 * 86400).toEpochMilli());
        assertThat(store.room("no-trace-3").orElseThrow().lastEnteredAt())
            .as("never entered: from when it was made, not from the day of the upgrade").isEqualTo(MADE.toEpochMilli());
        assertThat(store.room("the-erosion-garden-1").orElseThrow().baselineObjects())
            .as("no object in its journal: nothing known of its furnishing").isNull();
    }

    @Test
    void it_is_read_once() throws Exception {
        var trail = trail("{\"ts\":\"" + at(-60) + "\",\"type\":\"tick\",\"agent\":\"mia\"}");
        assertThat(MadeThingsHistory.done(jdbc)).isFalse();
        assertThat(MadeThingsHistory.runOnce(jdbc, trail, Set.of("mia"), id -> id)).isNotNull();
        assertThat(MadeThingsHistory.done(jdbc)).isTrue();
        assertThat(MadeThingsHistory.runOnce(jdbc, trail, Set.of("mia"), id -> id)).isNull();
        assertThat(MadeThingsHistory.runOnce(jdbc, dir.resolve("no-such-file"), Set.of("mia"), id -> id)).isNull();
    }

    @Test
    void several_rooms_closed_in_one_pass_are_told_in_one_sentence() {
        var one = new RoomClosing.Result(true, "", "a", "The Erosion Garden", 12);
        assertThat(RoomClosing.toldLine(List.of(one))).startsWith("The Erosion Garden was closed up: nobody had been in it for 12 days.");
        var many = new java.util.ArrayList<RoomClosing.Result>();
        for (int i = 0; i < 8; i++) many.add(new RoomClosing.Result(true, "", "r" + i, "Room " + i, 10 + i));
        var told = RoomClosing.toldLine(many);
        assertThat(told).startsWith("8 rooms of yours were closed up, because nobody had been in them for a long while: Room 0 (10 days), ")
            .contains("and 3 more").endsWith("They are kept as they were. Go to one by name, or by what it was, and it opens again.");
        assertThat(RoomClosing.toldLine(List.<RoomClosing.Result>of())).isNull();
    }
}
