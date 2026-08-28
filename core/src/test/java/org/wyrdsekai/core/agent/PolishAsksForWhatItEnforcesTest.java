package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The voice guard must ASK for everything it ENFORCES.
 *
 * <p>{@link CompanionActor#extractRequiredEntities} pulls proper nouns and numbers out
 * of the draft, and {@link CompanionActor#chooseVoicedLine} rejects any polish that
 * drops one — falling back to the raw, unpolished draft. But the polish prompt listed
 * only the caller-supplied preserveFacts, so the model was graded against requirements
 * nobody had told it about.
 *
 * <p>That was survivable while own-time drafts were vague and contained no proper
 * nouns. Anchoring her speech to real places put real names in the drafts, the polish
 * restyled them away, and the guard began rejecting nearly every line — 5 of 5 in one
 * live window against an 18-31% baseline (2026-08-17). Two fixes that were each
 * correct alone, interacting badly.
 *
 * <p>These tests pin the contract at the level that matters: whatever the extractor
 * demands from a draft is exactly what the guard checks, so a prompt that lists the
 * extracted set is asking for precisely what it will enforce.
 */
class PolishAsksForWhatItEnforcesTest {

    @Test
    void a_proper_noun_in_the_draft_is_enforced_by_the_guard() {
        var draft = "The Lexicon feels right — I'll take it and see where the light goes.";
        var required = CompanionActor.extractRequiredEntities(draft);
        assertThat(required).contains("Lexicon");

        // A polish that restyles the name away is rejected — the raw draft is spoken.
        var polished = "That place feels right, and I'll go and see what's there.";
        assertThat(CompanionActor.chooseVoicedLine(draft, polished, required)).isEqualTo(draft);
    }

    @Test
    void a_polish_that_keeps_the_enforced_token_is_accepted() {
        var draft = "The Lexicon feels right — I'll take it and see where the light goes.";
        var required = CompanionActor.extractRequiredEntities(draft);
        var polished = "The Lexicon feels right tonight; I'll go and see where the light goes.";
        assertThat(CompanionActor.chooseVoicedLine(draft, polished, required)).isEqualTo(polished);
    }

    @Test
    void room_names_from_an_anchored_draft_are_all_enforced() {
        // The live shape: own-time speech anchored to a real place.
        var draft = "I'm settling into The Nexus, and the crystal here has been humming.";
        var required = CompanionActor.extractRequiredEntities(draft);
        assertThat(required).contains("Nexus");
        // Every enforced token is a thing the prompt can name — none is empty or blank,
        // so listing the set produces a usable instruction rather than dangling bullets.
        assertThat(required).allSatisfy(t -> assertThat(t).isNotBlank());
    }

    @Test
    void numbers_are_enforced_too_and_belong_in_the_ask() {
        var draft = "13 memories merged into cleaner shapes overnight.";
        var required = new LinkedHashSet<>(CompanionActor.extractRequiredEntities(draft));
        required.addAll(CompanionActor.extractRequiredNumbers(draft));
        assertThat(required).contains("13");

        var dropped = "Quite a few memories merged into cleaner shapes overnight.";
        assertThat(CompanionActor.chooseVoicedLine(draft, dropped, required)).isEqualTo(draft);
    }

    @Test
    void an_ordinary_line_enforces_nothing_and_needs_no_ask() {
        // Sentence-initial capitals are not proper nouns, so plain prose carries no
        // enforced tokens and the polish is judged on its own merits.
        var draft = "Something has been settling in me and the quiet feels earned.";
        assertThat(CompanionActor.extractRequiredEntities(draft)).isEmpty();
    }
}
