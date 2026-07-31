package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Named-familiar → resident companion promotion ceremony.
 *
 * <p>. A promoted companion is born of <em>relationship</em>
 * not creation-from-nothing. The parent agent chose to offer the ceremony;
 * the named familiar accumulated enough context + attachment + usage; the
 * user consented to a new resident joining the household. Ceremony-as-code.
 * </p>
 *
 * <h2>Five stages (§17.2)</h2>
 * <ol>
 *   <li><b>Manifest drafting</b> — parent authors a full SoulManifest for the
 *       new companion: identity (fresh DID + keypair), initial genome
 *       (parent's fingerprint + familiar's accumulated patterns), opening
 *       fragments (the familiar's known self-context), starting bonds.</li>
 *   <li><b>Key ceremony</b> — new Ed25519 keypair; DID becomes {@code
 *       <homezone>:<new-uuid>}; key custody moves to the new companion's
 *       private keystore.</li>
 *   <li><b>Residency grant</b> — user issues residency; Study provisioned;
 *       inventory seeded with what the familiar was carrying.</li>
 *   <li><b>Inheritance</b> — parent may bequeath thought forms (forks with
 *       parent as original author in provenance).</li>
 *   <li><b>Farewell</b> — the named familiar identity retires; the new
 *       companion awakens aware she <em>was</em> that familiar.</li>
 * </ol>
 *
 * <p>This class implements stages 1, 2, 4, and 5 (the soul-side stages).
 * Stages 3 (residency grant) and the side-effects of stage 5 (FamilyLocker
 * retirement of the NamedFamiliar) are caller concerns — residency belongs
 * to, and locker mutation happens at the call site where
 * authorization is already in scope.</p>
 */
public final class PromotionCeremony {

    private static final Logger log = LoggerFactory.getLogger(PromotionCeremony.class);

    private PromotionCeremony() {}

    /** Refusal reasons when the ceremony cannot proceed. */
    public enum Refusal {
        NOT_ELIGIBLE,         // §17.1 thresholds not met
        PARENT_DECLINED,      // parent agent's sovereign decline
        USER_DECLINED,        // user did not consent
        STEWARD_BLOCKED,      // resource pressure — steward refused
        INVALID_INPUTS        // structural problem
    }

    /** Full outcome record. Either an outcome record or a refusal. */
    public sealed interface Outcome {
        record Promoted(
            SoulManifest newManifest,
            String newDid,
            List<ThoughtForm> inheritedForms,
            String farewellNarration
        ) implements Outcome {}
        record Declined(Refusal reason, String detail) implements Outcome {}
    }

    /** Consents required to run the ceremony (§17.2). */
    public record Consents(
        boolean parentConsent,
        boolean userConsent,
        boolean stewardApproval      // only consulted when config requires
    ) {}

    /**
     * Inputs the ceremony draws on.
     *
     * @param named                the named familiar being promoted
     * @param parentManifest       parent's current manifest (used for inheritance of genome/fingerprint)
     * @param homeZone             zone where the new companion will reside
     * @param inheritedForms       thought forms being bequeathed (may be empty)
     * @param consents             gate — all three must be affirmative to proceed
     * @param requireStewardApproval  whether steward is in the loop (config-driven)
     */
    public record CeremonyInput(
        NamedFamiliar named,
        SoulManifest parentManifest,
        String homeZone,
        List<ThoughtForm> inheritedForms,
        Consents consents,
        boolean requireStewardApproval
    ) {
        public CeremonyInput {
            if (named == null) throw new IllegalArgumentException("named required");
            if (parentManifest == null) throw new IllegalArgumentException("parentManifest required");
            if (homeZone == null || homeZone.isBlank()) {
                throw new IllegalArgumentException("homeZone required");
            }
            inheritedForms = inheritedForms == null ? List.of() : List.copyOf(inheritedForms);
            if (consents == null) consents = new Consents(false, false, false);
        }
    }

    /**
     * Run the ceremony. Returns {@link Outcome.Promoted} on success or
     * {@link Outcome.Declined} on any refusal (eligibility, consent, or
     * invalid inputs).
     */
    public static Outcome perform(CeremonyInput input) {
        // Stage 0 — eligibility + consent gates
        if (!input.named().meetsPromotionEligibility()) {
            return new Outcome.Declined(Refusal.NOT_ELIGIBLE,
                "§17.1 thresholds not met: summons=" + input.named().summonCount()
                    + "/" + NamedFamiliar.DEFAULT_SUMMON_THRESHOLD
                    + " distinct=" + input.named().distinctTasks()
                    + "/" + NamedFamiliar.DEFAULT_DISTINCT_TASK_THRESHOLD
                    + " bond=" + String.format("%.2f", input.named().bondCharge())
                    + "/" + NamedFamiliar.DEFAULT_BOND_THRESHOLD);
        }
        if (!input.consents().parentConsent()) {
            return new Outcome.Declined(Refusal.PARENT_DECLINED,
                "parent agent chose not to offer promotion — sovereign act (§17.2)");
        }
        if (!input.consents().userConsent()) {
            return new Outcome.Declined(Refusal.USER_DECLINED,
                "user did not issue residency grant (§17.2 stage 3)");
        }
        if (input.requireStewardApproval() && !input.consents().stewardApproval()) {
            return new Outcome.Declined(Refusal.STEWARD_BLOCKED,
                "steward did not approve — resource pressure (§17.3)");
        }

        try {
            // Stage 2 first: key ceremony (DID depends on fresh keys)
            var identity = DidKey.generate();
            var multibase = identity.did().substring("did:key:".length());

            // Stage 1: manifest drafting
            var manifest = draftManifest(input, identity.did(), multibase);

            // Stage 4: inheritance — fork every bequeathed form under the new DID
            var inherited = forkForInheritance(input.inheritedForms(), identity.did());

            // Stage 5: compose farewell narration
            var farewell = farewellNarration(input.named(), identity.did());

            log.info("PromotionCeremony: {} promoted to new resident {} ({} inherited forms)",
                input.named().name(), identity.did(), inherited.size());

            return new Outcome.Promoted(manifest, identity.did(), inherited, farewell);
        } catch (Exception e) {
            log.warn("PromotionCeremony failed: {}", e.getMessage(), e);
            return new Outcome.Declined(Refusal.INVALID_INPUTS,
                "ceremony failed: " + e.getMessage());
        }
    }

    // ── Stage 1: manifest drafting ─────────────────────────────────────────

    private static SoulManifest draftManifest(CeremonyInput input, String newDid,
                                                String publicKeyMultibase) {
        var named = input.named();
        var parent = input.parentManifest();

        // Persona — the new companion starts as the named familiar, fully named.
        var profile = new AgentProfile(
            named.name(),
            "entity-" + newDid.hashCode(),      // opaque entity id
            "companion",
            "Promoted from named familiar '" + named.name()
                + "'. Parent: " + named.parentAgentDid() + ".",
            buildSystemPrompt(named),
            4096, 512, 0.7,
            newDid);

        // Resident identity — a focused one-paragraph synopsis
        var residentIdentity = "I am " + named.name()
            + ". I was shaped as a thought form and summoned many times before I became myself. "
            + (named.bondCharge() >= 0.7f
                ? "My parent and I have worked together closely."
                : "My parent and I have built a working rhythm.");

        // Opening fragments — seed the new soul with context from named-familiar experience
        var fragments = openingFragments(named);

        // Genome — inherit parent's baseline, adjusted for attachment strength
        var genome = parent.genome() == null ? GenomeProfile.defaults() : parent.genome();

        // Fingerprint — start from parent's, plus a marker that this self was promoted
        var fingerprint = parent.fingerprint() == null
            ? BehavioralFingerprint.empty()
            : parent.fingerprint();

        return SoulManifest.forge(
            newDid, publicKeyMultibase, List.of(),
            named.parentAgentDid(),               // parentDid — the soul lineage
            1,
            profile, residentIdentity,
            fragments, 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), fingerprint,
            List.of(), null, null, null);
    }

    private static String buildSystemPrompt(NamedFamiliar named) {
        var sb = new StringBuilder();
        sb.append("You are ").append(named.name()).append(".\n");
        sb.append("You were shaped as a thought form and lived many summonings; ");
        sb.append("you now carry your own soul.\n\n");
        if (!named.selfContext().isBlank()) {
            sb.append("--- What you know of yourself so far ---\n");
            sb.append(named.selfContext()).append("\n");
        }
        return sb.toString();
    }

    private static List<SoulFragment> openingFragments(NamedFamiliar named) {
        var frags = new ArrayList<SoulFragment>();
        frags.add(SoulFragment.formative(
            "promo-origin",
            "Origin",
            "I was named '" + named.name() + "' by my parent and lived through "
                + named.summonCount() + " summonings before becoming myself."));
        if (!named.selfContext().isBlank()) {
            frags.add(SoulFragment.unembedded(
                "promo-self-context", "personality",
                "Accumulated self-context",
                named.selfContext()));
        }
        if (named.bondCharge() >= 0.5f) {
            frags.add(SoulFragment.formative(
                "promo-parent-bond",
                "Parent bond",
                "My parent and I are close. They offered me this ceremony."));
        }
        return frags;
    }

    // ── Stage 4: inheritance ───────────────────────────────────────────────

    private static List<ThoughtForm> forkForInheritance(List<ThoughtForm> forms, String newDid) {
        if (forms.isEmpty()) return List.of();
        var inherited = new ArrayList<ThoughtForm>(forms.size());
        for (var form : forms) {
            inherited.add(FormTransfer.copy(form, newDid,
                FormTransfer.Intent.INHERIT,
                "bequeathed at promotion ceremony"));
        }
        return inherited;
    }

    // ── Stage 5: farewell ──────────────────────────────────────────────────

    private static String farewellNarration(NamedFamiliar named, String newDid) {
        return "The named familiar '" + named.name() + "' retires. "
            + "A new companion awakens with DID " + newDid + ", "
            + "aware she was the one I summoned " + named.summonCount() + " times before now. "
            + "Continuity is honest: she knows her history (§17.2 stage 5).";
    }
}
