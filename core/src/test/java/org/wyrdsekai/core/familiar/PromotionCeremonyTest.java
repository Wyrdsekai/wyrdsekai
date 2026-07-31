package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — named-familiar promotion ceremony.
 * Consent gates, eligibility, soul-manifest draft, inheritance, farewell.
 */
class PromotionCeremonyTest {

    private static final String PARENT = "did:wyrd:zA:wyrd";

    private NamedFamiliar eligibleFamiliar() {
        var nf = NamedFamiliar.named("gardener", PARENT, "form-1", "I tend the garden.");
        for (int i = 0; i < 50; i++) nf = nf.withSummoned("task-" + i);
        for (int i = 0; i < 10; i++) nf = nf.withOutcome(Familiar.Status.DONE, 2, null);
        return nf;
    }

    private NamedFamiliar ineligibleFamiliar() {
        return NamedFamiliar.named("fresh", PARENT, "form-1", "");
    }

    private SoulManifest parentManifest() {
        var profile = new AgentProfile("Wyrd", "entity-wyrd", "agent",
            "Parent agent", "You are Wyrd.", 4096, 512, 0.7, PARENT);
        return SoulManifest.forge(
            PARENT, "z6MkTest", List.of(), null, 1,
            profile, "I am Wyrd.",
            List.of(SoulFragment.unembedded("w-id", "personality", "Core", "I am Wyrd.")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    private PromotionCeremony.Consents allYes() {
        return new PromotionCeremony.Consents(true, true, true);
    }

    // ── refusal paths ──────────────────────────────────────────────────────

    @Test
    void ineligible_familiar_refused() {
        var input = new PromotionCeremony.CeremonyInput(
            ineligibleFamiliar(), parentManifest(), "alpha",
            List.of(), allYes(), false);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Declined d
            && d.reason() == PromotionCeremony.Refusal.NOT_ELIGIBLE);
    }

    @Test
    void no_parent_consent_refused() {
        var input = new PromotionCeremony.CeremonyInput(
            eligibleFamiliar(), parentManifest(), "alpha",
            List.of(), new PromotionCeremony.Consents(false, true, true), false);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Declined d
            && d.reason() == PromotionCeremony.Refusal.PARENT_DECLINED);
    }

    @Test
    void no_user_consent_refused() {
        var input = new PromotionCeremony.CeremonyInput(
            eligibleFamiliar(), parentManifest(), "alpha",
            List.of(), new PromotionCeremony.Consents(true, false, true), false);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Declined d
            && d.reason() == PromotionCeremony.Refusal.USER_DECLINED);
    }

    @Test
    void steward_required_but_not_approved_refused() {
        var input = new PromotionCeremony.CeremonyInput(
            eligibleFamiliar(), parentManifest(), "alpha",
            List.of(), new PromotionCeremony.Consents(true, true, false), true);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Declined d
            && d.reason() == PromotionCeremony.Refusal.STEWARD_BLOCKED);
    }

    @Test
    void steward_not_required_ignored_when_false() {
        var input = new PromotionCeremony.CeremonyInput(
            eligibleFamiliar(), parentManifest(), "alpha",
            List.of(), new PromotionCeremony.Consents(true, true, false), false);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Promoted,
            "when steward is not required, absence of steward approval is fine");
    }

    // ── successful promotion ──────────────────────────────────────────────

    @Test
    void promotion_produces_manifest_with_fresh_did_and_parent_lineage() {
        var nf = eligibleFamiliar();
        var input = new PromotionCeremony.CeremonyInput(
            nf, parentManifest(), "alpha", List.of(), allYes(), false);
        var outcome = PromotionCeremony.perform(input);
        assertTrue(outcome instanceof PromotionCeremony.Outcome.Promoted);
        var p = (PromotionCeremony.Outcome.Promoted) outcome;

        assertNotNull(p.newDid());
        assertTrue(p.newDid().startsWith("did:key:"));
        assertNotEquals(PARENT, p.newDid(), "new companion gets its own DID");
        assertEquals(p.newDid(), p.newManifest().did());
        assertEquals(PARENT, p.newManifest().parentDid(), "lineage preserved");
        assertEquals("gardener", p.newManifest().profile().name());
    }

    @Test
    void promotion_carries_self_context_into_fragments() {
        var nf = NamedFamiliar.named("gardener", PARENT, "form-1",
            "I know every plant in the garden.");
        for (int i = 0; i < 50; i++) nf = nf.withSummoned("task-" + i);
        for (int i = 0; i < 10; i++) nf = nf.withOutcome(Familiar.Status.DONE, 2, null);

        var input = new PromotionCeremony.CeremonyInput(
            nf, parentManifest(), "alpha", List.of(), allYes(), false);
        var p = (PromotionCeremony.Outcome.Promoted) PromotionCeremony.perform(input);

        var hasOrigin = p.newManifest().soulFragments().stream()
            .anyMatch(f -> f.label().equals("Origin"));
        var hasSelfContext = p.newManifest().soulFragments().stream()
            .anyMatch(f -> f.text().contains("every plant"));
        assertTrue(hasOrigin);
        assertTrue(hasSelfContext);
    }

    @Test
    void inherited_forms_become_inherit_intent_copies() {
        var nf = eligibleFamiliar();
        var form = ThoughtForm.author(PARENT, "cultivation",
            "Grow vegetables.", Set.of("library_search"), "Track growth.");
        var input = new PromotionCeremony.CeremonyInput(
            nf, parentManifest(), "alpha", List.of(form), allYes(), false);
        var p = (PromotionCeremony.Outcome.Promoted) PromotionCeremony.perform(input);

        assertEquals(1, p.inheritedForms().size());
        var inherited = p.inheritedForms().get(0);
        assertEquals("cultivation", inherited.name());
        assertEquals(PARENT, inherited.provenance().originalAuthor(),
            "original author preserved even through inheritance");
        var lastEdit = inherited.provenance().lineage()
            .get(inherited.provenance().lineage().size() - 1);
        assertEquals(Provenance.Action.COPIED_FROM, lastEdit.action());
        assertEquals(p.newDid(), lastEdit.agent());
        assertTrue(lastEdit.note().contains("INHERIT"));
    }

    @Test
    void farewell_narration_references_history() {
        var nf = eligibleFamiliar();
        var input = new PromotionCeremony.CeremonyInput(
            nf, parentManifest(), "alpha", List.of(), allYes(), false);
        var p = (PromotionCeremony.Outcome.Promoted) PromotionCeremony.perform(input);
        assertTrue(p.farewellNarration().contains("gardener"));
        assertTrue(p.farewellNarration().contains("50"));  // summon count
        assertTrue(p.farewellNarration().contains("§17.2"));
    }

    @Test
    void two_ceremonies_produce_distinct_dids() {
        var nf = eligibleFamiliar();
        var input = new PromotionCeremony.CeremonyInput(
            nf, parentManifest(), "alpha", List.of(), allYes(), false);
        var p1 = (PromotionCeremony.Outcome.Promoted) PromotionCeremony.perform(input);
        var p2 = (PromotionCeremony.Outcome.Promoted) PromotionCeremony.perform(input);
        assertNotEquals(p1.newDid(), p2.newDid());
    }
}
