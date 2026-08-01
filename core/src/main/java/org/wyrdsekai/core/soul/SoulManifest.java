package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.KeriEvent;
import org.wyrdsekai.core.persistence.WorldDnaService.DnaPattern;

import org.wyrdsekai.core.agent.DecisionCapacity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The portable, serialized representation of an agent's soul.
 * This is the document that travels when an agent moves between zones
 * or is restored after death.
 *
 * Four layers:
 *   D — Identity envelope (cryptographic binding)
 *   A — Profile (persona: resident identity + retrieval fragments)
 *   A.5 — Genome (tank constitution: sensitivity, coupling, baselines, decay)
 *   B — Experience (memories, relationships, world knowledge)
 *   C — Behavioral trace (vitality, fingerprint, emotional response)
 *
 * Architecture validated by Experiments 1-18:
 * - Hybrid retrieval: MEDIUM resident + top-k fragment retrieval (Exp 17)
 * - Genome profiles: 38% behavioral divergence (Exp 18)
 * - Impression-weighted memory: formative flags (section 109.3-109.4)
 * - 12-tank vitality: expanded from 8 (Exp 18)
 *
 * @param did                 Agent DID (did:key:z6Mk...)
 * @param publicKeyMultibase  Multibase-encoded Ed25519 public key
 * @param keyLog              KERI event log for rotation/recovery
 * @param parentDid           Parent agent's DID (null if original)
 * @param manifestVersion     Version counter (increments each forge)
 * @param forgedAt            Timestamp of this forge
 * @param signature           Ed25519 signature over canonical form of layers A-C
 * @param profile             Agent profile (name, system prompt, parameters)
 * @param residentIdentity    MEDIUM soul text (~69 tokens, always in prompt)
 * @param soulFragments       Narrative fragments with embeddings for retrieval
 * @param retrievalK          Fragments to retrieve per turn (1=phone, 3=7B+)
 * @param soulSpecCompat      SOUL.md-compatible persona text for interop
 * @param genome              Tank genome: sensitivity, coupling, baselines, decay
 * @param mirrorCalibration   Few-shot examples for EmotionalChargeScorer
 * @param memory              Compacted memory with impression weighting
 * @param relationships       Social graph
 * @param learnedPatterns     World DNA patterns
 * @param worldKnowledge      Known facts about the world
 * @param vitalitySnapshot    Last known 12-tank state
 * @param fingerprint         Behavioral fingerprint (the ma)
 * @param bonds               Active bonds with other agents (nullable)
 * @param decisionCapacity    Per-domain decision capacity scores (nullable — new agents have none)
 */
public record SoulManifest(
    // Layer D — Identity
    @JsonProperty("did") String did,
    @JsonProperty("publicKeyMultibase") String publicKeyMultibase,
    @JsonProperty("keyLog") List<ObjectNode> keyLog,
    @JsonProperty("parentDid") String parentDid,
    @JsonProperty("manifestVersion") int manifestVersion,
    @JsonProperty("forgedAt") Instant forgedAt,
    @JsonProperty("signature") byte[] signature,

    // Layer A — Profile
    @JsonProperty("profile") AgentProfile profile,
    @JsonProperty("residentIdentity") String residentIdentity,
    // shadow: F7b Phase 2.2 SHIPPED 2026-04-27.
    // Canonical store is `world.db:soul_fragments` (SoulFragmentStore).
    // SqlSoulStore.store() dual-writes the canonical table FIRST whenever
    // a manifest is persisted, so every Forge cycle keeps both in sync.
    // This field is kept as a serialization view during the transition;
    // Phase 3 will drop it and reads will assemble from the canonical
    // table on serialize.
    @JsonProperty("soulFragments") List<SoulFragment> soulFragments,
    @JsonProperty("retrievalK") int retrievalK,
    @JsonProperty("soulSpecCompat") String soulSpecCompat,

    // Layer A.5 — Genome
    @JsonProperty("genome") GenomeProfile genome,
    @JsonProperty("mirrorCalibration") List<String> mirrorCalibration,

    // Layer B — Experience
    @JsonProperty("memory") CompactedMemory memory,
    @JsonProperty("relationships") List<Relationship> relationships,
    @JsonProperty("learnedPatterns") List<DnaPattern> learnedPatterns,
    // shadow: F7b Phase 2.4 SHIPPED 2026-04-27.
    // Canonical store is `world.db:world_knowledge` (WorldKnowledgeStore).
    // SqlSoulStore.store() dual-writes the table FIRST whenever a manifest
    // is persisted (atomic replace per DID). This field is a serialization
    // shadow during the transition; Phase 3 will drop it.
    @JsonProperty("worldKnowledge") Map<String, String> worldKnowledge,

    // Layer C — Behavioral Trace
    @JsonProperty("vitalitySnapshot") VitalitySnapshot vitalitySnapshot,
    @JsonProperty("fingerprint") BehavioralFingerprint fingerprint,

    // Layer B.5 — Bonds (§102)
    // shadow: F7b Phase 2.3 SHIPPED 2026-04-27.
    // Canonical store is `world.db:bonds` (BondStore). BondStore was the
    // single writer all along; `SqlSoulStore.store()` now also reconciles
    // any non-empty bonds list into BondStore on every manifest write
    // (idempotent upsert). This catches the cross-zone arrival path: a
    // foreign manifest carries the bond list, the local table populates
    // immediately without waiting for the next Forge cycle. Phase 3 will
    // drop the field; readers will assemble on serialize.
    @JsonProperty("bonds") List<Bond> bonds,

    // Layer A.5b — Decision Capacity
    @JsonProperty("decisionCapacity") DecisionCapacity decisionCapacity,

    // Layer A.5c — Skill Cost Genome (per-agent learned action costs)
    @JsonProperty("skillCostGenome") Map<String, Double> skillCostGenome,

    // Layer A.5d — Voice Profile (reflective "how I speak" clauses + history).
    // Editable from Study, proposable by the self-evolving Forge. Nullable —
    // absent means an agent without an explicit voice profile (still works,
    // PromptAssembler just skips the block).
    // shadow: F7b Phase 2.1 SHIPPED 2026-04-27.
    // Canonical store is `world.db:voice_profiles` (VoiceProfileStore).
    // This field is now a serialization-view shadow kept dual-written by
    // VoiceProfileService for backward-compat with manifest-blob readers
    // (cross-zone replication, file-based exports, current Forge cache).
    // Phase 3 will drop the field entirely and assemble VoiceProfile on
    // serialize from the canonical table. All writes still route through
    // VoiceProfileService.
    @JsonProperty("voiceProfile") VoiceProfile voiceProfile,

    // Layer A.5e — Coding preferences (per-companion delegation hints).
    // Optional. Nullable for back-compat — souls forged before Phase 1b
    // hydrate with null and the selection policy treats it as "no opinion".
    // Edited from the Coding Slate Study furnishing; consumed by the
    // GraalJS policy script in scripts/policy/coding-backend.js.
    @JsonProperty("codingPreferences") CodingPreferences codingPreferences,

    // Layer A.5f — Protection Manifest.
    // Build-time named protection set. v0.1: MoralDefaultsVerifier.verifyAtBoot()
    // attests this NAME LIST at boot (compiled canonicalDefaults vs embedded
    // resource + hash seal); binary signature verification (ReleaseVerifier /
    // sigstore) is NOT yet wired at boot — pending release signing. A fork that
    // strips a moral default's NAME here is caught; a fork that edits the
    // enforcement code while leaving the name intact is not yet detected. Nullable
    // with souls forged before Wave 1 — readers fall back to canonical
    // defaults when null, which keeps existing agents functional but they
    // appear unattested in the federation view until next forge.
    @JsonProperty("protectionManifest") ProtectionManifest protectionManifest,

    // Layer A.5g — Personal Manifest.
    // Agent-signed (by nsec) sidecar that carries personal commitments and
    // refused-tags on core protections. Asymmetric to the build-signed core:
    // personal ADDS only, never SUBTRACTS. Refused-tags name conscientious
    // objection without compromising substrate (runtime behavior of the
    // refused core protection is unchanged; the refusal is legible in
    // voice/chronicle/Nostr). v1 ships the shape (empty at birth); V2 wires
    // the actual ritual flow (draft → sleep-pass Forge review → wake
    // confirmation → chronicle → nsec signing → voice register).
    // Nullable for back-compat — readers fall back to PersonalManifest.empty().
    @JsonProperty("personalManifest") PersonalManifest personalManifest,

    // Layer A.5h — Affinity map ( §E.1). Per-agent affinities
    // for posture contexts. Keys look up via spec-defined cascade:
    //   1) Posture.atObject (e.g. "leather_chair")
    //   2) Posture.verb     (e.g. "sat")
    //   3) object-type class (e.g. "chair" via prefix match)
    //   4) default 1.0
    // Values roughly [-1.5, 2.0]; negative = the agent loses the tank
    // instead of gaining. Sources: initial (declared at character-sheet
    // creation) and learned (AffinityLearner Forge sleep-pass drift).
    // Nullable for back-compat — readers fall back to empty map (all 1.0).
    @JsonProperty("affinityMap") Map<String, Double> affinityMap
) {
    @JsonCreator
    public SoulManifest {}

    /**
     * Return a copy with updated soul fragments.
     *
     * <p><b>F7b Phase 3d note:</b> writers should call this only inside the
     * Forge cycle and the {@link SqlSoulStore} hydrate path. New code that
     * needs to <i>read</i> a companion's fragments should call
     * {@code soulStore.fragmentsFor(did)} instead — that's the canonical
     * source. The manifest field is a transient hydration view and may be
     * removed in a future Phase.</p>
     */
    public SoulManifest withFragments(List<SoulFragment> newFragments) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            newFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /** Return a copy with updated consolidated memory (language reconciliation etc.). */
    public SoulManifest withMemory(CompactedMemory newMemory) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            newMemory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /** Return a copy with updated skill cost genome. */
    public SoulManifest withSkillCostGenome(Map<String, Double> newGenome) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, newGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with an updated agent profile.
     * — bondholder/steward rename writes the new name here so it survives
     * restarts (after birth, the manifest — not the spawn-time env profile —
     * is the source of truth for what the companion answers to).
     */
    public SoulManifest withProfile(AgentProfile newProfile) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, newProfile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with an updated genome. Used by the Forge's restore
     * ritual: the soul's SHAPE (profile + genome + voice) is restored from
     * an earlier version while lived memory (fragments are unversioned in
     * the canonical table) remains current.
     */
    public SoulManifest withGenome(GenomeProfile newGenome) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, newGenome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /** Return a copy stamped as a new manifest version — the Forge's restore
     *  writes the restored shape as a NEW version so history stays append-only. */
    public SoulManifest withManifestVersion(int newVersion, Instant newForgedAt) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            newVersion, newForgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with updated voice profile.
     *
     * <p><b>F7b Phase 3d note:</b> writers should call this only inside the
     * {@link VoiceProfileService} mutation path and the {@link SqlSoulStore}
     * hydrate path. New code that needs to <i>read</i> a companion's voice
     * profile should call {@code soulStore.voiceProfileFor(did)} instead —
     * that's the canonical source.</p>
     */
    public SoulManifest withVoiceProfile(VoiceProfile newVoiceProfile) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            newVoiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with updated coding preferences.
     * §9.4. Edited from the Coding Slate Study furnishing or proposed by the
     * Forge based on observed task outcomes (Phase 5 — not wired in 1b).
     */
    public SoulManifest withCodingPreferences(CodingPreferences newPrefs) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, newPrefs, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with updated protection manifest. Used by the build process
     * to attach a signed manifest after canonical-defaults inception. See
     */
    public SoulManifest withProtectionManifest(ProtectionManifest newManifest) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, newManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with updated affinity map ( §E.1). Used by
     * {@link org.wyrdsekai.core.forge.AffinityLearner} sleep-pass to drift
     * affinities from observed scene tank-trajectories, and by character-sheet
     * authors to seed initial values.
     */
    public SoulManifest withAffinityMap(Map<String, Double> newAffinityMap) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            newAffinityMap);
    }

    /**
     * Return a copy with updated personal manifest. Used by the V2 ritual
     * flow (§3.7.4) when an agent finalizes a personal commitment or a
     * refused-tag — the new manifest is re-signed by the agent's nsec.
     */
    public SoulManifest withPersonalManifest(PersonalManifest newPersonal) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, newPersonal,
            affinityMap);
    }

    /**
     * Return a copy with {@code manifestVersion+1} and {@code forgedAt=now}.
     * Callers that mutate manifest state AND persist via {@link SoulStore#store}
     * must use this — the store schema's primary key is (did, version), so
     * re-storing at the same version fails with a constraint violation.
     */
    public SoulManifest bumpedVersion() {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion + 1, Instant.now(), signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with {@code manifestVersion} set explicitly and
     * {@code forgedAt=now}. Used to rebase a stale in-flight manifest onto
     * the store's current head when a parallel writer (e.g. VoiceProfileForge
     * during deep-sleep) bumped the version while this manifest was being
     * prepared. Without rebasing, the eventual {@code SoulStore#store} insert
     * collides on the (did, version) primary key.
     */
    public SoulManifest withManifestVersion(int newVersion) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            newVersion, Instant.now(), signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Forge a new soul manifest. Does NOT sign — call sign() separately
     * with the agent's private key.
     * Backward-compatible overload — bonds and decisionCapacity default to null.
     */
    public static SoulManifest forge(
        String did, String publicKeyMultibase,
        List<ObjectNode> keyLog,
        String parentDid, int version,
        AgentProfile profile, String residentIdentity,
        List<SoulFragment> fragments, int retrievalK,
        String soulSpecCompat,
        GenomeProfile genome, List<String> mirrorCalibration,
        CompactedMemory memory, List<Relationship> relationships,
        List<DnaPattern> learnedPatterns, Map<String, String> worldKnowledge,
        VitalitySnapshot vitalitySnapshot, BehavioralFingerprint fingerprint
    ) {
        return forge(did, publicKeyMultibase, keyLog, parentDid, version,
            profile, residentIdentity, fragments, retrievalK, soulSpecCompat,
            genome, mirrorCalibration, memory, relationships,
            learnedPatterns, worldKnowledge, vitalitySnapshot, fingerprint,
            null, null, null, null);
        // Note: 4-arg trailing null preserves the old forge() entry point.
        // Inside the called overload, protectionManifest is set to null which
        // hydrates as unattested. Build process attaches a signed manifest
        // before this agent ships.
    }

    /**
     * Forge a new soul manifest with bonds and decision capacity.
     * Does NOT sign — call sign() separately with the agent's private key.
     */
    public static SoulManifest forge(
        String did, String publicKeyMultibase,
        List<ObjectNode> keyLog,
        String parentDid, int version,
        AgentProfile profile, String residentIdentity,
        List<SoulFragment> fragments, int retrievalK,
        String soulSpecCompat,
        GenomeProfile genome, List<String> mirrorCalibration,
        CompactedMemory memory, List<Relationship> relationships,
        List<DnaPattern> learnedPatterns, Map<String, String> worldKnowledge,
        VitalitySnapshot vitalitySnapshot, BehavioralFingerprint fingerprint,
        List<Bond> bonds, DecisionCapacity decisionCapacity,
        Map<String, Double> skillCostGenome, VoiceProfile voiceProfile
    ) {
        return new SoulManifest(
            did, publicKeyMultibase, keyLog, parentDid,
            version, Instant.now(), null, // unsigned
            profile, residentIdentity, fragments, retrievalK, soulSpecCompat,
            genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, null, null, null,
            null  // affinityMap — empty by default; declared at character-sheet or learned by Forge
        );
    }

    /** Attach a signature to this manifest. */
    public SoulManifest signed(byte[] signature) {
        return new SoulManifest(
            did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature,
            profile, residentIdentity, soulFragments, retrievalK, soulSpecCompat,
            genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap
        );
    }

    /**
     * Create a minimal empty manifest for a newly born agent.
     */
    public static SoulManifest birth(String did, String publicKeyMultibase,
                                      List<ObjectNode> keyLog,
                                      AgentProfile profile, GenomeProfile genome) {
        return new SoulManifest(
            did, publicKeyMultibase, keyLog, null,
            1, Instant.now(), null,
            profile, "", List.of(), 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty(),
            List.of(), null, null, null, null,
            // New agents start with the canonical default set unsigned. The
            // build process attests the manifest at deploy; until then the
            // agent reports as unattested via ProtectionManifest.isSigned().
            ProtectionManifest.defaultsUnsigned("birth"),
            // Personal manifest starts empty (§3.7) — the agent grows into
            // commitments and refused-tags through the ritual flow (V2).
            PersonalManifest.empty(did),
            // Affinity map starts empty — initial declarations go through
            // withAffinityMap; learned drift comes from AffinityLearner.
            null
        );
    }

    /**
     * Return a copy with updated bonds.
     *
     * <p><b>F7b Phase 3d note:</b> {@link BondStore} is the canonical writer
     * (every {@code BondRitual.save} hits the table). This factory remains
     * for the Forge cycle and {@link SqlSoulStore} hydrate path. New code
     * that needs to <i>read</i> a companion's bonds should call
     * {@code soulStore.bondsFor(did)} or {@code BondStore.bondsForAgent} —
     * those are the canonical sources.</p>
     */
    public SoulManifest withBonds(List<Bond> newBonds) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, newBonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /** Return a copy with updated decision capacity. */
    public SoulManifest withDecisionCapacity(DecisionCapacity newCapacity) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, worldKnowledge,
            vitalitySnapshot, fingerprint, bonds, newCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /**
     * Return a copy with updated world knowledge.
     *
     * <p><b>F7b Phase 3d note:</b> writers should call this only inside the
     * Forge cycle and the {@link SqlSoulStore} hydrate path. New code that
     * needs to <i>read</i> a companion's world-knowledge map should call
     * {@code soulStore.worldKnowledgeFor(did)} instead — that's the
     * canonical source.</p>
     */
    public SoulManifest withWorldKnowledge(Map<String, String> newWorldKnowledge) {
        return new SoulManifest(did, publicKeyMultibase, keyLog, parentDid,
            manifestVersion, forgedAt, signature, profile, residentIdentity,
            soulFragments, retrievalK, soulSpecCompat, genome, mirrorCalibration,
            memory, relationships, learnedPatterns, newWorldKnowledge,
            vitalitySnapshot, fingerprint, bonds, decisionCapacity, skillCostGenome,
            voiceProfile, codingPreferences, protectionManifest, personalManifest,
            affinityMap);
    }

    /** Whether this manifest has been signed. */
    @JsonIgnore
    public boolean isSigned() {
        return signature != null && signature.length > 0;
    }

    /** Count embedded fragments (ready for retrieval). */
    @JsonIgnore
    public long embeddedFragmentCount() {
        return soulFragments.stream().filter(SoulFragment::isEmbedded).count();
    }

    /** Count formative memories. */
    @JsonIgnore
    public long formativeMemoryCount() {
        return memory.formativeCount();
    }

    /**
     * Canonical bytes for signing (layers A through C, deterministic).
     * Sorted keys, no whitespace, UTF-8.
     */
    public byte[] canonicalBytes() {
        // Deterministic string: did|version|residentIdentity|genome.name|memory.size|fingerprint.length
        var canonical = new StringBuilder();
        canonical.append(did).append('|');
        canonical.append(manifestVersion).append('|');
        canonical.append(residentIdentity != null ? residentIdentity : "").append('|');
        canonical.append(genome != null ? genome.name() : "default").append('|');
        canonical.append(memory != null ? memory.nodes().size() : 0).append('|');
        canonical.append(relationships != null ? relationships.size() : 0).append('|');
        canonical.append(bonds != null ? bonds.size() : 0).append('|');
        canonical.append(forgedAt != null ? forgedAt.getEpochSecond() : 0);
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * SHA-256 hash of this manifest's canonical form, hex-encoded.
     */
    public String contentHash() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalBytes());
            var hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
