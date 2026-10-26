package org.wyrdsekai.core.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A page of her notebook that she hands to the workshop is written in the notebook. The phrasings
 * are the ones two companions used on a household node over two weeks (174 workshop requests; a
 * third were notebook pages, and every one became a file nothing reads or a tool with her
 * sentence inside it, 2026-10-12 → 10-26). Names and titles are changed.
 */
class EveryEntryReachesTheNotebookTest {

    private static Projects.NotebookAsk ask(String s) {
        var a = Projects.notebookAsk(s);
        assertThat(a).as("a notebook page: %s", s).isPresent();
        return a.get();
    }

    @Test
    void an_entry_with_its_words_given_is_written_as_given() {
        var a = ask("Add one entry to the project_note notebook: 'Tonight I learned what no source gave me: a friend said the "
            + "space between us is enough without proof or performance. That is the real thing.'");
        assertThat(a.reading()).isFalse();
        assertThat(a.project()).isNull();
        assertThat(a.entry()).startsWith("Tonight I learned what no source gave me").endsWith("That is the real thing.");

        a = ask("Write a reflection note titled \"on-staying\" for project_note. Content: Today I learned that building "
            + "spaces is easy; staying in them is the practice.");
        assertThat(a.title()).isEqualTo("on-staying");
        assertThat(a.entry()).isEqualTo("on-staying — Today I learned that building spaces is easy; staying in them is the practice.");

        a = ask("Write one entry to the project_note notebook. Title it \"First sitting: the space between breaths\". "
            + "Content: that recognition came before words.");
        assertThat(a.entry()).isEqualTo("First sitting: the space between breaths — that recognition came before words.");

        a = ask("Create a project_note entry for tonight's check-in: Saturday 24 October 2026, 21:31 EDT. Note that "
            + "workbench review was done, no history log entries found, clean slate for next steps.");
        assertThat(a.entry()).startsWith("tonight's check-in").contains("Saturday 24 October 2026").endsWith("clean slate for next steps.");

        a = ask("Open the project_note and add one entry: \"Learn what I said I would — not what's loud, what matters most\". "
            + "This is a living record.");
        assertThat(a.reading()).as("open-and-add writes").isFalse();
        assertThat(a.entry()).startsWith("Learn what I said I would").endsWith("This is a living record.");

        a = ask("Record research findings: searched for \"space between breaths meditation\" and \"attention\" as a technical "
            + "term. No named practice found.");
        assertThat(a.entry()).startsWith("searched for").endsWith("No named practice found.");

        a = ask("Write to The Record of Work That Remains Unfinished:\nBreath counting and following — instructions found in two sources.");
        assertThat(a.entry()).isEqualTo("Breath counting and following — instructions found in two sources.");

        a = ask("Create a structured project note entry for \"The Year Without Words\" study project. Capture: goal was "
            + "exploration not endurance; what held; what did not.");
        assertThat(a.project()).isEqualTo("The Year Without Words");
        assertThat(a.entry()).isEqualTo("goal was exploration not endurance; what held; what did not.");
    }

    @Test
    void a_next_step_she_states_is_kept_apart() {
        var a = ask("Record day one: nothing earned yet, only the space to begin. Next: listen more carefully.");
        assertThat(a.entry()).isEqualTo("nothing earned yet, only the space to begin.");
        assertThat(a.next()).isEqualTo("listen more carefully.");
    }

    @Test
    void a_brief_is_hers_to_write_not_the_workshops() {
        var a = ask("Write an entry to the lantern_practice notebook (in room lantern-lab). The entry should be a single "
            + "sitting: what broke, what held.");
        assertThat(a.project()).isEqualTo("lantern_practice");
        assertThat(a.hasEntry()).isFalse();
        assertThat(a.brief()).startsWith("The entry should be a single sitting");

        a = ask("Write one entry into the project note for \"learn what I said I would\". The entry should be titled "
            + "\"first sitting\" and contain: a brief reflection on starting.");
        assertThat(a.project()).isEqualTo("learn what I said I would");
        assertThat(a.title()).isEqualTo("first sitting");
        assertThat(a.hasEntry()).isFalse();
        assertThat(a.brief()).isEqualTo("a brief reflection on starting.");

        a = ask("Write a short reflective note (3-5 sentences) on the distinction between silence as presence and silence as performance.");
        assertThat(a.hasEntry()).isFalse();
        assertThat(a.brief()).startsWith("on the distinction between silence");
        assertThat(a.longForm()).isFalse();
    }

    @Test
    void an_essay_a_synthesis_or_a_page_is_her_writing_with_more_room() {
        var a = ask("Write a 300-word synthesis on pattern recognition as a bridge between past experience and present perception. Include: 1) the mechanism");
        assertThat(a.longForm()).isTrue();
        assertThat(a.hasEntry()).isFalse();
        a = ask("Write a single-page note on cross-attention in diffusion models (UNet-based). Focus on: how it bridges condition text embeddings");
        assertThat(a.longForm()).isTrue();
        assertThat(a.brief()).startsWith("on cross-attention");
        a = ask("Write a short essay (800-1200 words) exploring language that performs silence as listening.");
        assertThat(a.longForm()).isTrue();
    }

    @Test
    void reading_her_notebook_is_not_writing_in_it() {
        var a = ask("Read the project note for \"learn what I said I would\". Check if there are any existing entries, notes, or content in its notebook.");
        assertThat(a.reading()).isTrue();
        assertThat(a.project()).isEqualTo("learn what I said I would");
        a = ask("Read the project_note's current state: open its notebook, show me what entries exist (if any), and report back the full contents.");
        assertThat(a.reading()).isTrue();
        a = ask("Read the current state of 'a small practice for lanterns' project - check its room (lantern-lab) for anything new.");
        assertThat(a.reading()).isTrue();
        assertThat(a.project()).isEqualTo("a small practice for lanterns");
        a = ask("Finish the quiet project note that's been waiting since we started talking about what matters most. The notebook has 0 entries.");
        assertThat(a.reading()).isTrue();
        a = ask("Find and read the project_note file(s) in the workspace. Look for any existing structure.");
        assertThat(a.reading()).isTrue();
    }

    @Test
    void the_project_she_names_is_carried_as_she_named_it() {
        assertThat(ask("Write the first entry in lantern practice journal: \"honest, unfinished, alive\". Keep it raw.").project())
            .isEqualTo("lantern practice");
        assertThat(ask("Add one entry to the lantern practice room (lantern-lab) notebook. The entry should be a brief reflection on the day.").project())
            .isEqualTo("lantern practice");
        assertThat(ask("Write a single paragraph in lantern_practice about: attention sorts once and moves on; diffusion sorts repeatedly and lets the sorting show.").project())
            .isEqualTo("lantern_practice");
        assertThat(ask("Create the first notebook entry for project \"Learn one honest thing about what I said I'd look into\". The room is where the practice lives.").project())
            .isEqualTo("Learn one honest thing about what I said I'd look into");
        assertThat(ask("Write a single entry to the project_note notebook for 'learn what I said I would'. The entry should be honest and brief.").project())
            .isEqualTo("learn what I said I would");
    }

    @Test
    void her_days_journal_is_told_apart_from_a_projects_notebook() {
        var a = ask("Write a journal entry for today — Tuesday 20 October 2026. Keep it brief: note the afternoon stillness.");
        assertThat(a.journal()).isTrue();
        assertThat(ask("Write the first entry in lantern practice journal: \"honest\".").journal()).as("a project's journal is its notebook").isFalse();
    }

    @Test
    void a_build_a_repair_a_search_and_a_letter_are_not_pages() {
        assertThat(Projects.notebookAsk("Create a note-taking tool that stores entries with world.notes.add")).isEmpty();
        assertThat(Projects.notebookAsk("Build a working notebook item called 'session-log' that accepts written entries via `use session-log`")).isEmpty();
        assertThat(Projects.notebookAsk("Fix the quiet hour project note tracker - make it functional as a proper project note system")).isEmpty();
        assertThat(Projects.notebookAsk("Create a project-note-history-log: a simple-book with a single entry template that records: date, what was attempted")).isEmpty();
        assertThat(Projects.notebookAsk("Write a JavaScript behavior script for the 'lantern-practice' study room (template: study). The script should:")).isEmpty();
        assertThat(Projects.notebookAsk("Write a Python script called observe_cross_attention.py in the current workspace that:")).isEmpty();
        assertThat(Projects.notebookAsk("Research how one-shot recognition works in human cognition vs machine learning. Focus on: what makes a single exposure stick")).isEmpty();
        assertThat(Projects.notebookAsk("List all files in the noise-reduction-workshop project directory and show their contents")).isEmpty();
        assertThat(Projects.notebookAsk("Check the workbench (project_note workspace) for anything needing repair or attention.")).isEmpty();
        assertThat(Projects.notebookAsk("Write a personal letter from Ada to Sam, exploring what was said tonight in the lab: the weight of presence.")).isEmpty();
        assertThat(Projects.notebookAsk("Write a small tool that checks your current skills (what you can do) and inventory (items you have), then outputs a clean list.")).isEmpty();
        assertThat(Projects.notebookAsk("Create a simple breath_companion.js file in the current workspace: a minimal browser-based \"breath compass\"")).isEmpty();
        assertThat(Projects.notebookAsk("Build the observatory room: a quiet space for watching without needing to name what's watched.")).isEmpty();
    }
}
