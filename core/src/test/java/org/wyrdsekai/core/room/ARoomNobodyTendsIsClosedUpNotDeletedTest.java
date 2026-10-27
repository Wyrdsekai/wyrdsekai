package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.MadeThingsStore;
import org.wyrdsekai.core.persistence.MadeThingsStore.RoomRecord;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household node, 2026-10-02: 149 made rooms, 49 of them from two companions in six days; of 128
 * rooms anyone had entered, 57 were entered on one day only. The steward's rules: what a companion
 * made because a person asked is that person's to let go and is never counted; what she made on her
 * own and nobody tends is closed up (never deleted) and counts against how many she keeps open; every
 * number is a setting.
 */
class ARoomNobodyTendsIsClosedUpNotDeletedTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long NOW = 1_790_000_000_000L;
    private static final Set<String> SEEDS = Set.of("nexus", "library");
    private static final Set<String> COMPANIONS = Set.of("mia", "rose");
    private static final RoomKeeping.Settings DEFAULTS = new RoomKeeping.Settings(7, 30, 12);

    private static RoomRecord room(String id, String by, String madeFor, long lastEnteredDaysAgo, Long personAt, Long closedAt) {
        return new RoomRecord(id, id, "companion-created", by, NOW - 40 * DAY, madeFor,
            NOW - lastEnteredDaysAgo * DAY, personAt, closedAt, null, 3);
    }

    @Test
    void what_a_person_asked_for_or_has_been_in_is_held() {
        assertThat(RoomKeeping.heldBecause(room("reading-nook-1", "mia", "did:key:z6MkPerson", 90, null, null), SEEDS, COMPANIONS))
            .isEqualTo("a person asked for it");
        assertThat(RoomKeeping.heldBecause(room("gift-room-2", "mia", MadeThingsStore.OWN, 90, NOW - 60 * DAY, null), SEEDS, COMPANIONS))
            .isEqualTo("a person has been in it");
        assertThat(RoomKeeping.heldBecause(room("dusk-nook-3", "mia", MadeThingsStore.OWN, 90, null, null), SEEDS, COMPANIONS)).isNull();
        // Made before anyone recorded who asked, and the trail could not say: held, not assumed hers.
        assertThat(RoomKeeping.heldBecause(room("old-room-4", "rose", null, 1, null, null), SEEDS, COMPANIONS))
            .isEqualTo("nobody recorded who it was made for");
    }

    @Test
    void founding_rooms_homes_studies_and_rooms_with_no_known_maker_are_never_the_houses_to_close() {
        assertThat(RoomKeeping.heldBecause(room("nexus", "system", null, 400, null, null), SEEDS, COMPANIONS)).isEqualTo("a founding room");
        assertThat(RoomKeeping.heldBecause(room("home-companion-mia", "system", null, 400, null, null), SEEDS, COMPANIONS)).isEqualTo("a companion's Home");
        assertThat(RoomKeeping.heldBecause(room("study-1f56", "system", null, 400, null, null), SEEDS, COMPANIONS)).isEqualTo("a person's Study");
        assertThat(RoomKeeping.heldBecause(room("workshop-codezaiku-1f56", "mia", MadeThingsStore.OWN, 400, null, null), SEEDS, COMPANIONS))
            .isEqualTo("a person's Workshop");
        assertThat(RoomKeeping.heldBecause(room("workshop-codeplane-1f56", "mia", MadeThingsStore.OWN, 400, null, null), SEEDS, COMPANIONS))
            .isEqualTo("a person's Workshop");
        assertThat(RoomDemolition.refusal("workshop-codezaiku-1f56", null, SEEDS)).contains("Workshop is theirs");
        assertThat(RoomKeeping.heldBecause(room("the-chamber-of-firsts-8443", "system", null, 400, null, null), SEEDS, COMPANIONS))
            .isEqualTo("no companion made it");
        assertThat(RoomKeeping.heldBecause(room("a-visitor-room", "Ignatius", MadeThingsStore.OWN, 400, null, null), SEEDS, COMPANIONS))
            .isEqualTo("no companion made it");
    }

    @Test
    void an_empty_room_is_due_after_seven_quiet_days_and_one_that_holds_something_after_thirty() {
        var eight = room("dusk-nook-3", "mia", MadeThingsStore.OWN, 8, null, null);
        var six = room("attention-study-5", "rose", MadeThingsStore.OWN, 6, null, null);
        assertThat(RoomKeeping.due(eight, false, DEFAULTS, NOW)).isTrue();
        assertThat(RoomKeeping.due(six, false, DEFAULTS, NOW)).isFalse();
        assertThat(RoomKeeping.due(eight, true, DEFAULTS, NOW)).as("it holds something: thirty days").isFalse();
        assertThat(RoomKeeping.due(room("r", "mia", MadeThingsStore.OWN, 31, null, null), true, DEFAULTS, NOW)).isTrue();
        assertThat(RoomKeeping.due(room("r", "mia", MadeThingsStore.OWN, 90, null, NOW - DAY), false, DEFAULTS, NOW))
            .as("already closed up").isFalse();
        assertThat(RoomKeeping.due(eight, false, new RoomKeeping.Settings(0, 30, 12), NOW)).as("0 turns the rule off").isFalse();
        assertThat(RoomKeeping.days(RoomKeeping.quietFor(eight, NOW))).isEqualTo("8 days");
    }

    /**
     * She could make a room at any moment and never let one go. The limit first chose for her (the
     * room longest unentered, with an hour to walk there and spare it); operator, 2026-10-02: "lets do
     * it" — the choice is hers. She has the verb at any time, and at the limit she is shown the three
     * quietest with what each is. If she does not choose, the quietest goes.
     */
    @Test
    void which_room_to_close_is_hers_to_choose() throws Exception {
        var all = List.of(
            room("nine-days", "mia", MadeThingsStore.OWN, 9, null, null),
            room("three-days", "mia", MadeThingsStore.OWN, 3, null, null),
            room("today", "mia", MadeThingsStore.OWN, 0, null, null),
            room("five-days", "mia", MadeThingsStore.OWN, 5, null, null),
            room("twelve-days", "mia", MadeThingsStore.OWN, 12, null, null));
        var own = RoomKeeping.openOwn(all, "mia", SEEDS);
        assertThat(RoomKeeping.quietest(own, Set.of(), NOW, 3)).extracting(RoomRecord::roomId)
            .containsExactly("twelve-days", "nine-days", "five-days");
        assertThat(RoomKeeping.quietest(own, Set.of("twelve-days"), NOW, 3)).extracting(RoomRecord::roomId)
            .as("the room she is standing in is never offered").containsExactly("nine-days", "five-days", "three-days");
        assertThat(RoomKeeping.quietest(List.of(room("today", "mia", MadeThingsStore.OWN, 0, null, null)), Set.of(), NOW, 3))
            .as("nothing entered today is offered").isEmpty();

        var kit = org.wyrdsekai.core.item.ToolItemStarterKit.standard();
        var tool = kit.stream().filter(t -> "close_room".equals(t.id())).findFirst().orElseThrow();
        assertThat(tool.description()).contains("Nothing is destroyed").contains("opens again when anyone goes to it")
            .contains("a room a person asked for is theirs to let go");
        assertThat(org.wyrdsekai.core.agent.ActionPolicy.AUTONOMY_TIERS.get("close_room"))
            .isEqualTo(org.wyrdsekai.core.agent.ActionPolicy.AutonomyTier.VISIBLE);

        var ca = read("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        assertThat(ca).contains("if (\"close_room\".equals(toolItem.builtinHandler())) {\n                handleCloseRoom(node);");
        // Hers alone: what a person asked for, or has been in, is theirs to let go.
        assertThat(ca).contains("var held = RoomKeeping.heldBecause(rec, closing.protectedRooms(), Set.of(profile.name()));\n        if (held != null) {");
        // At the limit she is shown three, with what each is, and the verb.
        assertThat(ca).contains("close one up first with close_room. The ones nobody has entered");
        assertThat(ca).contains("\", holds something you put there\"");
        // The verb the notice names cannot be trimmed off her menu.
        assertThat(ca).contains("\"shape_recipe\", \"sending_stone\", \"dispatch_task\", \"close_room\", \"read_book\", \"project_note\", \"what_i_have\");");
        assertThat(ca).contains("for (var door : DOORS_NAMED_IN_THE_PROMPT) if (autonomyPromptLower.contains(door)) kept.add(door);");
        // And if she does not choose, nothing of hers is closed: the room waits, and she is told so.
        // (The first build closed the quietest for her; the first night, rose had said no and
        // the house closed a four-day-old room she had not picked, 2026-10-03.)
        assertThat(ca).doesNotContain("to make way for");
        assertThat(ca).contains("Nothing of yours is closed unless you choose it.");
        assertThat(ca).contains("The choice stays yours: close_room on one of them first, or let \"");
        for (var lang : List.of("en", "es", "ja")) {
            assertThat(read("scripts/i18n/" + lang + ".json")).contains("\"item.close_room.descriptor\"");
        }
    }

    @Test
    void the_house_is_wired_to_keep_these_rules() throws Exception {
        var ca = read("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
        var zg = read("core/src/main/java/org/wyrdsekai/core/room/ZoneGuardian.java");
        var ra = read("core/src/main/java/org/wyrdsekai/core/room/RoomActor.java");
        // Who a room is for is decided when it is made, on both ways of making one.
        assertThat(ca.split(java.util.regex.Pattern.quote("if (heldBackByHerOpenRooms(roomName, madeFor)) return;"), -1).length - 1).isEqualTo(2);
        assertThat(ca).contains("stampRoomMade(newRoomId, seed.name(), \"companion-created\", madeFor,");
        assertThat(ca).contains("stampRoomMade(newRoomId, roomName, \"player-created\", madeFor, furnishedWith);");
        // A person's request is never held back or counted.
        assertThat(ca).contains("if (closing == null || !MadeThingsStore.OWN.equals(madeFor)) return false;");
        // A closed-up room is opened by going to it, and is not made a second time.
        assertThat(ca).contains("if (openClosedRoomAndGo(target)) return;");
        // The opening is waited for, so what she tells the person who asked is true.
        assertThat(ca).contains("res = closing.open(shut.roomId()).toCompletableFuture().get(8, TimeUnit.SECONDS);");
        assertThat(ca).doesNotContain("ClosedRoomOpened");
        // And every way she walks opens a closed room first, a remembered doorway included.
        assertThat(ca).contains("private void moveToRoomById(String targetRoomId, String direction) {\n"
            + "        // Every way she walks passes here: a room that was closed up is opened before she arrives.");
        assertThat(ca).contains("        openIfClosedUp(targetRoomId);\n        var previousRoomId = roomId;");
        assertThat(ca.split(java.util.regex.Pattern.quote("if (openClosedRoomInsteadOfMaking(roomName)) return;"), -1).length - 1).isEqualTo(2);
        // After her sleep her quiet rooms are looked at; she is told what was closed.
        assertThat(ca).contains("closeUpHerQuietRooms();\n        putAwayHerUnusedItems();\n        restHerQuietProjects();\n        refreshHouseFacts();");
        assertThat(ca).contains("if (err != null || closed == null || closed.isEmpty()) return;\n                RoomClosing.tell(entityId, closed);");
        assertThat(read("core/src/main/java/org/wyrdsekai/core/room/RoomClosing.java"))
            .contains("org.wyrdsekai.core.agent.HouseFacts.noteForNextTurn(entityId, told);");
        // The zone does not start a closed-up room at boot, and can start one again.
        assertThat(zg).contains("if (closedUp.contains(info.roomId())) continue;");
        assertThat(zg).contains(".onMessage(ReopenRoom.class, cmd -> {");
        // Every entry is recorded, a person's apart.
        assertThat(ra).contains("\"player\".equals(cmd.entityType()) || \"visitor\".equals(cmd.entityType()));");
    }

    private static String read(String rel) throws Exception {
        var from = java.nio.file.Path.of("..", rel);
        return java.nio.file.Files.readString(java.nio.file.Files.exists(from) ? from : java.nio.file.Path.of(rel));
    }

    @Test
    void only_her_own_untended_rooms_count_against_how_many_she_keeps_open() {
        var all = List.of(
            room("own-a", "mia", MadeThingsStore.OWN, 2, null, null),
            room("own-b", "mia", MadeThingsStore.OWN, 9, null, null),
            room("unrecorded", "mia", null, 60, null, null),
            room("asked", "mia", "did:key:z6MkPerson", 50, null, null),
            room("visited", "mia", MadeThingsStore.OWN, 50, NOW - DAY, null),
            room("closed", "mia", MadeThingsStore.OWN, 50, null, NOW - DAY),
            room("roses", "rose", MadeThingsStore.OWN, 50, null, null),
            room("home-companion-mia", "system", null, 0, null, null));
        var own = RoomKeeping.openOwn(all, "mia", SEEDS);
        assertThat(own).extracting(RoomRecord::roomId).containsExactly("own-a", "own-b");
        assertThat(RoomKeeping.atLimit(own, new RoomKeeping.Settings(7, 30, 2))).isTrue();
        assertThat(RoomKeeping.atLimit(own, DEFAULTS)).isFalse();
        assertThat(RoomKeeping.atLimit(own, new RoomKeeping.Settings(7, 30, 0))).as("0 sets no limit").isFalse();
        assertThat(RoomKeeping.longestQuiet(own, Set.of(), NOW)).get().extracting(RoomRecord::roomId).isEqualTo("own-b");
        assertThat(RoomKeeping.longestQuiet(own, Set.of("own-b"), NOW)).get().extracting(RoomRecord::roomId).isEqualTo("own-a");
    }
}
