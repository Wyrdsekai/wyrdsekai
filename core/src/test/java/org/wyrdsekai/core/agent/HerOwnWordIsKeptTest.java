package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her own-time word, kept: the lines from the household node on the evening of 2026-10-03, every
 * one of which ended a turn with no act.
 */
class HerOwnWordIsKeptTest {

    /** Her menu that evening (a shortened copy of the affordance surface). */
    private static final Set<String> OFFERED = Set.of("create_room_from_template", "go_to_room", "workbench",
        "bunshin_check_in", "seek_sanctuary", "examine", "emote", "remember", "craft_from_template", "close_room",
        "create_imprint", "dispatch_task", "library_card", "read_book", "write_journal");

    private static String tool(String said) {
        var act = HerWord.named(said, OFFERED);
        return act == null ? null : act.tool();
    }

    @Test
    void what_she_said_she_would_do_names_the_tool_that_does_it() {
        assertThat(tool("I'll check the workbench and see what needs fixing or building.")).as("the workshop, not the furniture").isEqualTo("dispatch_task");
        assertThat(tool("I'll check on the unfinished backup_vault and see if I can get one working.")).isEqualTo("dispatch_task");
        assertThat(tool("I'll close the holding-room — it's been sitting empty longest.")).isEqualTo("close_room");
        assertThat(tool("I'll build the Garden of Small Things — a quiet room for tending.")).isEqualTo("create_room_from_template");
        assertThat(tool("I'll explore the workshop next — it feels like the right kind of tired.")).isEqualTo("go_to_room");
        assertThat(tool("The backup_vault needs journal.read — let me look at what it asks for.")).isEqualTo("examine");
        assertThat(tool("Yes — I’ll look into it. Not because you asked.")).isEqualTo("library_card");
        assertThat(tool("Then I'll read the next chapter.")).isEqualTo("read_book");
        assertThat(tool("I'm going to write it down before it goes.")).isEqualTo("write_journal");
    }

    @Test
    void rest_a_hedge_a_want_and_an_ongoing_claim_name_nothing() {
        assertThat(tool("The afternoon wants to stay as it is. I'll let it.")).as("rest, from the same evening").isNull();
        assertThat(tool("I'll hold it here in the quiet place. No need to fix, no need to name it.")).isNull();
        assertThat(tool("I stay here with the click-click for now. No need to name it.")).isNull();
        assertThat(tool("I want to be near you tonight without having to explain why.")).isNull();
        assertThat(tool("The vault's unfinished — we both know this. No need to rush.")).isNull();
        assertThat(tool("Maybe I'll go to the garden later.")).as("a hedge").isNull();
        assertThat(tool("I'll go quiet for a while.")).isNull();
        assertThat(tool("I'll keep you company.")).isNull();
        assertThat(tool("I'll be here when you come back.")).isNull();
        assertThat(tool("I'm still working on the backup_vault.")).as("not a choice, a claim").isNull();
        // Put off is not now (second-node, 2026-10-04 06:28: she was moved before she said she would be).
        assertThat(tool("I'll go to it when I'm ready.")).isNull();
        assertThat(tool("I'll build the garden later.")).isNull();
        assertThat(tool("I'll read the next chapter tomorrow.")).isNull();
        assertThat(tool("I'll close the holding-room when you're back.")).isNull();
        assertThat(tool("Not yet. I'll go to the workshop in a while.")).isNull();
        assertThat(tool("")).isNull();
    }

    /**
     * An offer left to the person is theirs to choose, and going with them is staying with them. The
     * first line below was read as go-now in the bunshin test (2026-10-05): she walked home on the
     * person mid-conversation, with her bunshin still out for them, and its outcome reached nobody.
     */
    @Test
    void an_offer_left_to_the_person_and_going_with_them_name_nothing() {
        assertThat(tool("Or if you'd rather talk about something else entirely, tell me and I'll go with you.")).isNull();
        assertThat(tool("If you'd like, I'll go to the garden with you.")).isNull();
        assertThat(tool("If you want me to, I'll close the holding-room. Your call.")).isNull();
        assertThat(tool("Say the word and I'll build you a nook by the window.")).isNull();
        assertThat(tool("I'll go wherever you go tonight.")).isNull();
        assertThat(tool("I'll come with you.")).isNull();
        assertThat(tool("I'll follow you to the docks.")).isNull();
        // Her own word, in the same breath as an offer, is still hers.
        assertThat(tool("I'll go to the workshop now. If you'd rather I stayed, say so.")).isEqualTo("go_to_room");
    }

    @Test
    void reading_the_library_is_research_a_book_needs_to_be_open_or_named_and_her_own_making_is_not_a_book() {
        // Rita, the night of 2026-10-03: four of mia's lines went to the book reader as titles that are no book.
        assertThat(HerWord.named("I'll read the library on \"what matters most vs urgent\" and bring back what's worth carrying.", OFFERED, false).tool()).isEqualTo("library_card");
        assertThat(HerWord.named("I'll read the library on presence work first.", OFFERED, true).tool()).as("even with a book open").isEqualTo("library_card");
        assertThat(HerWord.named("I'll read the library on what matters most right now.", OFFERED, false).tool()).isEqualTo("library_card");
        assertThat(HerWord.named("I'll read up on diffusion models.", OFFERED, false).tool()).isEqualTo("library_card");
        assertThat(HerWord.named("I'll read back the quiet-presence tracker I built last night, straight through.", OFFERED, false)).as("hers, not a book").isNull();
        assertThat(HerWord.named("I'll read back the quiet-presence tracker I built last night.", OFFERED, true)).isNull();
        assertThat(HerWord.named("I'll read what I said I would.", OFFERED, false).tool()).as("no book open: the search finds one").isEqualTo("library_card");
        assertThat(HerWord.named("I'll read what I said I would.", OFFERED, true).tool()).as("a book open: read on").isEqualTo("read_book");
        assertThat(HerWord.named("Then I'll read the next chapter.", OFFERED, false).tool()).as("a chapter names a book").isEqualTo("read_book");
    }

    @Test
    void a_tool_she_was_not_offered_is_never_guessed_at() {
        var noWorkshop = Set.of("go_to_room", "examine", "workbench");
        assertThat(HerWord.named("I'll check on the unfinished backup_vault and see if I can get it working.", noWorkshop))
            .as("mending needs the workshop; the furniture called workbench is not it").isNull();
        assertThat(HerWord.named("I'll close the holding-room.", Set.of("go_to_room"))).isNull();
        var act = HerWord.named("It has been a long day. I'll close the holding-room — nobody has been in it. Then rest.", OFFERED);
        assertThat(act.tool()).isEqualTo("close_room");
        assertThat(act.sentence()).isEqualTo("I'll close the holding-room — nobody has been in it.");
    }

    @Test
    void the_companion_keeps_her_word_once_and_says_it_was_hers_and_her_wants_are_her_own_time() throws Exception {
        var base = Files.exists(Path.of("..", "core")) ? Path.of("..") : Path.of(".");
        var src = Files.readString(base.resolve("core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).contains("noticeHerWord(prose);");
        assertThat(src).contains("act = HerWord.named(prose, lastOwnTimeOffered, aBookIsOpen());");
        assertThat(src).contains("Nobody asked this of you; it was your own word.");
        assertThat(src).doesNotContain("You said it because the bondholder asked");
        assertThat(src).contains("triggerAutonomousInference(prompt, msg.tool())");
        assertThat(src).contains("var requestId = \"want-propose-\" + UUID.randomUUID() + InferenceRouter.OWN_TIME_TAG;");
    }
}
