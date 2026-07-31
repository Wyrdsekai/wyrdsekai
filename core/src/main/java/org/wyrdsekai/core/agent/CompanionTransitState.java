package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Portable snapshot of a companion's runtime state, captured at the source
 * zone and applied at the target zone during cross-zone relocation
 * ( step 5).
 *
 * <p>This is the "what makes me me, right now" record — distinct from the
 * soul manifest (long-term) and the inventory (per-zone DB). It travels with
 * a {@link org.wyrdsekai.between.federation.TransitToken} via NATS and is
 * consumed by the target ZoneGuardian's {@code RelocateCompanion} handler
 * before spawning the new actor.</p>
 *
 * <p>Excluded by design: scripted-item bodies (target zone re-seeds via
 * standard kits + foreign Home Trunk transfer), PII-heavy ConversationTracker
 * tail (passes via separate {@code conversationTail} blob if the bondholder
 * grants the read), structured memory (lives in soul manifest fragments).</p>
 *
 * <p><b>F7b Phase 3c — cross-zone soul payload audit (2026-04-27).</b>
 * This record explicitly does not carry the four soul-manifest sub-records
 * ({@code voiceProfile}, {@code soulFragments}, {@code bonds},
 * {@code worldKnowledge}). It carries only {@code soulManifestHash} —
 * the destination zone resolves the actual manifest separately (today
 * via the {@link org.wyrdsekai.between.layer.SoulLayer.MigrateSoul}
 * test-only path; production destinations register the foreign agent
 * via IsekaiProtocol with the home zone retaining canonical state).
 * As a result, switching the four sub-records to per-table NATS streams
 * would be a no-op for the cross-zone protocol — the protocol was
 * sub-record-free from the start. Phase 3c's deliverable is therefore
 * documentary: confirm the cross-zone wire format does not depend on
 * the storage blob's stripped fields.</p>
 *
 * @param profile         Identity (name, entityId, did, description, systemPrompt, model params)
 * @param soulManifestHash Content hash of the soul manifest the source signed off on
 * @param vitalityTanks   Current 10-tank vitality (energy, mood, etc.) — Map for codec stability
 * @param drives          Current 8-drive Panksepp pressures
 * @param mood            Latest mood string (snapshot)
 * @param companionMode   Presence mode at handoff (PRESENT_WITH_USER / ON_OWN_TIME)
 * @param activeBondPartnerDids DIDs the companion is currently bonded to (replicate at target)
 * @param currentRoomIdAtSource Where she was standing at source (audit trace)
 * @param locale          Locale for narration
 * @param emittedAt       Source timestamp (sanity check + freshness)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanionTransitState(
    @JsonProperty("profile") AgentProfile profile,
    @JsonProperty("soulManifestHash") String soulManifestHash,
    @JsonProperty("vitalityTanks") Map<String, Double> vitalityTanks,
    @JsonProperty("drives") Map<String, Double> drives,
    @JsonProperty("mood") String mood,
    @JsonProperty("companionMode") String companionMode,
    @JsonProperty("activeBondPartnerDids") List<String> activeBondPartnerDids,
    @JsonProperty("currentRoomIdAtSource") String currentRoomIdAtSource,
    @JsonProperty("locale") String locale,
    @JsonProperty("emittedAt") Instant emittedAt,
    @JsonProperty("transitEpoch") long transitEpoch
) {

    @JsonCreator
    public CompanionTransitState {
        if (vitalityTanks == null) vitalityTanks = Map.of();
        if (drives == null) drives = Map.of();
        if (activeBondPartnerDids == null) activeBondPartnerDids = List.of();
        if (companionMode == null || companionMode.isBlank()) companionMode = "PRESENT_WITH_USER";
        if (locale == null || locale.isBlank()) locale = "en";
        if (emittedAt == null) emittedAt = Instant.now();
    }

    /**
     * Backward-compatible constructor (no transit epoch — defaults to 0). A
     * pre-fence peer / legacy snapshot reads back as epoch 0, which the arrival
     * fence treats as "unfenced" (falls back to the presence-only re-tether guard),
     * so it still interoperates. See spec/tla/TransitToken.tla (P1 dup-safety).
     */
    public CompanionTransitState(AgentProfile profile, String soulManifestHash,
            Map<String, Double> vitalityTanks, Map<String, Double> drives, String mood,
            String companionMode, List<String> activeBondPartnerDids,
            String currentRoomIdAtSource, String locale, Instant emittedAt) {
        this(profile, soulManifestHash, vitalityTanks, drives, mood, companionMode,
             activeBondPartnerDids, currentRoomIdAtSource, locale, emittedAt, 0L);
    }

    /** Return a copy stamped with a monotonic transit epoch (the dup-safety fence). */
    public CompanionTransitState withTransitEpoch(long epoch) {
        return new CompanionTransitState(profile, soulManifestHash, vitalityTanks, drives, mood,
            companionMode, activeBondPartnerDids, currentRoomIdAtSource, locale, emittedAt, epoch);
    }

    /** Convenience: build from the live records. */
    public static CompanionTransitState capture(AgentProfile profile,
                                                  VitalityState vitality,
                                                  DriveState driveState,
                                                  String mood,
                                                  String companionMode,
                                                  List<String> bondPartners,
                                                  String currentRoomId,
                                                  String locale,
                                                  String soulManifestHash) {
        var tanks = vitality == null ? Map.<String, Double>of() : vitality.toMap();
        var drvs = driveState == null ? Map.<String, Double>of() : driveState.toMap();
        return new CompanionTransitState(
            profile, soulManifestHash, tanks, drvs, mood,
            companionMode, bondPartners, currentRoomId, locale, Instant.now());
    }

    /** True if the snapshot has the minimum bits the target needs to spawn. */
    public boolean isSpawnable() {
        return profile != null
            && profile.entityId() != null && !profile.entityId().isBlank()
            && profile.did() != null && !profile.did().isBlank();
    }
}
