package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.room.BrokenRoomScripts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room script a companion wrote that fails gets the workshop's mend the way a placed item does:
 * on a copy, through the coding helper, judged by the error the room actually saw, the file
 * replaced only when whole, the old version kept, three tries and then it rests. Asked for on
 * 2026-10-09 ("why not?") after the set-aside shipped without it.
 */
class ARoomScriptIsMendedWhileTheHouseIsQuietTest {

    private static final String BARE = "function onEnter(id, name, from) { emit('narrate', { text: 'welcome' }); }\n";
    private static final String WHOLE = "function onEnter(id, name, from) { world.emit('narrate', { text: 'welcome' }); }\n";
    private static final String BROKEN_PARSE = "function onEnter(id, name, from) { world.emit('narrate', { text: 'welcome' ) }\n";

    @TempDir Path dir;
    Path rooms;
    Path items;

    @BeforeEach
    void fresh() throws Exception {
        BrokenRoomScripts.resetForTests();
        NightMending.resetForTests();
        rooms = dir.resolve("scripts"); Files.createDirectories(rooms);
        items = dir.resolve("items"); Files.createDirectories(items);
    }

    @AfterEach
    void noBackend() { ItemContractRepair.setEscalation(null); }

    private void failedThrice(String room, String script, String error) throws Exception {
        var f = rooms.resolve(room + ".js");
        Files.writeString(f, script);
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000L));
        for (int i = 0; i < 3; i++) BrokenRoomScripts.failed(room, rooms, "onEnter", error);
    }

    @Test
    @DisplayName("what is wrong, judged by the error the room saw: a bare world call, a parse error, and nothing when whole")
    void problems() {
        var bare = RoomScriptRepair.problems("alcove", BARE, "onEnter: ReferenceError: emit is not defined");
        assertThat(bare).containsExactly(
            "it calls emit(...) on its own; a room script reaches the world through world.emit(...)");
        assertThat(RoomScriptRepair.problems("alcove", BROKEN_PARSE, null)).hasSize(1).first().asString().startsWith("it does not parse: ");
        assertThat(RoomScriptRepair.problems("alcove", WHOLE, "onEnter: ReferenceError: emit is not defined")).isEmpty();
        // A failure the sandbox reproduces, with no bare call to name: the hook is named with it.
        var again = RoomScriptRepair.problems("study", "function onSay(id, name, text) { id.getProperty('x'); }",
            "onSay: TypeError: id.getProperty is not a function");
        assertThat(again).hasSize(1);
        assertThat(again.get(0)).startsWith("onSay still fails as it did in the room: ");
        // A script that defines emit itself is not calling the world bare.
        assertThat(RoomScriptRepair.problems("x", "function emit(t) {} function onEnter() { emit('a'); }", null)).isEmpty();
        assertThat(RoomScriptRepair.failedHook("onSay: TypeError: x")).isEqualTo("onSay");
        assertThat(RoomScriptRepair.failedHook("top level: SyntaxError")).isNull();
        assertThat(RoomScriptRepair.sameFailure("TypeError: id.getProperty is not a function", "onSay: TypeError: id.getProperty is not a function")).isTrue();
        assertThat(RoomScriptRepair.sameFailure("ReferenceError: emit is not defined", "onSay: TypeError: id.getProperty is not a function")).isFalse();
    }

    @Test
    @DisplayName("the night mends a failing room script on a copy, replaces it, keeps the old version, tells her, and the record clears")
    void mendedInTheNight() throws Exception {
        failedThrice("alcove", BARE, "ReferenceError: emit is not defined");
        assertThat(BrokenRoomScripts.isSetAside("alcove", rooms)).isTrue();
        assertThat(BrokenRoomScripts.mendable(rooms)).extracting(BrokenRoomScripts.Entry::roomId).containsExactly("alcove");
        var prompts = new java.util.ArrayList<String>();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            try (var files = Files.list(workspace)) {
                for (var f : files.filter(p -> p.toString().endsWith(".js")).toList()) Files.writeString(f, WHOLE);
                return true;
            } catch (Exception e) { return false; }
        });
        var map = BodyMap.inMemory();
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items, rooms,
            Duration.ZERO, Duration.ZERO, Duration.ZERO)).isEqualTo(1);
        assertThat(Files.readString(rooms.resolve("alcove.js"))).isEqualTo(WHOLE);
        try (var kept = Files.list(rooms.resolve(".repaired"))) {
            assertThat(kept.filter(p -> p.getFileName().toString().startsWith("alcove.js.")).count()).as("the old version is kept").isEqualTo(1);
        }
        assertThat(BrokenRoomScripts.isSetAside("alcove", rooms)).isFalse();
        assertThat(BrokenRoomScripts.mendable(rooms)).isEmpty();
        assertThat(map.recentMarks(1).get(0).text())
            .isEqualTo("The workshop mended the script in alcove while the house was quiet; it runs again. The version it replaced is kept.");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("EDIT alcove.js IN PLACE").contains("world.emit('narrate'")
            .contains("it calls emit(...) on its own").contains("The arguments are strings: they have no methods.");
    }

    @Test
    @DisplayName("a mend that does not make it whole leaves the file as it was; three tries and it rests until the file changes")
    void threeTriesThenRest() throws Exception {
        failedThrice("garden", BARE, "ReferenceError: emit is not defined");
        var runs = new AtomicInteger();
        ItemContractRepair.setEscalation((workspace, prompt) -> { runs.incrementAndGet(); return true; });   // changes nothing
        var map = BodyMap.inMemory();
        for (int night = 1; night <= 3; night++) {
            assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items, rooms,
                Duration.ZERO, Duration.ZERO, Duration.ZERO)).isZero();
        }
        assertThat(Files.readString(rooms.resolve("garden.js"))).isEqualTo(BARE);
        assertThat(runs.get()).as("two rounds a try, three tries").isEqualTo(6);
        assertThat(BrokenRoomScripts.triedOut("garden", rooms)).isTrue();
        assertThat(BrokenRoomScripts.mendable(rooms)).as("it rests").isEmpty();
        assertThat(map.recentMarks(1).get(0).text()).contains("tried to mend the script in garden 3 times and could not; it stays set aside");
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items, rooms,
            Duration.ZERO, Duration.ZERO, Duration.ZERO)).isZero();
        assertThat(runs.get()).as("a fourth night tries nothing").isEqualTo(6);
        // She writes it again by hand (the file changes): it runs first, and a new failure is a new thing to try.
        var f = rooms.resolve("garden.js");
        Files.writeString(f, BARE + "\n// again");
        Files.setLastModifiedTime(f, FileTime.fromMillis(2_000_000L));
        assertThat(BrokenRoomScripts.isSetAside("garden", rooms)).isFalse();
        assertThat(BrokenRoomScripts.mendable(rooms)).as("not failed yet as it stands").isEmpty();
    }

    @Test
    @DisplayName("a worse result is reverted; with no coding helper nothing is tried or counted")
    void neverWorseAndNoHelper() throws Exception {
        failedThrice("loft", BARE, "ReferenceError: emit is not defined");
        ItemContractRepair.setEscalation(null);
        var r = RoomScriptRepair.repair("loft", rooms.resolve("loft.js"), "onEnter: ReferenceError: emit is not defined");
        assertThat(r.fixed()).isFalse();
        assertThat(r.note()).isEqualTo("no coding backend is registered to repair with");
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, BodyMap.inMemory(), items, rooms,
            Duration.ZERO, Duration.ZERO, Duration.ZERO)).isZero();
        assertThat(BrokenRoomScripts.triedOut("loft", rooms)).as("nothing was tried, nothing counted").isFalse();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try (var files = Files.list(workspace)) {
                for (var f : files.filter(p -> p.toString().endsWith(".js")).toList()) Files.writeString(f, BROKEN_PARSE);
                return true;
            } catch (Exception e) { return false; }
        });
        var worse = RoomScriptRepair.repair("loft", rooms.resolve("loft.js"), "onEnter: ReferenceError: emit is not defined");
        assertThat(worse.fixed()).isFalse();
        assertThat(Files.readString(rooms.resolve("loft.js"))).as("the room's file is untouched").isEqualTo(BARE);
    }

    @Test
    @DisplayName("the bench, the steward's route and the launcher reach a room's script")
    void reachable() throws Exception {
        var night = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/coding/NightMending.java"));
        assertThat(night).contains("if (entry == null) return mendRoomNow(item);");
        assertThat(night).contains("BodyMap.get(), ScriptedItemLoader.householdItemsDir(), SystemPaths.scriptsDir(), SETTLE, POLL, WINDOW);");
        var routes = Path.of("../server/src/main/java/org/wyrdsekai/server/http/ItemRepairRoutes.java");
        if (Files.exists(routes)) {
            assertThat(Files.readString(routes)).contains("app.post(\"/api/rooms/script/repair\", this::handleRoomRepair);");
            assertThat(Files.readString(Path.of("../server/src/main/java/org/wyrdsekai/server/http/ApiPolicy.java")))
                .contains("\"POST /api/rooms/script/repair\"");
            assertThat(Files.readString(Path.of("../bin/wyrd"))).contains("        repair)\n").contains("\"$api/api/rooms/script/repair\"");
        }
    }
}
