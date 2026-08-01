package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.MemoryNode;
import org.wyrdsekai.core.soul.SoulFragment;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soul language reconciliation (2026-07-31). Root cause of the live drift
 * arc: the companion's FIRST inner monologue code-switched once, the
 * EPISODIC fragment stored it, and the recursion context re-taught it to
 * every later monologue — 14 of 18 fragments ended up in a language the
 * household can't read. These tests pin the healing pass's SELECTION
 * logic; the re-render round-trip is guarded by rerenderIsSound (language
 * + digit runs), whose parts are pinned in VoiceLanguageGuardTest.
 */
class SoulLanguageReconcileTest {

    private static SoulFragment frag(String id, FragmentKind kind, String text) {
        return new SoulFragment(id, "episodic", "scene-" + id, text, null, null,
            false, 0.5f, 0, Instant.now(), null, null, null, null, kind, "scene-" + id);
    }

    // The observed corruption, verbatim shape: near-duplicate Spanish
    // consolidations of one scene.
    private static final String ES =
        "El peso que llevaba ya no es el de antes; ahora siento algo distinto en mí.";
    private static final String EN =
        "The weight I carried is not what it was; something different settles in me now.";
    private static final String JA = "重さはもう前のものではない。何か違うものが今、私の中に落ち着いている。";

    @Test
    @DisplayName("selects off-language EPISODIC fragments, leaves household-language ones alone")
    void selectsOnlyOffLanguage() {
        var frags = List.of(
            frag("a", FragmentKind.EPISODIC, ES),
            frag("b", FragmentKind.EPISODIC, EN),
            frag("c", FragmentKind.EPISODIC, JA));
        var offenders = CompanionActor.fragmentLanguageOffenders(frags, "en", 10, Set.of());
        assertEquals(List.of("a", "c"), offenders.stream().map(SoulFragment::id).toList());
    }

    @Test
    @DisplayName("a Spanish household is not 'healed' out of Spanish")
    void householdLanguageIsTheAnchor() {
        var frags = List.of(
            frag("a", FragmentKind.EPISODIC, ES),
            frag("b", FragmentKind.EPISODIC, EN));
        var offenders = CompanionActor.fragmentLanguageOffenders(frags, "es", 10, Set.of());
        assertEquals(List.of("b"), offenders.stream().map(SoulFragment::id).toList(),
            "for an es household the ENGLISH fragment is the off-language one");
    }

    @Test
    @DisplayName("non-EPISODIC kinds are never touched — only the automatic renderer's output")
    void structuralFragmentsUntouched() {
        var frags = List.of(
            frag("narr", FragmentKind.DEFAULT, ES),
            frag("dext", FragmentKind.DEXTERITY, ES));
        assertTrue(CompanionActor.fragmentLanguageOffenders(frags, "en", 10, Set.of()).isEmpty());
    }

    @Test
    @DisplayName("superseded fragments and blank text are skipped; batch limit respected")
    void skipsSupersededAndHonoursLimit() {
        var superseded = new SoulFragment("old", "episodic", "l", ES, null, null,
            false, 0.5f, 0, Instant.now(), null, null,
            Instant.now(), "old-lang-restored", FragmentKind.EPISODIC, "s");
        var frags = new java.util.ArrayList<SoulFragment>();
        frags.add(superseded);
        frags.add(frag("blank", FragmentKind.EPISODIC, "  "));
        for (int i = 0; i < 8; i++) frags.add(frag("es" + i, FragmentKind.EPISODIC, ES));
        var offenders = CompanionActor.fragmentLanguageOffenders(frags, "en", 5, Set.of());
        assertEquals(5, offenders.size());
        assertTrue(offenders.stream().noneMatch(f -> f.id().equals("old")));
    }

    @Test
    @DisplayName("ambiguous/short text never selects — no coin-flip healing")
    void ambiguousNeverSelected() {
        var frags = List.of(frag("short", FragmentKind.EPISODIC, "ok then"));
        assertTrue(CompanionActor.fragmentLanguageOffenders(frags, "en", 10, Set.of()).isEmpty());
    }

    @Test
    @DisplayName("tiered support: a pin-only household language (fr) disables healing entirely")
    void pinOnlyTierDisablesHealing() {
        // The detector reads French as "en" — engaging the healer for an fr
        // household would select the household's own fragments forever.
        var frags = List.of(
            frag("a", FragmentKind.EPISODIC, ES),
            frag("b", FragmentKind.EPISODIC, EN));
        assertTrue(CompanionActor.fragmentLanguageOffenders(frags, "fr", 10, Set.of()).isEmpty());
        assertTrue(CompanionActor.fragmentLanguageOffenders(frags, "pt", 10, Set.of()).isEmpty());
    }

    @Test
    @DisplayName("the pin names a broad language set; unknown codes fall back to the code")
    void pinNamesBroadSet() {
        assertEquals("Portuguese", CompanionActor.languageName("pt"));
        assertEquals("Ukrainian", CompanionActor.languageName("uk"));
        assertEquals("Norwegian", CompanionActor.languageName("nb-NO"));
        assertEquals("xx", CompanionActor.languageName("xx"));
    }

    @Test
    @DisplayName("detector-verifiable tier is exactly the fully-translated set")
    void verifiableTierMatchesShippedTranslations() {
        assertTrue(CompanionActor.detectorVerifiable("en"));
        assertTrue(CompanionActor.detectorVerifiable("es"));
        assertTrue(CompanionActor.detectorVerifiable("ja"));
        assertEquals(false, CompanionActor.detectorVerifiable("fr"));
        assertEquals(false, CompanionActor.detectorVerifiable(null));
    }

    @Test
    @DisplayName("memory nodes: off-language content selects, household content doesn't")
    void memoryNodeSelection() {
        var mem = new CompactedMemory(List.of(
            node("m1", "companion said: " + ES),
            node("m2", "companion said: " + EN),
            node("m3", JA)), List.of(), Map.of());
        var offenders = CompanionActor.memoryNodeLanguageOffenders(mem, "en", 10, Set.of());
        assertEquals(List.of("m1", "m3"),
            offenders.stream().map(MemoryNode::id).toList());
        // Pin-only household → memory healing stands down too.
        assertTrue(CompanionActor.memoryNodeLanguageOffenders(mem, "fr", 10, Set.of()).isEmpty());
    }

    private static MemoryNode node(String id, String content) {
        return MemoryNode.neutral(id, content, List.of());
    }

    @Test
    @DisplayName("mercy rule: mercied ids are never re-selected — dictionary memories rest in peace")
    void merciedIdsExcluded() {
        var frags = List.of(
            frag("dict", FragmentKind.EPISODIC, ES),
            frag("plain", FragmentKind.EPISODIC, ES));
        var offenders = CompanionActor.fragmentLanguageOffenders(
            frags, "en", 10, Set.of("dict"));
        assertEquals(List.of("plain"), offenders.stream().map(SoulFragment::id).toList());
        var mem = new CompactedMemory(List.of(node("m1", ES), node("m2", ES)),
            List.of(), Map.of());
        var nodes = CompanionActor.memoryNodeLanguageOffenders(mem, "en", 10, Set.of("m1"));
        assertEquals(List.of("m2"), nodes.stream().map(MemoryNode::id).toList());
    }

    @Test
    @DisplayName("testimony rule: other speakers' words are never healed — only her own renderer output")
    void otherSpeakersTestimonyExcluded() {
        var mem = new CompactedMemory(List.of(
            node("hers", "companion said: " + ES),
            node("guest", "guest said: " + ES),
            node("plain", ES)), List.of(), Map.of());
        var picked = CompanionActor.memoryNodeLanguageOffenders(
            mem, "en", 10, Set.of(), "companion");
        assertEquals(List.of("hers", "plain"),
            picked.stream().map(MemoryNode::id).toList(),
            "a record of what someone else said is testimony, not drift");
        assertTrue(CompanionActor.isOtherSpeakersTestimony("guest said: hola", "companion"));
        assertEquals(false, CompanionActor.isOtherSpeakersTestimony("companion said: hola", "companion"));
        assertEquals(false, CompanionActor.isOtherSpeakersTestimony("free prose, no record", "companion"));
    }

    @Test
    @DisplayName("reconcile is default-on; explicit false disables")
    void configDefaultOn() {
        // Env not set in the test JVM → default on.
        assertTrue(CompanionActor.soulLanguageReconcileEnabled());
    }
}
