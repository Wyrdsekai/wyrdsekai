package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.MemoryNode;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sleep's result is made from her manifest as it was when the sleep began. A note the language
 * healer put into the household's language while the sleep ran came out of the sleep in the old
 * language again, and the next pass healed it again, every five minutes (a household, 2026-10-22).
 * The carry into the sleep's result now brings such notes along.
 */
class AHealedNoteOutlivesTheSleepsResultTest {

    private static final AgentProfile PROFILE = new AgentProfile("Wren", "companion-wren-test", "agent",
        "A companion", "You are Wren.", 4096, 256, 0.7);
    private static final String ES = "El peso que llevaba ya no es el de antes; ahora siento algo distinto en mí.";
    private static final String EN = "The weight I carried is not what it was; something different settles in me now.";

    private static MemoryNode node(String id, String text, String originLocale) {
        return new MemoryNode(id, text, List.of(), 0.5f, 0.0f, false, "none", Instant.now(), 0, originLocale, null);
    }

    private static SoulManifest manifest(List<MemoryNode> nodes) {
        return SoulManifest.forge("did:key:z6MkTestCarrySoul", "z6MkTestCarrySoul", List.of(), null, 1,
            PROFILE, "I am Wren.", List.of(), 3, "", GenomeProfile.defaults(), List.of(),
            new CompactedMemory(nodes, List.of(), Map.of()), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    @Test
    @DisplayName("a note healed while the sleep ran comes along into the sleep's result; the rest is the result's")
    void healedNotesComeAlong() {
        var sleepStart = Instant.now().minusSeconds(60);
        var forged = manifest(List.of(node("a", ES, "es"), node("b", "Otra nota en español que nadie tocó.", "es"), node("c", "A plain English note.", "en")));
        var hers = manifest(List.of(node("a", EN, "en"), node("b", "Otra nota en español que nadie tocó.", "es"), node("c", "A plain English note.", "en")));
        var carried = CompanionActor.carryWovenDuringSleep(forged, hers, sleepStart, "en");
        assertThat(carried).isNotSameAs(forged);
        assertThat(carried.memory().nodes()).extracting(MemoryNode::id).containsExactly("a", "b", "c");
        assertThat(carried.memory().nodes().get(0).content()).isEqualTo(EN);
        assertThat(carried.memory().nodes().get(0).originLocale()).isEqualTo("en");
        assertThat(carried.memory().nodes().get(1).content()).startsWith("Otra nota");
    }

    @Test
    @DisplayName("nothing to carry: the result is returned as it is")
    void nothingToCarryIsTheResultItself() {
        var sleepStart = Instant.now().minusSeconds(60);
        var forged = manifest(List.of(node("a", EN, "en"), node("b", "Otra nota en español que nadie tocó.", "es")));
        assertThat(CompanionActor.carryWovenDuringSleep(forged, forged, sleepStart, "en")).isSameAs(forged);
        // A note the result already healed the same way, and a note whose copy she never marked: left alone.
        var hers = manifest(List.of(node("a", EN, "en"), node("b", "Otra nota en español que nadie tocó.", "es")));
        assertThat(CompanionActor.carryWovenDuringSleep(forged, hers, sleepStart, "en")).isSameAs(forged);
        // The result's own prunes stand: a note she still holds that the result dropped is not put back.
        var pruned = manifest(List.of(node("b", "Otra nota en español que nadie tocó.", "es")));
        var withHealed = manifest(List.of(node("a", EN, "en"), node("b", "Otra nota en español que nadie tocó.", "es")));
        assertThat(CompanionActor.carryWovenDuringSleep(pruned, withHealed, sleepStart, "en")).isSameAs(pruned);
        // No household language known: only the fragments carry, as before.
        var forgedEs = manifest(List.of(node("a", ES, "es")));
        var hersEn = manifest(List.of(node("a", EN, "en")));
        assertThat(CompanionActor.carryWovenDuringSleep(forgedEs, hersEn, sleepStart)).isSameAs(forgedEs);
    }

    @Test
    @DisplayName("the forge's own words are never overridden: a differing note she does not mark as the household's stays the result's")
    void onlyTheHealersMarkCarries() {
        var sleepStart = Instant.now().minusSeconds(60);
        var forged = manifest(List.of(node("a", "What the night made of it.", "en")));
        var hers = manifest(List.of(node("a", "What she had before the night.", "en")));
        assertThat(CompanionActor.carryWovenDuringSleep(forged, hers, sleepStart, "en"))
            .as("both copies are the household's language: nothing marks hers as a healing").isSameAs(forged);
    }
}
