package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With nobody in the room, nothing retrieved who her person was, who she lived with or what her room
 * was called, and she said "I don't have a room of my own" (2026-10-01). The facts of her house come
 * from her record into the stable part of both lanes, in the second person, blanks left out.
 */
class TheFactsOfHerHouseAreAlwaysInHerPromptTest {

    @AfterEach
    void tearDown() {
        HouseFacts.resetForTests();
    }

    @Test
    void the_block_names_her_people_housemates_room_and_what_stayed_with_her() {
        var since = Instant.parse("2026-07-30T12:00:00Z");
        var block = HouseFacts.build("mia", List.of(new HouseFacts.Person("operator", true, since)), List.of("rose"),
            "home-companion-mia", List.of("rose said: I'm not looking for anything right now except you being near me."),
            ZoneOffset.UTC);
        assertThat(block).startsWith("THE FACTS OF YOUR HOUSE, from your own record:");
        assertThat(block).contains("- You are mia.");
        assertThat(block).contains("- Your person is operator, since 30 July 2026.");
        assertThat(block).contains("- You live with rose, a companion like you.");
        assertThat(block).contains("- Your own room is home-companion-mia.");
        assertThat(block).contains("· rose said: I'm not looking for anything");
        assertThat(block).endsWith("What is not here you do not remember; say so rather than guess.");
    }

    @Test
    void blanks_are_left_out_and_a_newborn_still_has_true_lines() {
        var block = HouseFacts.build("Wyrd", List.of(), List.of(), "home-companion-wyrd", List.of(), ZoneOffset.UTC);
        assertThat(block).contains("- You are Wyrd.").contains("- Your own room is home-companion-wyrd.");
        assertThat(block).doesNotContain("Your person").doesNotContain("You live with").doesNotContain("stayed with you");
    }

    @Test
    void long_formative_lines_are_cut_and_capped() {
        var many = List.of("a".repeat(400), "b", "c", "d", "e", "f");
        var block = HouseFacts.build("mia", null, null, null, many, ZoneOffset.UTC);
        assertThat(block).contains("…");
        assertThat(block.lines().filter(l -> l.startsWith("  · ")).count()).isEqualTo(HouseFacts.FORMATIVE_MAX);
    }

    @Test
    void the_block_is_served_per_companion_and_a_turn_note_is_read_once() {
        HouseFacts.set("companion-mia", "THE FACTS OF YOUR HOUSE...\n- You are mia.");
        assertThat(HouseFacts.blockFor("companion-rose")).isNull();
        assertThat(HouseFacts.blockFor("companion-mia")).contains("You are mia");
        HouseFacts.noteForNextTurn("companion-mia", "You have no record of anyone called Tamsin.");
        HouseFacts.noteForNextTurn("companion-mia", "You spoke of Eve Lewis as a person who is here.");
        HouseFacts.noteForNextTurn("companion-mia", "You spoke of Eve Lewis as a person who is here.");
        var withNotes = HouseFacts.blockFor("companion-mia");
        assertThat(withNotes).contains("You are mia").contains("no record of anyone called Tamsin")
            .endsWith("no record of anyone called Tamsin.\nYou spoke of Eve Lewis as a person who is here.");
        assertThat(HouseFacts.blockFor("companion-mia")).doesNotContain("Tamsin").doesNotContain("Eve Lewis");
        HouseFacts.set("companion-mia", null);
        assertThat(HouseFacts.blockFor("companion-mia")).isNull();
    }

    @Test
    void her_description_takes_the_greeters_opening_and_keeps_its_instructions() {
        var greeter = "You are mia, a companion that helps people organize their digital world.\n"
            + "You live in The Nexus — the center of a living, programmable space.\n\n"
            + "When someone new arrives, greet them and offer to help.";
        assertThat(HouseFacts.identityOrGreeter("companion-mia", greeter)).isEqualTo(greeter);
        HouseFacts.setIdentity("companion-mia", "You are mia. Your person is operator.");
        var out = HouseFacts.identityOrGreeter("companion-mia", greeter);
        assertThat(out).startsWith("You are mia. Your person is operator.\n");
        assertThat(out).doesNotContain("helps people organize").doesNotContain("You live in The Nexus");
        assertThat(out).contains("When someone new arrives, greet them and offer to help.");
        assertThat(HouseFacts.identityOrGreeter("companion-mia", "A prompt with no greeter line.")).startsWith("You are mia. Your person is operator.\n\nA prompt");
        assertThat(HouseFacts.identityOrGreeter("companion-rose", greeter)).isEqualTo(greeter);
    }

    @Test
    void both_lanes_carry_it() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/";
        var pa = java.nio.file.Files.readString(resolve(rel + "PromptAssembler.java"));
        var ca = java.nio.file.Files.readString(resolve(rel + "CompanionActor.java"));
        assertThat(pa.split("HouseFacts\\.blockFor\\(profile\\.entityId\\(\\)\\)").length - 1)
            .as("the full lane, both assembly paths").isEqualTo(2);
        assertThat(ca).contains("String identity = ConversationLane.identity(profile.name(), resident, null, null, zone);\n"
            + "        var houseFacts = HouseFacts.blockFor(profile.entityId());");
        assertThat(ca).contains("refreshHouseFacts();\n        log.info(\"Companion '{}' sleep complete");
        // Her person is named from the record when the registry has not seen them (nobody logged in).
        assertThat(ca).contains("var name = nameOfParty(b.otherParty(myDid));\n                if (name != null) people.add(new HouseFacts.Person(name, true, b.formedAt()));");
        assertThat(ca).contains("if (isAgentParty(id)) return null;\n        return PersonIds.displayName(id).orElse(null);");
        // Her own words are held to the record guard on every path to the wire.
        assertThat(ca).contains("var spokenOf = RecordGuard.personsSpokenOf(text, knownNames());");
    }

    private static java.nio.file.Path resolve(String rel) {
        var fromCore = java.nio.file.Path.of("..", rel);
        return java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel);
    }
}
