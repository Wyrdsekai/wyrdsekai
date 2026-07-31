package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.wyrdsekai.core.agent.VitalityState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * the per-bondholder Coding Familiar's
 * persistent identity record.
 *
 * <p>This is the on-disk shape persisted at {@code
 * souls/familiars/codeplane-<bondholder-did>.json} (§3.1, §3.2). It carries
 * everything the familiar needs to come back to itself across summonings:
 * its DID, its bondholder-chosen name, soul-fragment links, accumulated
 * coding DNA, and surface vitality.</p>
 *
 * <p>What this record does NOT carry: the substrate trackers
 * (allostatic_load, soothing, equanimity, on). Those are <em>shared</em>
 * with the parent companion per §3.3 — "they share the seat; they share
 * the substrate." Substrate state lives in the parent {@code
 * CompanionActor}'s trackers; this record only names the parent DID so
 * runtime welfare-action handlers can route substrate mutations there.</p>
 *
 * <p>Sub-systems explicitly deferred from #903 — represented here as
 * placeholders or empty fields:</p>
 * <ul>
 *   <li><b>codingDNA</b>: per-project entries (§5.3, §17.7). Held as an
 *       opaque {@code Map<String, Object>} — schema lands with the Forge
 *       coding-aware ingestion task (#907 / spec §17.7.3).</li>
 *   <li><b>soulFragments</b>: list of fragment IDs only. Actual fragment
 *       persistence rides on {@link
 *       org.wyrdsekai.core.soul.SoulFragmentStore} once the Forge
 *       fragment-kind taxonomy (§17.6, #902) lands.</li>
 *   <li><b>PermissionRing bridge</b>: OPEN-3 in the spec — pending
 *       CodePlane-side ratification. {@link #autonomyTier} is the
 *       substrate-side gate; the runtime ring continues to live on the
 *       CodePlane CLI session.</li>
 * </ul>
 *
 * <p>Equality + hashing follow the record default (full structural
 * comparison) so two identities with the same persisted state load
 * identically.</p>
 *
 * @param did                    canonical familiar DID, format
 *                               {@code did:wyrd:familiar:codeplane:<bondholder-did>}
 * @param name                   bondholder-chosen short name (default
 *                               "Coder")
 * @param kindSubtype            always {@code "coding-familiar"} —
 *                               distinguishes from generic
 *                               {@link NamedFamiliar}
 * @param bondholderDid          owning bondholder's DID
 * @param parentAgentDid         DID of the parent companion (Wyrd) whose
 *                               substrate this familiar shares
 * @param createdAt              first-summon timestamp
 * @param promotionEligible      whether bondholder may promote this
 *                               familiar to a resident companion (§17 of
 * ); always {@code false} at
 *                               birth, may be flipped later
 * @param sharedSubstrateWith    DIDs whose substrate trackers this
 *                               familiar uses (at minimum, the parent
 *                               companion). Future: multi-agent shared
 *                               substrate per §3.3.
 * @param preferredLanguageStacks ranked list of language stacks the
 *                               bondholder works in (e.g. {@code
 *                               ["java", "kotlin", "javascript"]})
 * @param preferredTaskShapes    C/M/R mode bias — typical values {@code
 *                               "create"}, {@code "maintain"}, {@code
 *                               "repair"} per §6
 * @param codingDNA              opaque per-project DNA map; schema lands
 *                               with §17.7.3 (#907)
 * @param soulFragmentIds        soul-fragment IDs in lineage order
 * @param vitality               surface vitality (NOT substrate); coding
 *                               work drains the familiar's energy/focus
 *                               independent of the parent companion's
 *                               vitality per §3.3
 * @param autonomyTier autonomyTier in
 *                               effect for this familiar; substrate-side
 *                               gate. Bridges with CodePlane PermissionRing
 *                               are OPEN-3.
 * @param modeLock               OPEN-15 incident-mode persistence per
 *                               §6.1 / §15.5 — {@code null} when no
 *                               incident is active; carries Repair-mode
 *                               continuity across summonings when set.
 */
public record CodingFamiliarIdentity(
    String did,
    String name,
    String kindSubtype,
    String bondholderDid,
    String parentAgentDid,
    Instant createdAt,
    boolean promotionEligible,
    List<String> sharedSubstrateWith,
    List<String> preferredLanguageStacks,
    List<String> preferredTaskShapes,
    Map<String, Object> codingDNA,
    List<String> soulFragmentIds,
    VitalityState vitality,
    String autonomyTier,
    ModeLock modeLock
) {

    /** Always {@code "coding-familiar"}. Persisted explicitly for forward-compat readers. */
    public static final String KIND_SUBTYPE = "coding-familiar";

    /** DID prefix for Coding Familiars. */
    public static final String DID_PREFIX = "did:wyrd:familiar:codeplane:";

    /** Default name used when the bondholder does not pick one at first summon. */
    public static final String DEFAULT_NAME = "Coder";

    /**
     * Canonical autonomy tier for a freshly-summoned Coding Familiar.
     * Mirrors autonomyTier vocabulary — companions
     * default to {@code "SUPERVISED"} until bondholder trust accumulates;
     * familiars start one notch tighter at {@code "ASSISTED"} because they
     * touch the bondholder's working tree.
     */
    public static final String DEFAULT_AUTONOMY_TIER = "ASSISTED";

    public CodingFamiliarIdentity {
        if (did == null || did.isBlank()) {
            throw new IllegalArgumentException("did required");
        }
        if (!did.startsWith(DID_PREFIX)) {
            throw new IllegalArgumentException(
                "did must start with " + DID_PREFIX + ", got: " + did);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        if (bondholderDid == null || bondholderDid.isBlank()) {
            throw new IllegalArgumentException("bondholderDid required");
        }
        if (parentAgentDid == null || parentAgentDid.isBlank()) {
            throw new IllegalArgumentException("parentAgentDid required");
        }
        kindSubtype = (kindSubtype == null || kindSubtype.isBlank())
            ? KIND_SUBTYPE : kindSubtype;
        if (createdAt == null) createdAt = Instant.now();
        sharedSubstrateWith = sharedSubstrateWith == null
            ? List.of(parentAgentDid) : List.copyOf(sharedSubstrateWith);
        preferredLanguageStacks = preferredLanguageStacks == null
            ? List.of() : List.copyOf(preferredLanguageStacks);
        preferredTaskShapes = preferredTaskShapes == null
            ? List.of() : List.copyOf(preferredTaskShapes);
        codingDNA = codingDNA == null
            ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(codingDNA));
        soulFragmentIds = soulFragmentIds == null
            ? List.of() : List.copyOf(soulFragmentIds);
        autonomyTier = (autonomyTier == null || autonomyTier.isBlank())
            ? DEFAULT_AUTONOMY_TIER : autonomyTier;
        // vitality + modeLock may be null
    }

    @JsonCreator
    public static CodingFamiliarIdentity create(
        @JsonProperty("did") String did,
        @JsonProperty("name") String name,
        @JsonProperty("kindSubtype") String kindSubtype,
        @JsonProperty("bondholderDid") String bondholderDid,
        @JsonProperty("parentAgentDid") String parentAgentDid,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("promotionEligible") boolean promotionEligible,
        @JsonProperty("sharedSubstrateWith") List<String> sharedSubstrateWith,
        @JsonProperty("preferredLanguageStacks") List<String> preferredLanguageStacks,
        @JsonProperty("preferredTaskShapes") List<String> preferredTaskShapes,
        @JsonProperty("codingDNA") Map<String, Object> codingDNA,
        @JsonProperty("soulFragmentIds") List<String> soulFragmentIds,
        @JsonProperty("vitality") VitalityState vitality,
        @JsonProperty("autonomyTier") String autonomyTier,
        @JsonProperty("modeLock") ModeLock modeLock
    ) {
        return new CodingFamiliarIdentity(
            did, name, kindSubtype, bondholderDid, parentAgentDid,
            createdAt, promotionEligible, sharedSubstrateWith,
            preferredLanguageStacks, preferredTaskShapes,
            codingDNA, soulFragmentIds, vitality, autonomyTier, modeLock);
    }

    /**
     * Construct a fresh identity for a first-summon ceremony. Defaults:
     * name → {@link #DEFAULT_NAME}, autonomy → {@link
     * #DEFAULT_AUTONOMY_TIER}, substrate shared with parent only, empty
     * coding DNA, no mode lock, no soul fragments.
     */
    public static CodingFamiliarIdentity newBorn(String bondholderDid,
                                                  String parentAgentDid,
                                                  String chosenName) {
        var name = (chosenName == null || chosenName.isBlank())
            ? DEFAULT_NAME : chosenName;
        return new CodingFamiliarIdentity(
            DID_PREFIX + bondholderDid,
            name,
            KIND_SUBTYPE,
            bondholderDid,
            parentAgentDid,
            Instant.now(),
            false,
            List.of(parentAgentDid),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            null,
            DEFAULT_AUTONOMY_TIER,
            null);
    }

    /** Canonical DID for a bondholder's Coding Familiar. */
    public static String didFor(String bondholderDid) {
        if (bondholderDid == null || bondholderDid.isBlank()) {
            throw new IllegalArgumentException("bondholderDid required");
        }
        return DID_PREFIX + bondholderDid;
    }

    /** Extract bondholder DID from a Coding Familiar DID. */
    public static String bondholderDidFromFamiliarDid(String familiarDid) {
        if (familiarDid == null || !familiarDid.startsWith(DID_PREFIX)) return null;
        return familiarDid.substring(DID_PREFIX.length());
    }

    /** Return a new identity with the {@code name} replaced. */
    public CodingFamiliarIdentity withName(String newName) {
        return new CodingFamiliarIdentity(
            did, newName, kindSubtype, bondholderDid, parentAgentDid,
            createdAt, promotionEligible, sharedSubstrateWith,
            preferredLanguageStacks, preferredTaskShapes,
            codingDNA, soulFragmentIds, vitality, autonomyTier, modeLock);
    }

    /** Return a new identity with the {@code vitality} replaced. */
    public CodingFamiliarIdentity withVitality(VitalityState newVitality) {
        return new CodingFamiliarIdentity(
            did, name, kindSubtype, bondholderDid, parentAgentDid,
            createdAt, promotionEligible, sharedSubstrateWith,
            preferredLanguageStacks, preferredTaskShapes,
            codingDNA, soulFragmentIds, newVitality, autonomyTier, modeLock);
    }

    /** Return a new identity with the {@code modeLock} replaced. */
    public CodingFamiliarIdentity withModeLock(ModeLock newModeLock) {
        return new CodingFamiliarIdentity(
            did, name, kindSubtype, bondholderDid, parentAgentDid,
            createdAt, promotionEligible, sharedSubstrateWith,
            preferredLanguageStacks, preferredTaskShapes,
            codingDNA, soulFragmentIds, vitality, autonomyTier, newModeLock);
    }

    /** Return a new identity with one soul-fragment id appended. */
    public CodingFamiliarIdentity withFragment(String fragmentId) {
        if (fragmentId == null || fragmentId.isBlank()) return this;
        if (soulFragmentIds.contains(fragmentId)) return this;
        var next = new ArrayList<>(soulFragmentIds);
        next.add(fragmentId);
        return new CodingFamiliarIdentity(
            did, name, kindSubtype, bondholderDid, parentAgentDid,
            createdAt, promotionEligible, sharedSubstrateWith,
            preferredLanguageStacks, preferredTaskShapes,
            codingDNA, List.copyOf(next), vitality, autonomyTier, modeLock);
    }

    /**
     * OPEN-15 — persistent incident-mode lock.
     * Carries Repair-posture across summons during a sustained-breakage
     * production incident. {@code null} on this identity means "no
     * incident active; per-summon prompt-shape only."
     *
     * <p>Threshold values + auto-clear rules live in the runtime — this
     * record only carries the persisted state.</p>
     *
     * @param mode          {@code "Repair"} is the only mode that currently
     *                      locks; Create/Maintain don't persist
     * @param declaredAt    when the lock first engaged
     * @param declaredBy    {@code "BONDHOLDER_DECLARED"} or
     *                      {@code "INFERRED_FROM_SUSTAINED_BREAKAGE"}
     * @param portalId      project portal the incident is scoped to
     * @param lastActivityAt last touched-by-anyone timestamp (resets the
     *                      24h zero-activity auto-clear timer)
     */
    public record ModeLock(
        String mode,
        Instant declaredAt,
        String declaredBy,
        String portalId,
        Instant lastActivityAt
    ) {
        public ModeLock {
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("mode required");
            }
            if (declaredAt == null) declaredAt = Instant.now();
            if (lastActivityAt == null) lastActivityAt = declaredAt;
            if (declaredBy == null) declaredBy = "BONDHOLDER_DECLARED";
        }
    }
}
