package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.item.BrokenItems;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A thing that was made for her and does not work is hers to know: she is told once, in plain
 * words. While the house is quiet the workshop mends what is broken, as many as the night's
 * minutes allow, not one; it stops when someone needs the thinking brain, and it stops trying
 * a thing that will not mend. She is told in the morning what was mended and what could not be.
 */
class WhatIsBrokenIsHersToKnowAndIsMendedWhileTheHouseIsQuietTest {

    private static String item(String name, String body, String commands) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "A thing made in the workshop.",
              author: "did:wyrd:codezaiku",
              capabilities: ["memory.add"],
              rate_limits: { "memory.add": "10/min" },
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} uses it" },
              commands: %s
            };
            function invoke(params) {
            %s
            }
            """.formatted(name, commands, body);
    }

    private static final String TWO = "[ { label: \"Look\", args: \"\" }, { label: \"Recall\", args: \"recall\" } ]";
    private static String reaches(String name) {
        return item(name, "  var a = (params.args || \"\");\n  if (a === \"recall\") { return { ok: true, text: String(world.memory.get(\"x\")) }; }\n  return { ok: true, text: \"quiet\" };", TWO);
    }
    private static String misleads(String name) { return item(name, "  return { ok: true, text: \"quiet\" };", TWO); }
    private static String whole(String name) {
        return item(name, "  var a = (params.args || \"\");\n  if (a === \"recall\") { world.memory.add(\"kept\"); return { ok: true, text: \"kept\" }; }\n  return { ok: true, text: \"quiet\" };", TWO);
    }

    @AfterEach
    void tearDown() {
        ItemContractRepair.setEscalation(null);
        NightMending.resetForTests();
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("she is told once, in plain words, and again only when the way it is broken changes")
    void toldOnce(@TempDir Path items) throws Exception {
        Files.writeString(items.resolve("bondholder_mirror.js"), reaches("bondholder_mirror"));
        Files.writeString(items.resolve("study_nook.js"), misleads("study_nook"));
        Files.writeString(items.resolve("lantern.js"), whole("lantern"));
        var map = BodyMap.inMemory();
        var found = BrokenItems.find(items);
        assertThat(found).extracting(BrokenItems.Entry::item).containsExactly("bondholder_mirror", "study_nook");
        assertThat(found.get(0).failsOnUse()).as("what fails in her hands comes first").isTrue();

        assertThat(BrokenItems.tellOnce(found, map, true)).isEqualTo(2);
        assertThat(map.recentMarks(5)).as("one sentence however many there are: her felt line carries three marks a turn").hasSize(1);
        assertThat(map.recentMarks(1).get(0).text()).isEqualTo(
            "Two things that were made for me do not work as they should. The bondholder mirror reaches for parts of the world that are not there, "
            + "and will fail when used. One other offers several commands and does the same thing for every one. "
            + "The workshop will try to mend them when the house is quiet, or I can take one to the mending bench.");
        assertThat(BrokenItems.tellOnce(BrokenItems.find(items), map, true)).as("not told twice").isZero();

        Files.writeString(items.resolve("study_nook.js"), reaches("study_nook"));
        assertThat(BrokenItems.tellOnce(BrokenItems.find(items), map, true)).as("broken in a new way is news").isEqualTo(1);
        assertThat(map.recentMarks(1).get(0).text())
            .startsWith("The study nook that was made for me does not work yet: it reaches for a part of the world that is not there (world.memory.get).")
            .contains("mend it when the house is quiet");
    }

    @Test
    @DisplayName("the workshop mends as many as the night allows, tells her of each, and the mended ones work")
    void asManyAsTheNightAllows(@TempDir Path items) throws Exception {
        for (var n : new String[] {"mirror_a", "mirror_b", "mirror_c"}) Files.writeString(items.resolve(n + ".js"), reaches(n));
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try (var files = Files.list(workspace)) {
                for (var f : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                    Files.writeString(f, whole(f.getFileName().toString().replace(".js", "")));
                }
                return true;
            } catch (Exception e) { return false; }
        });
        var map = BodyMap.inMemory();
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items)).as("three broken, three mended: the bound is time").isEqualTo(3);
        assertThat(BrokenItems.find(items)).isEmpty();
        assertThat(map.recentMarks(5)).extracting(m -> m.text()).filteredOn(t -> t.startsWith("The workshop mended the mirror")).hasSize(3);
    }

    @Test
    @DisplayName("it stops when someone needs the thinking brain, and when the night's minutes are spent")
    void itYields(@TempDir Path items) throws Exception {
        for (var n : new String[] {"mirror_a", "mirror_b", "mirror_c"}) Files.writeString(items.resolve(n + ".js"), reaches(n));
        var runs = new AtomicInteger();
        ItemContractRepair.setEscalation((workspace, prompt) -> { runs.incrementAndGet(); return true; });
        var asked = new AtomicInteger();
        NightMending.mend(Duration.ofMinutes(45), () -> asked.incrementAndGet() <= 1, BodyMap.inMemory(), items);
        assertThat(BrokenItems.find(items)).as("the second item was never started: someone was speaking to her").hasSize(3);
        int afterFirstNight = runs.get();
        assertThat(afterFirstNight).isBetween(1, ItemContractRepair.MAX_ROUNDS);

        NightMending.resetForTests();
        NightMending.mend(Duration.ZERO, () -> true, BodyMap.inMemory(), items);
        assertThat(runs.get()).as("no minutes, no mending").isEqualTo(afterFirstNight);
    }

    @Test
    @DisplayName("she wakes speaking: the workshop waits for a quiet moment instead of giving up on the first busy sample")
    void itWaitsForQuiet(@TempDir Path items) throws Exception {
        for (var n : new String[] {"mirror_a", "mirror_b"}) Files.writeString(items.resolve(n + ".js"), reaches(n));
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try (var files = Files.list(workspace)) {
                for (var f : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                    Files.writeString(f, whole(f.getFileName().toString().replace(".js", "")));
                }
                return true;
            } catch (Exception e) { return false; }
        });
        // Busy for the first four looks (the chronicle, the polish, her first turn), then quiet.
        var looks = new AtomicInteger();
        var mended = NightMending.mend(Duration.ofMinutes(45), () -> looks.incrementAndGet() > 4, BodyMap.inMemory(), items,
            Duration.ZERO, Duration.ZERO, Duration.ofMinutes(10));
        assertThat(mended).as("both mended once the house went quiet").isEqualTo(2);
        assertThat(BrokenItems.find(items)).isEmpty();

        // The window is the bound: a brain that never frees up ends the night with nothing tried.
        NightMending.resetForTests();
        Files.writeString(items.resolve("mirror_c.js"), reaches("mirror_c"));
        var before = looks.get();
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> { looks.incrementAndGet(); return false; }, BodyMap.inMemory(), items,
            Duration.ZERO, Duration.ZERO, Duration.ZERO)).isZero();
        assertThat(looks.get()).isGreaterThan(before);
        assertThat(BrokenItems.find(items)).hasSize(1);
    }

    @Test
    @DisplayName("a thing that will not mend is tried three times unchanged and then left for a person")
    void notForever(@TempDir Path items) throws Exception {
        Files.writeString(items.resolve("bondholder_mirror.js"), reaches("bondholder_mirror"));
        ItemContractRepair.setEscalation((workspace, prompt) -> true);   // completes, fixes nothing
        var map = BodyMap.inMemory();
        for (int night = 0; night < 5; night++) { NightMending.resetForTests(); NightMending.mend(Duration.ofMinutes(45), () -> true, map, items); }
        assertThat(map.recentMarks(20)).extracting(m -> m.text())
            .filteredOn(t -> t.contains("tried to mend the bondholder mirror")).as("three nights, not five").hasSize(BrokenItems.MAX_ATTEMPTS);
        assertThat(BrokenItems.mendable(items)).isEmpty();
        assertThat(Files.readString(items.resolve("bondholder_mirror.js"))).as("and it is as it was").isEqualTo(reaches("bondholder_mirror"));

        Files.writeString(items.resolve("bondholder_mirror.js"), reaches("bondholder_mirror") + "\n// someone touched it\n");
        assertThat(BrokenItems.mendable(items)).as("a changed file is worth trying again").hasSize(1);
    }
}
