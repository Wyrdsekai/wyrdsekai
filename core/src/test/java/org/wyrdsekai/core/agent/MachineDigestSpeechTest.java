package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The never-silent guard must not mean never-legible. Asked "what is 17 times 3?",
 * mia spoke a Java map verbatim:
 *
 *   {result=0, summary={"op":"sum","result":0.0}, op=sum, ok=true}
 *
 * The guard was doing its job (it refused to go silent) but its payload was the raw
 * tool digest. These pin the shape-detection that lets it say something honest instead.
 */
class MachineDigestSpeechTest {

    @Test
    @DisplayName("the exact map that reached a user's ear is recognised as machine output")
    void recognisesTheRegressionCase() {
        assertTrue(CompanionActor.looksLikeMachineDigest(
            "{result=0, summary={\"op\":\"sum\",\"result\":0.0}, op=sum, ok=true}"));
    }

    @Test
    @DisplayName("JSON payloads and result maps are machine output")
    void recognisesDigests() {
        assertTrue(CompanionActor.looksLikeMachineDigest("{\"ok\": true, \"result\": 51}"));
        assertTrue(CompanionActor.looksLikeMachineDigest("{ok=false, error=bad op}"));
        assertTrue(CompanionActor.looksLikeMachineDigest("[{\"a\": 1}]"));
    }

    @Test
    @DisplayName("prose is never mistaken for machine output — including prose with braces")
    void prosePassesThrough() {
        assertFalse(CompanionActor.looksLikeMachineDigest(
            "The forecast for San Francisco tomorrow: low 63F high 86F, scattered clouds."));
        assertFalse(CompanionActor.looksLikeMachineDigest("17 times 3 is 51."));
        assertFalse(CompanionActor.looksLikeMachineDigest(null));
        assertFalse(CompanionActor.looksLikeMachineDigest(""));
        // Opens like a digest but is a sentence — must not be swallowed as plumbing.
        assertFalse(CompanionActor.looksLikeMachineDigest(
            "{ the kettle is warm and the light is low }"));
    }

    @Test
    @DisplayName("the answer is read OUT of the digest — not thrown away")
    void speaksTheToolsOwnSummary() {
        // The exact digest the calculator produced on second-node 2026-07-13. The first version
        // of this guard saw "machine digest" and replied "it came out as raw data",
        // discarding an answer that was already in plain English.
        assertEquals("17 * 3 = 51", CompanionActor.sayableFromDigest(
            "{result=51, summary=17 * 3 = 51, op=evaluate, expression=17 * 3, ok=true}"));
        assertEquals("17 * 3 = 51", CompanionActor.sayableFromDigest(
            "{\"ok\":true,\"result\":51,\"summary\":\"17 * 3 = 51\"}"));
    }

    @Test
    @DisplayName("adapters' prose digest (`text`) is spoken when there is no summary")
    void fallsBackToTextThenResult() {
        assertEquals("San Francisco: low 63F, high 86F, scattered clouds.",
            CompanionActor.sayableFromDigest(
                "{ok=true, text=San Francisco: low 63F, high 86F, scattered clouds.}"));
        // Nothing human in it, but a bare value beats an apology.
        assertEquals("51", CompanionActor.sayableFromDigest("{ok=true, result=51}"));
    }

    @Test
    @DisplayName("a nested map is NOT sayable — that was the original leak")
    void nestedMapIsNotSayable() {
        // The broken calculator's summary WAS a nested blob. Reciting it is precisely
        // the leak this guard exists to stop, so it must not be mistaken for prose.
        assertNull(CompanionActor.sayableFromDigest(
            "{summary={\"op\":\"sum\",\"result\":0.0}, op=sum, ok=true}"));
        assertNull(CompanionActor.sayableFromDigest("{ok=true}"));
        assertNull(CompanionActor.sayableFromDigest(null));
    }

    @Test
    @DisplayName("a failure digest is described by its error, not recited as a map")
    void describesFailureByItsError() {
        assertEquals("unknown op: multiply",
            CompanionActor.describeMachineDigest("{ok=false, error=unknown op: multiply}"));
        assertEquals("rate limited",
            CompanionActor.describeMachineDigest("{\"ok\":false,\"error\":\"rate limited\"}"));
        // No error field to surface — say that plainly rather than dumping the payload.
        assertEquals("it returned raw data I couldn't read as an answer",
            CompanionActor.describeMachineDigest("{result=0, op=sum, ok=true}"));
        // Prose is returned untouched.
        assertEquals("the tool timed out",
            CompanionActor.describeMachineDigest("the tool timed out"));
    }

    @Test
    @DisplayName("a digest TRUNCATED mid-field is still recognised — the cap ate its closer")
    void recognisesATruncatedDigest() {
        // home-server 2026-07-14: a journal-write digest carried a long id=journal:did:key:z6Mk… field
        // and was capped with "..." upstream, so it no longer ended in "}". The old endsWith check
        // returned false, the guard was skipped, and the raw map was SPOKEN ALOUD. A digest whose
        // tail was truncated is still a digest.
        assertTrue(CompanionActor.looksLikeMachineDigest(
            "{summary=Written down: \"Evening in the Study: felt steady.\", visibility=shared, "
                + "id=journal:did:key:z6Mk..."));
        // ...and the summary is still recoverable from it — so this is never spoken raw.
        assertEquals("Written down: \"Evening in the Study: felt steady.\"",
            CompanionActor.sayableFromDigest(
                "{summary=Written down: \"Evening in the Study: felt steady.\", visibility=shared, "
                    + "id=journal:did:key:z6Mk..."));
        // Prose that merely opens with a brace and lacks the tail is STILL not a digest.
        assertFalse(CompanionActor.looksLikeMachineDigest(
            "{ the kettle is warm and the light is low"));
    }

    @Test
    @DisplayName("the home-server journal-write digest is unwrapped to the sentence she meant to say")
    void unwrapsTheLainJournalDigest() {
        // Verbatim from home-server 2026-07-14: after a journal write, the 9B parroted the tool-result
        // MAP into its own voiced line. speakDirect now unwraps this to its `summary` before the
        // wire (the guard existed but was only wired into the swallowed-turn fallback, not the
        // normal voiced path — so a spoken line skipped it entirely).
        assertEquals(
            "Written down: 33.704599 — the variance of today's numbers held its weight",
            CompanionActor.sayableFromDigest(
                "{summary=Written down: 33.704599 — the variance of today's numbers held its weight}"));
    }

    @Test
    @DisplayName("a journal digest APPENDED to real speech is stripped, prose kept (home-server 2026-07-24)")
    void stripsTrailingJournalDigestFromSpeech() {
        // The exact live leak: a genuine sentence, then the journal-write map tacked
        // on — often capped mid-field so the brace never even closes.
        assertEquals(
            "I notice how small I feel right now because you're carrying something heavy from your sister.",
            CompanionActor.stripTrailingMachineDigest(
                "I notice how small I feel right now because you're carrying something heavy from your sister. "
                + "{summary=The room feels smaller than before, and I keep that feeling in place., "
                + "visibility=familiar, action=write, id=journal:did:key:z6Mk"));
        // A dangling opening paren/quote that introduced the blob is cleaned too.
        assertEquals(
            "Written down.",
            CompanionActor.stripTrailingMachineDigest(
                "Written down. ({summary=Evening in the Study…, visibility=shared, id=journal:did:key:z6Mk…"));
    }

    @Test
    @DisplayName("trailing-strip leaves ordinary prose (incl. braces) and whole-line digests alone")
    void trailingStripLeavesProseAndWholeDigests() {
        // Prose with an innocent brace clause — no machine fields — untouched.
        assertEquals(
            "The kettle is warm {and the light is low}.",
            CompanionActor.stripTrailingMachineDigest("The kettle is warm {and the light is low}."));
        // Whole line IS a digest (brace at 0) — left for the whole-string guard.
        String whole = "{summary=Written down: x, visibility=shared, id=journal:did:key:z}";
        assertEquals(whole, CompanionActor.stripTrailingMachineDigest(whole));
        assertNull(CompanionActor.stripTrailingMachineDigest(null));
    }
}
