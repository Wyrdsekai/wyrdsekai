package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * envelope handed to the Forge when the
 * workshop familiar decides "this is deeper than the synchronous loop can
 * handle" and dispatches a bunshin to Forge for async deep work.
 *
 * <p>The envelope captures everything the Forge bunshin needs to:</p>
 * <ul>
 *   <li>Resolve scope: which project portal (§2.4), which files, expected duration.</li>
 *   <li>Carry mode-lock state (§21 OPEN-15) — Repair-mode dispatches consolidate
 *       differently than Maintain-mode dispatches.</li>
 *   <li>Signal back: workshop session id for the return_to callback when
 *       the bunshin completes (or surfaces interim findings under low-autonomy).</li>
 *   <li>Cooperate with the §17.6 fragment-kind taxonomy: each task_shape
 *       indicates which DEXTERITY / CONVENTION / STRUCTURAL pass is expected
 *       to consume the bunshin's output.</li>
 * </ul>
 *
 * <p>Lifecycle: workshop familiar builds → enqueues via
 * {@link ForgeDispatchQueue#submit} → bunshin actor spawns (existing
 * bunshin primitives) → runs in Forge → on completion
 * writes results back to project portal + DNA compartments + signals the
 * workshop session.</p>
 *
 * <p>The envelope is JSON-persisted in the Forge work queue so a bunshin
 * dispatch survives server restart cross-session
 * bunshin persistence.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForgeDispatchEnvelope(
    @JsonProperty("dispatchId") String dispatchId,
    @JsonProperty("familiarDid") String familiarDid,
    @JsonProperty("bondholderDid") String bondholderDid,
    @JsonProperty("taskShape") TaskShape taskShape,
    @JsonProperty("projectPortalId") String projectPortalId,
    @JsonProperty("scopeHint") ScopeHint scopeHint,
    @JsonProperty("modeLockState") ModeLockState modeLockState,
    @JsonProperty("returnTo") String returnTo,
    @JsonProperty("autonomyTier") String autonomyTier,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("status") Status status,
    @JsonProperty("metadata") Map<String, String> metadata
) {

    /**
     * §17.7.2 task shapes. Each maps to a fragment-kind consumer per
     * §17.6: deep-refactor → DEXTERITY (procedural learnings) +
     * STRUCTURAL (project-shape updates); cross-project-distill →
     * CONVENTION; corpus-candidate → DEXTERITY with V6+ training-eligibility
     * marker; spec-ingestion → STRUCTURAL; semantic-equivalence → DEXTERITY.
     */
    public enum TaskShape {
        DEEP_REFACTOR,
        CROSS_PROJECT_DISTILL,
        CORPUS_CANDIDATE,
        SPEC_INGESTION,
        SEMANTIC_EQUIVALENCE
    }

    /** Lifecycle marker for the queued dispatch. */
    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Scope estimate the workshop familiar attached when deciding to
     * dispatch. {@code affectedFiles} can be empty; the bunshin will
     * discover more as it runs but the hint shapes initial planning.
     * {@code expectedDurationMinutes} is provisional — used by the
     * workshop to set bondholder expectations.
     */
    public record ScopeHint(
        @JsonProperty("affectedFiles") List<String> affectedFiles,
        @JsonProperty("depth") String depth,
        @JsonProperty("expectedDurationMinutes") Integer expectedDurationMinutes
    ) {
        @JsonCreator
        public ScopeHint {
            affectedFiles = affectedFiles == null
                ? List.of() : List.copyOf(affectedFiles);
        }
    }

    /**
     * Mode-lock state propagated per §21 OPEN-15. {@code null} on the
     * envelope means "no incident-mode active" — bunshin runs Maintain-
     * mode consolidation. When non-null, the bunshin inherits Repair
     * posture for any work touching the same portal.
     */
    public record ModeLockState(
        @JsonProperty("mode") String mode,
        @JsonProperty("declaredAt") Instant declaredAt,
        @JsonProperty("declaredBy") String declaredBy,
        @JsonProperty("portalId") String portalId
    ) {
        @JsonCreator
        public ModeLockState {}
    }

    @JsonCreator
    public ForgeDispatchEnvelope {
        if (dispatchId == null || dispatchId.isBlank()) {
            throw new IllegalArgumentException("dispatchId required");
        }
        if (familiarDid == null || familiarDid.isBlank()) {
            throw new IllegalArgumentException("familiarDid required");
        }
        if (taskShape == null) {
            throw new IllegalArgumentException("taskShape required");
        }
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = Status.QUEUED;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Build a fresh envelope for a workshop-side dispatch. Generates a UUID
     * dispatchId and stamps {@code createdAt} = now / status = QUEUED.
     */
    public static ForgeDispatchEnvelope newDispatch(
        String familiarDid,
        String bondholderDid,
        TaskShape taskShape,
        String projectPortalId,
        ScopeHint scopeHint,
        ModeLockState modeLockState,
        String returnTo,
        String autonomyTier
    ) {
        return new ForgeDispatchEnvelope(
            UUID.randomUUID().toString(),
            familiarDid, bondholderDid, taskShape, projectPortalId,
            scopeHint, modeLockState, returnTo, autonomyTier,
            Instant.now(), Status.QUEUED, Map.of());
    }

    /** Return a copy with the status replaced. */
    public ForgeDispatchEnvelope withStatus(Status newStatus) {
        return new ForgeDispatchEnvelope(
            dispatchId, familiarDid, bondholderDid, taskShape, projectPortalId,
            scopeHint, modeLockState, returnTo, autonomyTier,
            createdAt, newStatus, metadata);
    }

    /**
     * §17.7.3 helper: the fragment-kind consumer for this task shape per
     * the §17.6 taxonomy. Forge's coding-aware ingestion uses this to
     * route the bunshin's output stream to the correct consolidation pass.
     *
     * <p>Derived from {@link #taskShape}; not serialized.</p>
     */
    @JsonIgnore
    public FragmentKind expectedOutputKind() {
        return switch (taskShape) {
            case DEEP_REFACTOR        -> FragmentKind.DEXTERITY;
            case CROSS_PROJECT_DISTILL -> FragmentKind.CONVENTION;
            case CORPUS_CANDIDATE     -> FragmentKind.DEXTERITY;
            case SPEC_INGESTION       -> FragmentKind.STRUCTURAL;
            case SEMANTIC_EQUIVALENCE -> FragmentKind.DEXTERITY;
        };
    }

    /**
     * True when this envelope carries an active Repair mode-lock. The
     * bunshin running in Forge inherits Repair posture and consolidates
     * differently (Repair-mode allostatic-load drain elevations propagate
     * through to the parent companion's substrate per §3.3).
     *
     * <p>Derived from {@link #modeLockState}; not serialized.</p>
     */
    @JsonIgnore
    public boolean isRepairMode() {
        return modeLockState != null
            && "Repair".equalsIgnoreCase(modeLockState.mode());
    }
}
