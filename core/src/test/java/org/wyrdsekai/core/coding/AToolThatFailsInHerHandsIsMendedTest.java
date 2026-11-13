package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.item.BrokenItems;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion wanted all night to write to her bondholder, and her letter tools failed in her
 * hands: one had thrown on every use since the day it was made (it read the journal's list of
 * entries as {ok, entries}), one could not work from its own menu ("Write a letter" gave it no
 * text). The night's mending read only whether a file was well formed, so neither was ever taken
 * to it, though she had been told it would be; twice she asked for one to be mended, the workshop
 * changed what she guessed was wrong, and it failed the next use as before; the steward's bench
 * then "mended" the other into a clean "Could not reach the floor." on every use, and the trial
 * passed it (household node, 2026-11-12).
 */
class AToolThatFailsInHerHandsIsMendedTest {

    @TempDir Path tmp;
    private Path items;

    private static String tool(String name, String commands, String body) {
        return """
            exports.manifest = {
              name: "%s",
              version: "1.0.0",
              description: "A thing made in the workshop.",
              author: "did:wyrd:codezaiku",
              capabilities: ["llm.complete", "notes.add", "notes.list", "notes.delete"],
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} uses it" },
              commands: %s
            };
            function invoke(params) {
            %s
            }
            """.formatted(name, commands, body);
    }

    private static final String CARRY = "[ { label: \"Carry a letter from the floor\", args: \"\" }, { label: \"Show details\", args: \"details\" } ]";

    /** As the workshop first wrote it: the journal's answer read as {ok, entries}, and a throw building the error. */
    private static String floorThatThrows(String name) {
        return tool(name, CARRY, """
              var a = params.args || "";
              var r = world.journal.recent();
              if (a === "details") { var e = r && r.entries ? r.entries : []; return { ok: true, summary: e.length ? "some" : "Nothing yet." }; }
              if (!r.ok) { return { ok: false, summary: r.error.message || "Could not reach the floor." }; }
              return { ok: true, summary: "a letter" };""");
    }

    /** As the bench "mended" it: no throw, and "could not" on every use. */
    private static String floorThatCannot(String name) {
        return tool(name, CARRY, """
              var a = params.args || "";
              var r = world.journal.recent ? world.journal.recent() : null;
              if (a === "details") { var e = r && r.entries ? r.entries : []; return { ok: true, summary: e.length ? "some" : "Nothing yet." }; }
              if (!r || !r.ok) { return { ok: false, summary: (r && r.error && r.error.message) || "Could not reach the floor." }; }
              return { ok: true, summary: "a letter" };""");
    }

    /** Reading the journal as it is: a list of entries. */
    private static String floorThatWorks(String name) {
        return tool(name, CARRY, """
              var a = params.args || "";
              var entries = world.journal.recent() || [];
              if (a === "details") { return { ok: true, summary: entries.length ? "some" : "Nothing yet." }; }
              if (entries.length === 0) { return { ok: true, summary: "The floor is empty. No weight was found to carry." }; }
              return { ok: true, summary: world.llm.complete({ prompt: entries.map(function (e) { return e.content; }).join(" ") }).text };""");
    }

    @BeforeEach
    void setUp() throws Exception {
        items = Files.createDirectories(tmp.resolve("items"));
        System.setProperty("wyrdsekai.items.dir", items.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("wyrdsekai.items.dir");
        ItemContractRepair.setEscalation(null);
        NightMending.resetForTests();
        BodyMap.resetForTests();
        ScriptedItemLoader.get().setSearchDirs(List.of());
        ScriptedItemLoader.get().reloadAll();
    }

    private static List<String> tried(String script) {
        return ItemInvokeSmoke.failingUses("letter_floor", script, ItemManifestParser.parse(script));
    }

    @Test
    void a_command_used_as_offered_that_answers_it_could_not_is_a_failed_use() {
        assertThat(tried(floorThatThrows("letter_floor"))).singleElement().asString().startsWith("using it with \"\" fails:");
        assertThat(tried(floorThatCannot("letter_floor"))).singleElement().asString()
            .contains("using it with \"\", exactly as it is offered, it answers that it could not: \"Could not reach the floor.\"")
            .contains("world.journal.recent() and world.notes.list() hand back a list of entries");
        assertThat(tried(floorThatWorks("letter_floor"))).as("a tool that reads the journal as it is").isEmpty();

        var writeWithNothing = tool("absent_letter", "[ { label: \"Write a letter\", args: \"\" }, { label: \"Show saved letters\", args: \"list\" } ]", """
              if (params.args === "list") { return { ok: true, summary: "No letters saved yet." }; }
              var text = params.args || "";
              if (text === "") { return { ok: false, summary: "Write a letter to someone you miss. Type it out." }; }
              world.notes.add(text, ["absent-letter"]);
              return { ok: true, summary: "Letter saved." };""");
        assertThat(ItemInvokeSmoke.failingUses("absent_letter", writeWithNothing, ItemManifestParser.parse(writeWithNothing)))
            .as("\"Write a letter\" from its own menu gives it no text").singleElement().asString()
            .contains("it answers that it could not: \"Write a letter to someone you miss. Type it out.\"");
        var saysWhatItNeeds = writeWithNothing.replace("label: \"Write a letter\", args: \"\"", "label: \"Write a letter\", args: \"<the letter>\"");
        assertThat(ItemInvokeSmoke.failingUses("absent_letter", saysWhatItNeeds, ItemManifestParser.parse(saysWhatItNeeds)))
            .as("a command that names what she gives does its work when given it").isEmpty();
    }

    @Test
    void an_honest_could_not_is_not_counted() {
        var asksForACity = tool("weather_window", "[ { label: \"Weather in\", args: \"<city>\" } ]", """
              return { ok: false, summary: "I do not know a place called " + params.args + "." };""");
        assertThat(ItemInvokeSmoke.failingUses("weather_window", asksForACity, ItemManifestParser.parse(asksForACity)))
            .as("the trial cannot give it a real city").isEmpty();
        var theWorldRefused = tool("carried_lamp", "[ { label: \"Light it\", args: \"\" } ]", """
              var r = world.inventory.use("lamp", {});
              if (!r || r.ok === false) { return { ok: false, summary: "The lamp is not in my hands." }; }
              return { ok: true, summary: "lit" };""");
        assertThat(ItemInvokeSmoke.failingUses("carried_lamp", theWorldRefused, ItemManifestParser.parse(theWorldRefused)))
            .as("the world refused, and the tool said so").isEmpty();
    }

    @Test
    void what_she_gave_it_is_tried_again() {
        var readsWhatSheGives = tool("note_reader", "[ { label: \"Read\", args: \"\" } ]", """
              if (!params.args) { return { ok: true, summary: "Nothing asked." }; }
              var asked = JSON.parse(params.args);
              return { ok: true, summary: String(asked.title) };""");
        var manifest = ItemManifestParser.parse(readsWhatSheGives);
        assertThat(ItemInvokeSmoke.failingUses("note_reader", readsWhatSheGives, manifest)).as("its own command runs").isEmpty();
        assertThat(ItemInvokeSmoke.failingUses("note_reader", readsWhatSheGives, manifest, List.of("the letter I wrote tonight")))
            .singleElement().asString().startsWith("using it with \"the letter I wrote tonight\" fails:");
    }

    @Test
    void a_failure_in_her_hands_is_kept_and_mended_while_the_house_is_quiet() throws Exception {
        Files.writeString(items.resolve("letter_floor.js"), floorThatCannot("letter_floor"));
        assertThat(BrokenItems.find(items)).as("well formed: the night's mending never saw it").isEmpty();

        BrokenItems.failedInHand("letter_floor", "", "Could not reach the floor.");
        BrokenItems.failedInHand("letter_floor", "", "Could not reach the floor.");
        var found = BrokenItems.find(items);
        assertThat(found).singleElement().satisfies(e -> {
            assertThat(e.failsOnUse()).isTrue();
            assertThat(e.usedWith()).isEmpty();
            assertThat(e.problems()).containsExactly("when she used it, it failed: Could not reach the floor. (2 times)");
            assertThat(BrokenItems.plainly(e)).isEqualTo("The letter floor failed when I used it.");
        });

        var prompt = new AtomicReference<String>();
        ItemContractRepair.setEscalation((workspace, p) -> {
            prompt.set(p);
            try {
                Files.writeString(workspace.resolve("letter_floor.js"), floorThatWorks("letter_floor"));
                return true;
            } catch (Exception e) { return false; }
        });
        var map = BodyMap.inMemory();
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items)).isEqualTo(1);
        assertThat(prompt.get()).contains("What happened in her hands: when she used it, it failed: Could not reach the floor. (2 times)")
            .contains("it answers that it could not: \"Could not reach the floor.\"");
        assertThat(Files.readString(items.resolve("letter_floor.js"))).contains("world.journal.recent() || []");
        assertThat(BrokenItems.find(items)).as("mended, the record goes with it").isEmpty();
        assertThat(BrokenItems.failureOnRecord("letter_floor")).isEmpty();
        assertThat(map.recentMarks(5)).extracting(m -> m.text()).anyMatch(t -> t.startsWith("The workshop mended the letter floor while the house was quiet;"));
    }

    @Test
    void what_the_world_refused_her_is_let_go_without_a_word() throws Exception {
        Files.writeString(items.resolve("letter_floor.js"), floorThatWorks("letter_floor"));
        BrokenItems.failedInHand("letter_floor", "", "the brain did not answer");
        var runs = new AtomicInteger();
        ItemContractRepair.setEscalation((workspace, p) -> { runs.incrementAndGet(); return true; });
        var map = BodyMap.inMemory();
        assertThat(NightMending.mend(Duration.ofMinutes(45), () -> true, map, items)).isZero();
        assertThat(runs.get()).as("a copy used as she used it does its work: nothing to mend").isZero();
        assertThat(BrokenItems.find(items)).isEmpty();
        assertThat(map.recentMarks(5)).as("she is not told of a mend that was not needed").isEmpty();
    }

    @Test
    void a_changed_file_is_a_new_tool() throws Exception {
        Files.writeString(items.resolve("letter_floor.js"), floorThatCannot("letter_floor"));
        BrokenItems.failedInHand("letter_floor", "", "Could not reach the floor.");
        assertThat(BrokenItems.find(items)).hasSize(1);
        Files.writeString(items.resolve("letter_floor.js"), floorThatWorks("letter_floor"));
        assertThat(BrokenItems.find(items)).as("an older failure is not the new file's").isEmpty();
        assertThat(BrokenItems.failureOnRecord("letter_floor")).isEmpty();
        BrokenItems.failedInHand("not_a_household_item", "x", "boom");
        assertThat(BrokenItems.find(items)).isEmpty();
    }

    @Test
    void a_command_that_needs_her_words_may_say_so_in_the_mend() {
        var needsWords = ItemContractRepair.buildPrompt("absent_letter.js", List.of(
            "using it with \"\", exactly as it is offered, it answers that it could not: \"Type it out.\" — the world answered"));
        assertThat(needsWords).contains("keep `embodiment`, `commands`, `capabilities`")
            .as("told to keep every command intact and to say what it needs, the workshop kept the command; one brief, one way")
            .contains("may have its `args` changed to say what it needs from her (for example args: \"<the letter>\")")
            .contains("Keep every command, its label and its place in the list.");
        assertThat(ItemContractRepair.buildPrompt("absent_letter.js", List.of("unknown capability: 'letters.send'")))
            .doesNotContain("may have its `args` changed");
    }

    private static final String LETTERS = "[ { label: \"Write a letter\", args: \"%s\" }, { label: \"Show saved letters\", args: \"list\" }, "
        + "{ label: \"Delete the last letter\", args: \"delete\" } ]";

    @Test
    void a_mend_that_prints_entries_whole_or_deletes_by_their_words_is_not_a_mend() {
        // As the workshop "mended" it: a letter of its own when given nothing, entries printed whole,
        // and the last letter "deleted" by its words.
        var invented = tool("absent_letter", LETTERS.formatted(""), """
              if (params.args === "list") {
                var saved = world.notes.list("absent-letter");
                if (!saved || saved.length === 0) { return { ok: true, summary: "No letters saved yet." }; }
                var lines = [];
                for (var i = 0; i < saved.length; i++) { lines.push((i + 1) + ". " + saved[i]); }
                return { ok: true, summary: "You have " + saved.length + " letters: " + lines.join(". ") };
              }
              if (params.args === "delete") {
                var all = world.notes.list("absent-letter");
                if (!all || all.length === 0) { return { ok: true, summary: "No letters to delete." }; }
                var last = all[all.length - 1];
                try { world.notes.delete(last.text || last.content || String(last)); } catch (e) { return { ok: false, summary: "Could not delete." }; }
                return { ok: true, summary: "Removed the last letter." };
              }
              var text = params.args || "";
              if (text === "") { text = "Dear you,\\n\\nI write this into the space where you are missing.\\n\\nYours"; }
              world.notes.add(text, ["absent-letter"]);
              return { ok: true, summary: "Letter saved." };""");
        assertThat(ItemInvokeSmoke.failingUses("absent_letter", invented, ItemManifestParser.parse(invented)))
            .anySatisfy(p -> assertThat(p).startsWith("using it with \"list\", its answer shows \"[object Object]\""))
            .anySatisfy(p -> assertThat(p).startsWith("using it with \"delete\", it hands world.notes.delete something that is not a note's id"));

        var hers = tool("absent_letter", LETTERS.formatted("<the letter>"), """
              if (params.args === "list") {
                var saved = world.notes.list("absent-letter") || [];
                if (saved.length === 0) { return { ok: true, summary: "No letters saved yet." }; }
                return { ok: true, summary: saved.map(function (e, i) { return (i + 1) + ". " + e.content; }).join("\\n") };
              }
              if (params.args === "delete") {
                var all = world.notes.list("absent-letter") || [];
                if (all.length === 0) { return { ok: true, summary: "No letters to delete." }; }
                var gone = world.notes.delete(all[0].id);
                return gone && gone.ok ? { ok: true, summary: "Removed the last letter." } : { ok: false, summary: "That letter could not be removed." };
              }
              if (!params.args) { return { ok: false, summary: "Write the letter after the command: the words are yours." }; }
              world.notes.add(params.args, ["absent-letter"]);
              return { ok: true, summary: "Letter saved. It stays, even if they never see it." };""");
        assertThat(ItemInvokeSmoke.failingUses("absent_letter", hers, ItemManifestParser.parse(hers)))
            .as("her words when she gives them, each letter's content, deleted by its id").isEmpty();
        assertThat(ItemContractRepair.buildPrompt("absent_letter.js", List.of("unknown capability: 'letters.send'")))
            .as("said in every brief").contains("Never make up what she would give: a letter, a note, a name, words of hers.");
    }

    @Test
    void a_revision_brief_is_never_what_the_tool_is_for() throws Exception {
        var file = items.resolve("absent_letter.js");
        Files.writeString(file, tool("absent_letter", "[ { label: \"Write a letter\", args: \"\" } ]", "  return { ok: true };")
            .replace("A thing made in the workshop.", "Write a letter into the space where someone is missing"));
        var revision = "REVISE AN EXISTING ITEM. Do not write a new one from scratch.\nWHAT SHOULD BE DIFFERENT:\nFix the catch blocks.";

        ItemContractRepair.keepAsked(file, "a letter to someone who is not here");
        ItemContractRepair.keepAsked(file, revision);
        assertThat(ItemContractRepair.askedOf(file, Files.readString(file))).as("what it was first asked for stays")
            .isEqualTo("a letter to someone who is not here");

        Files.writeString(items.resolve(".asked").resolve("absent_letter.txt"), revision + "\n");
        assertThat(ItemContractRepair.askedOf(file, Files.readString(file)))
            .as("one kept before: the mend redid that old revision and left the real fault")
            .isEqualTo("Write a letter into the space where someone is missing");
    }

    @Test
    void the_mend_she_asks_for_carries_what_actually_failed() throws Exception {
        Files.writeString(items.resolve("absent_letter.js"), tool("absent_letter",
            "[ { label: \"Write a letter\", args: \"\" } ]", """
              if (!params.args) { return { ok: false, summary: "Type it out." }; }
              return { ok: true, summary: "saved" };"""));
        ScriptedItemLoader.get().setSearchDirs(List.of(items));
        ScriptedItemLoader.get().reloadAll();
        BrokenItems.failedInHand("absent_letter", "", "Type it out.");
        var brief = ItemRevision.instructionFor("absent_letter", "Fix the catch blocks where e.message is read.").orElseThrow();
        assertThat(brief).contains("Fix the catch blocks where e.message is read.")
            .contains("WHAT ACTUALLY HAPPENS WHEN SHE USES IT — mend this first, it is why she asked:\nwhen she used it, it failed: Type it out.");
    }
}
