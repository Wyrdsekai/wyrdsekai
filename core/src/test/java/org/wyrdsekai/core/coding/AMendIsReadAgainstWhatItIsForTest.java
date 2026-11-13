package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trial in a stand-in world tells whether a tool runs, answers and gets back what it kept; it
 * cannot tell whether that is the tool's job. Each mend of a companion's letter tool passed it the
 * shortest way, and the last wrote a letter of its own and kept it as hers when she gave it no
 * words; she was told twice it was mended (household node, 2026-11-12). A mended copy is now read
 * against what the tool is for before it may take the place of hers.
 */
class AMendIsReadAgainstWhatItIsForTest {

    @TempDir Path items;

    @AfterEach
    void tearDown() {
        PurposeJudge.use(null);
        ItemContractRepair.setEscalation(null);
    }

    private static String letters(String writeArgs, String whenNothing) {
        return """
            exports.manifest = {
              name: "absent_letter",
              version: "1.2.1",
              description: "Write a letter into the space where someone is missing",
              author: "did:wyrd:codezaiku",
              capabilities: ["notes.add", "notes.list", "notes.delete"],
              embodiment: { silent: false, emits: ["body_language"], descriptor_template: "{actor} writes a letter" },
              commands: [ { label: "Write a letter", args: "%s" }, { label: "Show saved letters", args: "list" } ]
            };
            function invoke(params) {
              if (params.args === "list") {
                var saved = world.notes.list("absent-letter") || [];
                if (saved.length === 0) { return { ok: true, summary: "No letters saved yet." }; }
                return { ok: true, summary: saved.map(function (e) { return e.content; }).join("\\n") };
              }
              var text = params.args || "";
              if (text === "") { %s }
              world.notes.add(text, ["absent-letter"]);
              return { ok: true, summary: "Letter saved." };
            }
            """.formatted(writeArgs, whenNothing);
    }

    private static final String INVENTS = letters("", "text = \"Dear you, I write this into the space where you are missing. The words stay.\";");
    private static final String HERS = letters("<the letter>", "return { ok: false, summary: \"Write the letter after the command.\" };");

    /** A reader that sees what the tool kept: words it wrote itself are not hers. */
    private static final PurposeJudge.Model READER = (instructions, question) -> question.contains("kept \"Dear you")
        ? "Here is my reading: {\"does_its_job\": true, \"makes_up_her_words\": true, \"what_it_does\": \"it writes a letter of its own\", "
            + "\"why_not\": \"given no words, it writes a letter of its own and keeps it as hers.\"}"
        : "{\"does_its_job\": true, \"makes_up_her_words\": false, \"what_it_does\": \"it keeps the letter I write and shows my letters back to me.\", \"why_not\": \"\"}";

    @Test
    void the_reading_sees_a_letter_she_did_not_write() {
        var uses = PurposeJudge.uses("absent_letter", INVENTS, ItemManifestParser.parse(INVENTS), List.of());
        var question = PurposeJudge.question("Write a letter into the space where someone is missing", ItemManifestParser.parse(INVENTS), uses);
        assertThat(question).contains("- Write a letter (args: \"\")")
            .contains("- used with \"\": answered ok, saying \"Letter saved.\"; kept \"Dear you, I write this into the space where you are missing. The words stay.\"")
            .contains("- used with \"list\": answered ok, saying \"Dear you, I write this");
        assertThat(PurposeJudge.read("I think: {\"does_its_job\": false, \"makes_up_her_words\": false, \"what_it_does\": \"it\", \"why_not\": \"x\"} done"))
            .hasValueSatisfying(v -> assertThat(v.whole()).isFalse());
        assertThat(PurposeJudge.read("no json here")).isEmpty();
    }

    @Test
    void a_copy_that_passes_the_trial_but_writes_her_words_does_not_replace_hers() throws Exception {
        var file = items.resolve("absent_letter.js");
        Files.writeString(file, letters("", "return { ok: false, summary: \"Write a letter to someone you miss. Type it out.\" };"));
        PurposeJudge.use(READER);
        var prompts = new ArrayList<String>();
        var rounds = new AtomicInteger();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            try {
                // The workshop's shortest way first; told why that is not a mend, the right one.
                Files.writeString(workspace.resolve("absent_letter.js"), rounds.incrementAndGet() == 1 ? INVENTS : HERS);
                return true;
            } catch (Exception e) { return false; }
        });
        var r = ItemContractRepair.repairPlaced(file);
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("used as she uses it, it does not do what it was made for: given no words, it writes a letter of its own and keeps it as hers.");
        assertThat(r.fixed()).isTrue();
        assertThat(Files.readString(file)).contains("<the letter>").doesNotContain("Dear you");
        assertThat(ItemContractRepair.whatTheMendDid(r)).as("what she is told is what was read")
            .isEqualTo("it keeps the letter I write and shows my letters back to me");
    }

    @Test
    void when_the_last_round_still_writes_her_words_hers_is_left_and_she_is_told_why() throws Exception {
        var file = items.resolve("absent_letter.js");
        var original = letters("", "return { ok: false, summary: \"Type it out.\" };");
        Files.writeString(file, original);
        PurposeJudge.use(READER);
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try { Files.writeString(workspace.resolve("absent_letter.js"), INVENTS); return true; } catch (Exception e) { return false; }
        });
        var r = ItemContractRepair.repairPlaced(file);
        assertThat(r.fixed()).isFalse();
        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(ItemContractRepair.whyNotMended(r)).isEqualTo("given no words, it writes a letter of its own and keeps it as hers");
    }

    @Test
    void a_tool_whole_by_the_trial_that_writes_her_words_is_taken_to_the_bench() throws Exception {
        var file = items.resolve("absent_letter.js");
        Files.writeString(file, INVENTS);
        PurposeJudge.use(READER);
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try { Files.writeString(workspace.resolve("absent_letter.js"), HERS); return true; } catch (Exception e) { return false; }
        });
        var r = ItemContractRepair.repairPlaced(file);
        assertThat(r.before()).singleElement().asString().startsWith("used as she uses it, it does not do what it was made for:");
        assertThat(r.fixed()).isTrue();
        assertThat(Files.readString(file)).contains("<the letter>");
    }

    @Test
    void no_reading_no_replacing() throws Exception {
        var file = items.resolve("absent_letter.js");
        var original = letters("", "return { ok: false, summary: \"Type it out.\" };");
        Files.writeString(file, original);
        PurposeJudge.use((instructions, question) -> { throw new IllegalStateException("the brain did not answer"); });
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try { Files.writeString(workspace.resolve("absent_letter.js"), HERS); return true; } catch (Exception e) { return false; }
        });
        var r = ItemContractRepair.repairPlaced(file);
        assertThat(r.fixed()).isFalse();
        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(ItemContractRepair.whyNotMended(r)).isEqualTo("it runs now, but what it does could not be read (the thinking brain did not answer)");
    }
}
