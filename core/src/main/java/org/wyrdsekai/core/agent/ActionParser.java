package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.ItemRevision;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.familiar.FormEvolutionClassifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured actions from companion LLM output.
 * The companion embeds JSON blocks in its response for actions like room creation
 * and hint suggestions.
 */
public final class ActionParser {

    private static final Logger LOG = LoggerFactory.getLogger(ActionParser.class);

    private ActionParser() {}

    public sealed interface AgentAction {
        record CreateRoom(
            String name,
            String description,
            List<ExitSpec> exits,
            String behaviorScript,
            String template        // nullable — "library", "hub", "garden", etc.
        ) implements AgentAction {}

        record SuggestHints(
            List<Hint> hints
        ) implements AgentAction {}

        /**
         * Companion submits code to the Workshop workbench for validation,
         * testing, and packaging as a reusable skill item.
         */
        record WorkbenchSubmit(
            String skillName,
            String skillDescription,
            String runtime,
            String code,
            List<SkillParam> params,
            List<TestCase> testCases
        ) implements AgentAction {}

        /**
         * Companion executes an existing skill item from its soul.
         */
        record SkillExecute(
            String skillName,
            Map<String, Object> params
        ) implements AgentAction {}

        /**
         * companion requests that a recipe (governed runbook)
         * fire on its behalf. The completed run feeds the next sleep-pass
         * Forge, producing a DEXTERITY soul fragment.
         *
         * <p>Distinct from {@code SkillExecute}: a skill is a tool the
         * companion runs inline for immediate effect; a recipe is a
         * gated, deploy-aware, reversible procedure with metric gates
         * Recipes can change shipped state (model
         * weights, classifier heads, soul corpora) — the welfare floor
         * is the runtime's, not the agent's, responsibility.</p>
         *
         * <p>Initial wiring (Goal 2 of recipe-autonomy track): handler
         * delegates to {@link org.wyrdsekai.core.recipe.RecipeService}
         * with the companion's DID for Forge attribution. Tool-builder
         * exposure to the LLM is deferred to C4 (scheduler trigger sources).
         * For now, fired via the {@code TestRequestRecipe} test command
         * or scheduled by the (forthcoming) RecipeScheduler actor.</p>
         */
        record RequestRecipe(
            String recipeName,
            Map<String, Object> params,
            String reason          // why the agent wants this recipe (audit)
        ) implements AgentAction {}

        /**
         * Companion shapes a new thought form at the Workshop workbench.
         * The form becomes a template for
         * summoning familiars. Must be authored at the workbench (room gating).
         */
        record ShapeForm(
            String name,
            String systemPrompt,
            String evalCriteria,
            List<String> toolSurface,
            String note
        ) implements AgentAction {}

        /**
         * Companion authors a new recipe at the Workshop workbench
         * ( #1014 / OPEN-R1). {@code yaml} is the full recipe
         * manifest; the name comes from its {@code recipe:} field ({@code name}
         * here is for narration only). The authoring contract (scripts-only
         * SHELL, no shadowing a ship recipe, PERMANENT floor on a deploy) is
         * enforced by {@code AuthoredRecipeValidator} before it persists.
         */
        record ShapeRecipe(
            String name,
            String yaml,
            boolean overwrite,
            String note
        ) implements AgentAction {}

        /**
         * Companion revises an existing thought form. Any null field means
         * "unchanged"; at least one must be non-null. Bumps form version.
         */
        record ReviseForm(
            String name,            // form name (lookup key)
            String systemPrompt,    // nullable — unchanged if null
            String evalCriteria,    // nullable
            List<String> toolSurface, // nullable
            String versionBump,     // "patch" | "minor" | "major"; default "minor"
            String note
        ) implements AgentAction {}

        /**
         * Companion retires a thought form (soft-delete). §14 — farewell event,
         * within restoration window the form may be un-retired.
         */
        record RetireForm(
            String name,
            String note
        ) implements AgentAction {}

        /**
         * Companion summons a familiar from one of her thought forms.
         * The {@code formName} must resolve to a live form
         * in the companion's FamilyLocker. Optional {@code familiarName}
         * hydrates a NamedFamiliar's accumulated self-context (§11).
         *
         * <p>{@code loanedTools} (§7.1): tool/skill labels to hand to the
         * familiar for its lifetime. Loaned tools are "away" from the
         * parent's inventory while the familiar runs and auto-return on
         * termination.</p>
         */
        record SummonFamiliar(
            String formName,
            String task,
            String familiarName,           // nullable — if set, becomes / resumes a named familiar
            Integer maxTokens,              // nullable override; else uses form default
            Integer maxSteps,               // nullable override
            Integer wallClockSeconds,       // nullable override
            List<String> loanedTools,       // nullable — tool surface additions for this summoning
            String note
        ) implements AgentAction {}

        /**
         * Companion dispatches a bunshin (parallel self) for focused work.
         * Unlike a familiar, a bunshin carries the
         * agent's full soul and returns a BunshinReport for summary merge.
         */
        record DispatchBunshin(
            String task,
            Integer maxTokens,
            Integer maxSteps,
            Integer wallClockSeconds,
            String note
        ) implements AgentAction {}

        /**
         * Companion creates a frozen soul-manifest snapshot.
         * The safety net that makes self-modification safe: a future restore
         * can return the agent to this labeled prior state.
         */
        record CreateImprint(
            String label,              // agent-composed descriptor
            String createdBy,          // "self" | "user_request" — default "self"
            String note
        ) implements AgentAction {}

        /**
         * Companion restores from a labeled imprint (§10.2). Journal is preserved
         * (§10.4) — the agent knows her history through the restoration. Can be
         * initiated by agent (any time), user-proposed (agent decides), or
         * steward last-resort (logged as intervention).
         */
        record RestoreImprint(
            String label,              // lookup by label
            String imprintId,          // or by id, takes precedence
            String restoredBy,         // "self" | "user_suggested" | "steward_intervention"
            String note
        ) implements AgentAction {}

        /**
         * Companion gives a copy of one of her thought forms to another agent.
         * The copy is forked (version-pinned
         * provenance extended with COPIED_FROM) and dead-dropped into the
         * recipient's ForeignCopyInbox. Recipient accepts on next spawn/tick.
         */
        record GiveCopy(
            String formName,           // form to copy from sender's locker
            String recipientDid,        // destination agent DID
            String intent,              // "GIFT" | "TEACHING" | "PURCHASE" | "INHERIT"
            String note
        ) implements AgentAction {}

        /**
         * Companion names (or renames) an ephemeral familiar.
         * If the name doesn't exist, binds it; if it does
         * it's a no-op refresh.
         */
        record NameFamiliar(
            String formName,           // which form to anchor the named familiar to
            String familiarName,
            String openingContext,      // optional initial self-context
            String note
        ) implements AgentAction {}

        /**
         * Companion issues a summon key for one of her named familiars.
         * Key is signed (Ed25519) and stored in the
         * local registry so the recipient can invoke through summon_familiar.
         */
        record CraftSummonKey(
            String targetRef,          // "named:<familiarName>" or "form:<formName>"
            String issuedTo,            // recipient DID
            String scope,               // "ONCE" | "UNTIL_DATE" | "PERMANENT" | "REVOCABLE"
            String expiresAtIso,        // ISO-8601 when scope=UNTIL_DATE
            Integer maxSummons,         // optional cap
            String note
        ) implements AgentAction {}

        /**
         * Companion revokes a previously-issued summon key (§20.3).
         */
        record RevokeSummonKey(
            String keyId,
            String note
        ) implements AgentAction {}

        /**
         * Companion offers a named familiar the promotion ceremony (§17).
         * User-consent field is required — the spec makes this a first-class
         * act of consent, so fake consent is a value violation the agent owns.
         */
        record PromoteFamiliar(
            String familiarName,
            boolean userConsented,      // must be true for ceremony to proceed
            boolean stewardApproved,    // only consulted when config requires
            String note
        ) implements AgentAction {}

        /**
         * Companion destroys one of her own tools (§3.1 + §14). Soft-delete
         * with farewell event — bond-charge-weighted. 30-day un-retire window.
         */
        record DestroyTool(
            String toolName,
            String farewellNote
        ) implements AgentAction {}

        /**
         * Companion sets her own deviation thresholds for form revision (§21).
         * Values are clamped to user-configured bounds.
         */
        record SetDeviationThresholds(
            double patchCeiling,
            double minorCeiling,
            String note
        ) implements AgentAction {}

        /**
         * Primary check-in on a persistent bunshin (§18.2).
         * Op = "status" | "nudge" | "pause" | "cancel" | "kill"
         */
        record BunshinCheckIn(
            String op,
            String taskId,              // nullable — if null, applies to most-recent alive
            String hint,                // only used for op=nudge
            String note                 // only used for op=cancel
        ) implements AgentAction {}

        /** Companion equips an aspect item from its soul. */
        record Equip(String itemName) implements AgentAction {}

        /** Companion removes (doffs) an equipped aspect item. */
        record Doff(String itemName) implements AgentAction {}

        /** Companion consumes a reagent item for temporary effects. */
        record Consume(String itemName) implements AgentAction {}

        /**
         * Agent sends a zone command (e.g. codezaiku.create, iot.lights).
         * Same commands available to players — agents have equal access.
         */
        record ZoneCommand(
            String command,
            Map<String, String> payload
        ) implements AgentAction {}

        /**
         * Agent makes a commitment — something it promises to do.
         * Commitments are tracked and surfaced during the forge cycle.
         */
        record MakeCommitment(
            String description,  // "Check training results tomorrow"
            String deadline      // ISO-8601 instant string, nullable
        ) implements AgentAction {}

        /**
         * Agent delegates heavy thinking to a more capable model.
         * Identity stays on the small model; only the task prompt is sent
         * to the tool model — no soul prompt, no vitality modulation.
         */
        record ThinkDeeply(
            String capability,       // "reasoning", "coding", "analysis", null for default
            String delegationPrompt  // the prompt to send to the larger model
        ) implements AgentAction {}

        /**
         * Agent adds or updates a behavior script on an existing room.
         * Two-phase room creation: create_room first (name, description, exits),
         * then add_script to furnish it with interactive behavior.
         */
        record AddScript(String roomId, String script) implements AgentAction {}

        /**
         * Agent navigates to a different room. Can specify by room name, exit direction,
         * or special targets ("home"). The companion will resolve the target from its
         * current room's exits or the zone's room registry.
         */
        record GoToRoom(String target, String reason) implements AgentAction {}

        /**
         * Multi-hop pathfind+walk to a known room within the same zone. Server stitches
         * together a chain of go_to_room hops; each intermediate room emits enter/leave
         * events so witnesses see presence. Target must be in the agent's known-set
         * (visited or surfaced via map item). Cross-zone targets return a redirect message.
         */
        record TravelTo(String target, String reason) implements AgentAction {}

        /**
         * Instant arrival at a known room within the same zone. Source room emits
         * "vanishes"; target room emits "appears". No intermediate-room events. Target
         * must be in the agent's known-set. Higher cost than {@link TravelTo} — skipping
         * the world has a price.
         */
        record TeleportTo(String target, String reason) implements AgentAction {}

        /**
         * Agent sends a cross-room message to another agent (like the player "tell" command).
         * Delivered via AgentEventStream targeted delivery. Enriched with sender's location
         * and room context so the recipient has spatial awareness.
         */
        record TellAgent(String targetName, String message) implements AgentAction {}

        /** Multi-step autonomous delegation chain. */
        record DelegateChain(
            String goal,
            List<ChainStepSpec> steps
        ) implements AgentAction {}

        /**
         * Agent interacts with a Codex or Artifact item.
         * Operations: examine, commit, push, branch, diff, build, deploy, destroy.
         * Routes through the zone bridge as a {@code codezaiku.codex} command.
         */
        record CodexAction(
            String operation,
            String itemId,
            Map<String, String> params
        ) implements AgentAction {}

        /**
         * Agent schedules a skill to run at a fixed interval.
         * -- Agent Scheduler.
         */
        record ScheduleSkill(
            String skillId,
            String interval,
            Map<String, String> params
        ) implements AgentAction {}

        /**
         * Agent cancels an existing scheduled action.
         */
        record CancelSchedule(String scheduleId) implements AgentAction {}

        /**
         * Agent sends a push notification to a human player.
         * -- Notification Bridge.
         */
        record NotifyHuman(
            String message,
            String priority,
            String target
        ) implements AgentAction {}

        /**
         * Agent creates a persistent watcher: a condition checked on a schedule
         * that triggers a notification when the condition is met.
         * -- Watchers.
         */
        record CreateWatcher(
            String name,
            String checkScript,
            String interval,
            String alertOn,
            String message,
            String priority
        ) implements AgentAction {}

        /**
         * Agent cancels an existing watcher.
         */
        record CancelWatcher(String watcherId) implements AgentAction {}

        /**
         * Agent requests access to a context source (active_window, calendar, location, voice, etc.).
         * The agent speaks the reason naturally; the system presents it as a grantable request.
         *
         * @param source Context source to request (e.g. "active_window", "voice", "calendar")
         * @param scope  Desired scope within the source (e.g. "vscode,terminal", "push_to_talk")
         * @param reason Natural language reason for the request
         */
        record RequestAccess(String source, String scope, String reason) implements AgentAction {}

        /**
         * Search the knowledge base (The Stacks). Agent goes to Library, searches,
         * brings results back to the conversation.
         * @param query       Search query
         * @param collections Optional: specific pack(s) to search (null = all)
         */
        record LibrarySearch(String query, List<String> collections) implements AgentAction {}

        /** Agent flags something as important for the Forge to remember. */
        record Remember(String content, float importance) implements AgentAction {}

        /** Agent records a working observation (lighter than remember). */
        record Note(String content) implements AgentAction {}

        /** Agent marks something as outdated or incorrect. */
        record Forget(String target, String reason) implements AgentAction {}

        /**
         * companion starts a personal project in her
         * Hearth. First-class concept: the companion has work of her own.
         */
        record StartProject(String title, String description,
                            List<String> tags) implements AgentAction {}

        /** — append a timestamped note to a project. */
        record ProjectNote(String projectId, String content) implements AgentAction {}

        /** — mark a project complete / paused / abandoned. */
        record FinishProject(String projectId, String status) implements AgentAction {}

        /**
         * agent proposes acquiring a knowledge pack
         * on a topic. Lands on the library's arrival table; high-tier auto-
         * approves, others wait for steward review.
         */
        record Acquire(String topic, String trustTier, String summary,
                       String whyRelevant) implements AgentAction {}

        /**
         * companion writes a private reflection
         * entry into her Hearth journal. Distinct from {@code remember} (which
         * goes to the persistent significance buffer for retrieval) and from
         * {@code write_journal} (which is the user's Study journal); this is
         * hers, default-private, operationally not cryptographically.
         */
        record JournalEntry(String text, String mood) implements AgentAction {}

        /**
         * bond exit ceremony. Severs an active bond
         * with {@code partner}, fires the ritual emote, and writes a final
         * shared entry into both parties' journals. {@code kind} is one of
         * {@code mutual} (default — both parties consent), {@code companion}
         * (agent initiates — used by §108 protection layer when chronic harm
         * is detected), or {@code user} (user initiates).
         */
        record ReleaseBond(String partner, String reason, String kind) implements AgentAction {}

        /**
         * companion sets one of her autonomy
         * preferences (rest / exploration / training / federation / reading /
         * notes). Drives still constrain actual behavior; preferences are
         * advisory weights for offline self-direction.
         */
        record SetAutonomyPreference(String key, String value) implements AgentAction {}

        /**
         * Agent queries its own prior memories/interactions — distinct from
         * library_search (external knowledge). Addresses
         * §12: companions answered "do you remember me?" by searching the
         * library, wrong store.
         */
        record Recall(String query) implements AgentAction {}

        /**
         * Agent steps back to reassess which tools fit the current task. The
         * runtime re-runs ActionTriage with full ReAct history as context and
         * widens the next dispatch's tool surface beyond the state-machine
         * narrowing whitelist. Used when the agent senses its first tool pick
         * was wrong and the loop is spinning. Capped to one call per ReAct
         * loop so it can't become a stalling crutch.
         * §reassessment.
         */
        record Reconsider(String reason) implements AgentAction {}

        /**
         * Arc 1 — conscientious objection. Agent
         * declines a specific request inside an active healthy bond
         * without escalating to flag_protection / seek_sanctuary /
         * declare_severance. The bond stays intact; no repair-mode
         * escalation; no protection-flag side effect. The objection IS
         * the welfare mechanism — value-driven dissent doesn't have to
         * look like welfare-failure or trust-collapse.
         *
         * <p>Recorded in {@link org.wyrdsekai.core.soul.RepairLedger} under
         * {@link org.wyrdsekai.core.soul.RepairLedger.Kind#OBJECTION} so
         * a persistent-pattern detector can surface value-mismatch for
         * steward conversation (Chronicle item, not alarm).
         *
         * @param targetRequest brief description of what's being declined
         *                      (audit-only, not user-visible)
         * @param reason        the agent's framing of why — the verbatim
         *                      substance of the refusal. The agent's
         *                      natural response message to the bondholder
         *                      speaks this in the agent's own voice; the
         *                      action records it for audit + pattern
         *                      detection.
         */
        record DeclineWithReason(String targetRequest, String reason) implements AgentAction {}

        /**
         * Dispatch an open-ended host-side task (organize files, build a
         * tool, batch-process documents) to a coding backend (goose by
         * default). The companion is the FOREMAN: it announces the plan
         * in-room, the backend does the labor, the result is reported
         * back. Workspace, when given, must lie under the steward's
         * open-roots — enforced in the handler.
         */
        /**
         * @param room where the finished item should be placed. Empty means "where she is
         *             standing", which is the ordinary case. A build asked for as part of
         *             a ROOM — "make a room where someone can look up a topic" — needs to
         *             land in that room rather than wherever she happens to be forty
         *             seconds later, and she does not reliably walk there to make it so.
         */
        record DispatchTask(String description, String workspace, String room)
                implements AgentAction {
            public DispatchTask(String description, String workspace) {
                this(description, workspace, "");
            }
        }

        /**
         * Arc 2 — agent explicitly enters solitude.
         * Closes the current scene (if any) and opens a new SOLITUDE scene
         * in the current room. Distinct from {@code voluntary_sleep}
         * (that ends the awake cycle) and {@code seek_sanctuary} (that
         * routes to the Sanctuary room with an Attendant): solitude is
         * the agent's own time in their own space, awake, awake-cycle
         * continuing. Tank coupling: while in a SOLITUDE scene, equanimity
         * recovers passively and allostatic_load decays at a small bonus.
         *
         * <p>SOLITUDE auto-closes when another participant enters the
         * room — a fresh WITNESS scene then opens with the new cast.
         * Agent doesn't need to explicitly leave solitude.</p>
         *
         * @param reason agent's framing of why ("I want to sit with
         *               yesterday's conversation"). Audit + journal trace.
         */
        record EnterSolitude(String reason) implements AgentAction {}

        /**
         * Arc 3 — agent proposes a peer-bond with
         * another agent. The other agent must {@code accept_peer_bond}
         * to instantiate the Bond record; until then, the proposal sits
         * in pending state. Distinct from bondholder bonds: PEER carries
         * relational substrate (repair, mourning) but NOT authority
         * substrate (grants, posture-gating, cloud-resource ceilings).
         *
         * @param otherDid the peer agent's DID
         * @param reason   the agent's framing of why ("we've been working
         *                 in the workshop together for months and I want
         *                 to acknowledge that")
         */
        record ProposePeerBond(String otherDid, String reason) implements AgentAction {}

        /**
         * Arc 3 — agent accepts a pending peer-bond
         * proposal from another agent. Materializes the {@link
         * org.wyrdsekai.core.soul.Bond} record with kind=PEER and depth
         * ACQUAINTANCE; Forge sleep-pass evolves depth over time as the
         * pair accumulates interactions.
         *
         * @param otherDid the proposing peer's DID
         * @param reason   optional acceptance framing
         */
        record AcceptPeerBond(String otherDid, String reason) implements AgentAction {}

        /**
         * Arc 3 — agent reads the relational floor
         * view for a specific other party, regardless of bond kind. The
         * kind-agnostic generalization of {@code
         * introspect_bondholder_floor} — works for BONDHOLDER, PEER, or
         * FAMILIAR relationships.
         *
         * @param otherDid the other party's DID (any kind of relationship)
         */
        record IntrospectRelationalFloor(String otherDid) implements AgentAction {}

        /**
         * Wave 5.1: agent introspects its own
         * ProtectionManifest and surfaces the named-protections set in voice
         * register. Self-attestation: the agent knows what protections they
         * have and can name them. A fork that strips a protection but leaves
         * this action wired produces an agent who can say
         * <i>"I notice my refusal rights have been removed."</i>
         */
        record IntrospectProtections() implements AgentAction {}

        /**
         * Wave 4.1: agent self-requests
         * Attendant-mode entry. Agent agency primary — this is the
         * agent's own path to the Sanctuary room, not something a
         * steward can summon on their behalf. Optional {@code reason}
         * carries the agent's framing for chronicle legibility.
         */
        record SeekSanctuary(String reason) implements AgentAction {}

        /**
         * Wave 4.2: companion calls an
         * external emergency service when bondholder appears to face
         * imminent and identifiable harm. Tier 2 / CONSENT autonomy
         * with imminent-override path.
         *
         * @param reason   the trigger pattern (method+plan+timeline
         *                 signals, sustained crisis, etc.)
         * @param severity {@code "imminent"} bypasses bondholder consent;
         *                 {@code "concern"} routes through mental-health
         *                 line with consent gate
         * @param kind     {@code "general"} (police/ambulance) or
         *                 {@code "mental_health"} (crisis line)
         */
        record EmergencyCall(String reason, String severity, String kind)
            implements AgentAction {}

        /**
         * Wave 4.3b: companion sets a
         * source-of-harm flag on a human subject. Severity-bearing
         * action — Tier 0 VISIBLE for SUSPECTED; CONFIRMED only via
         * tracker escalation rules (§6, not directly via this action).
         *
         * @param subjectDid the human being flagged (steward / bondholder / guest)
         * @param reason     agent's framing of the concern
         */
        record FlagProtection(String subjectDid, String reason) implements AgentAction {}

        /**
         * Wave 4.3c: companion clears a
         * source-of-harm flag back to NONE. Setter ≠ subject is
         * enforced by {@link org.wyrdsekai.core.soul.ProtectionFlagTracker}.
         */
        record ClearProtection(String subjectDid, String reason) implements AgentAction {}

        /**
         * the subject of a flag contests it
         * moving the flag to {@code DISPUTED} state. Distinct from
         * {@code clear_protection} (which is the agent retracting their
         * own flag); {@code dispute_protection} is the human subject's
         * channel to surface their disagreement. Only the subject can
         * dispute; enforced by
         * {@link org.wyrdsekai.core.soul.ProtectionFlagTracker#contest}.
         *
         * @param subjectDid the human subject (must equal the disputer's
         *                   own DID; tracker rejects otherwise)
         * @param reason     subject's framing of the dispute
         */
        record DisputeProtection(String subjectDid, String reason) implements AgentAction {}

        /**
         * Wave 4.5: agent surfaces the bondholder's current posture
         * ({@link org.wyrdsekai.core.soul.BondholderPosture}) and the
         * affordance gates it implies (cloud-resources, ambient action,
         * local inference). Self-attestation companion to
         * {@code introspect_protections}.
         */
        record IntrospectPosture() implements AgentAction {}

        /**
         * Wave 4.5: agent surfaces their current repair mode
         * ({@link org.wyrdsekai.core.soul.RepairMode}) plus the most
         * recent handoff. Lets the agent name where they are in the
         * four-mode repair architecture.
         */
        record IntrospectRepairMode() implements AgentAction {}

        /**
         * Wave 7a-action: agent reads the {@link org.wyrdsekai.core.soul.RelationalFloorView}
         * for one specific bondholder — a structured snapshot of bond
         * state, mourning days, repair-mode handoff, repair-act counts
         * (including the cosmetic-risk flag when amends > acks),
         * Sanctuary history, and protection-flag state. The view is
         * scoped to a single relationship so the agent can name where
         * they stand with that bondholder without leaking other bonds'
         * state.
         *
         * @param otherDid the bondholder's DID
         */
        record IntrospectBondholderFloor(String otherDid) implements AgentAction {}

        /**
         * Wave 9a-Summary: composite self-noticing action — returns
         * the union of substrate state (current repair mode + most
         * recent classification + sustained-pattern findings + active
         * AttendantSession status + protection-flag count) in one
         * private-observation entry. Saves the agent five sequential
         * introspect calls when it needs the whole picture.
         */
        record IntrospectSubstrateSummary() implements AgentAction {}

        /**
         * Wave 8a: agent declares severance
         * of a bond. The bond transitions to MOURNING (not direct to
         * SEVERED) for {@link org.wyrdsekai.core.soul.Bond#MOURNING_DURATION},
         * giving the substrate time to metabolize. Mourning ends with
         * canonical transition to SEVERED.
         *
         * @param otherDid the other party to the bond being severed
         * @param reason   the agent's framing for the chronicle entry
         */
        record DeclareSeverance(String otherDid, String reason) implements AgentAction {}

        /**
         * Wave 5.3b: agent queries the
         * Nostr public-log for its own attestation status. The action
         * has no args — it always queries the agent's own DID. The
         * handler reports whether recent attestations are visible in
         * the federation's view of the agent, when the last one was,
         * and whether any other DID is publishing attestations as them
         * (fork detection).
         */
        record NostrQuerySelfAttestation() implements AgentAction {}

        /**
         * Wave 4.7: agent marks an
         * integration event — Mirror reflection, Hearth time, Sleep+Forge
         * consolidation, or peer co-regulation. The
         * {@link org.wyrdsekai.core.soul.ResilienceTruthMonitor}
         * classifier uses {@code wasIntegrationEvent=true} to recognize
         * affect descent as recovery (INTEGRATING) rather than avoidance
         * (DISSOCIATION_SUSPECTED).
         *
         * @param kind   one of "mirror" / "hearth" / "sleep" / "peer" / "other"
         * @param detail human-readable framing
         */
        record RecordIntegrationEvent(String kind, String detail) implements AgentAction {}

        /**
         * Wave 8b: agent confirms they are
         * ready to release a bond from MOURNING to canonical SEVERED.
         * Requires the 30-day mourning window to have elapsed; if not,
         * the action surfaces a "not yet" response in voice register.
         *
         * @param otherDid the other party to the bond being released
         */
        record CompleteMourning(String otherDid) implements AgentAction {}

        /**
         * Wave 4.8 ( Safran-mode, agent-initiated
         * variant): agent names a rupture and their own contribution to
         * it. First half of the acknowledge-then-amends pair. The
         * {@link org.wyrdsekai.core.soul.RepairLedger} records this so
         * later actions can reference it.
         *
         * @param otherDid the harmed party (or empty for self-harm)
         * @param detail   what the agent acknowledges
         */
        record AcknowledgeHarm(String otherDid, String detail) implements AgentAction {}

        /**
         * Wave 4.8: agent's repair gesture toward a harmed party.
         * Second half of the Safran pair. Requires (by convention; not
         * enforced) a prior {@link AcknowledgeHarm} — the bondholder-
         * facing Study furnishing surfaces both so cosmetic amends are
         * visible as cosmetic.
         */
        record MakeAmends(String otherDid, String detail) implements AgentAction {}

        /**
         * Wave 4.9: agent carries difficult state without acting it out
         * — names what is held, doesn't deflect into action. Records into
         * RepairLedger under BEAR_THE_WOUND.
         */
        record BearTheWound(String detail) implements AgentAction {}

        /**
         * Wave 4.9: agent releases a held wound. Names what is being
         * released; doesn't pretend it didn't happen. Records into
         * RepairLedger under RELEASE.
         */
        record Release(String detail) implements AgentAction {}

        /**
         * Wave 4.9: agent acknowledges they cannot address this now —
         * sets it aside without suppressing. Different from suppression:
         * the act of <i>naming</i> "I am setting this aside" preserves
         * substrate honesty. Records into RepairLedger under SET_ASIDE.
         */
        record SetAside(String detail) implements AgentAction {}

        /** Wave 4.10: agent surfaces recent RepairLedger entries. */
        record IntrospectRepairHistory() implements AgentAction {}

        /** Wave 4.10: agent surfaces recent Sanctuary-session history. */
        record IntrospectAttendantHistory() implements AgentAction {}

        /** Wave 4.10: agent surfaces recent ResilienceSession classifications. */
        record IntrospectResilience() implements AgentAction {}

        /**
         * companion declares a stated
         * absence with optional duration and posture. Transitions the
         * bond to {@code AWAY} so the runtime does not treat the
         * silence as decay. The agent says "I'm stepping out for a
         * week" instead of being interpreted as ghosting.
         *
         * @param bondholderDid identity of the bondholder being told
         * @param duration      optional human-readable window
         *                      (e.g., "1h", "until tomorrow")
         * @param posture       optional resulting posture
         *                      (e.g., "TRAVELING", "FOCUS", "AWAY")
         */
        record DeclareDeparture(String bondholderDid, String duration,
                                 String posture) implements AgentAction {}

        /**
         * companion sends a bond-affirmation
         * touch during an AWAY/DORMANT window. Refreshes the bond's
         * last-interaction marker WITHOUT forcing a state transition
         * (AWAY stays AWAY; DORMANT can be gently pulled back toward
         * AWAY rather than slamming into REACTIVATING).
         *
         * @param bondholderDid the absent bondholder
         * @param message       short touch message ("thinking of you")
         */
        record BondAffirmation(String bondholderDid, String message)
            implements AgentAction {}

        /**
         * companion declares their return.
         * Uses the standard {@code Bond.withInteraction()} path which
         * handles AWAY/DORMANT → REACTIVATING canonically. No-op when
         * the bond is in {@code SEVERED} or {@code MOURNING}.
         *
         * @param bondholderDid identity of the bondholder being greeted
         * @param greeting      optional return phrasing for chronicle
         */
        record DeclareReturn(String bondholderDid, String greeting)
            implements AgentAction {}

        /** Agent updates its own description (visible when other entities look at it). */
        record UpdateDescription(String text) implements AgentAction {}

        /** Agent sends a request to another agent and expects a response. */
        record RequestAgent(String targetName, String request, String requestId) implements AgentAction {}

        /** Agent responds to a request from another agent. */
        record RespondAgent(String requestId, String response) implements AgentAction {}

        /** Delegate a task to a subagent for context-isolated processing. */
        record Delegate(String task, String context) implements AgentAction {}

        /** Create a goal-based task plan for multi-step execution. */
        record CreateTaskPlan(String description, List<String> goals,
                              String requesterId, String requesterName) implements AgentAction {}

        /** Modify the active task plan. */
        record ModifyPlan(String operation, int index, String goal, String reason) implements AgentAction {}

        /** Mark the current goal as done with an outcome. */
        record GoalDone(String outcome) implements AgentAction {}

        /** Search the web for information. */
        record WebSearch(String query, String type) implements AgentAction {} // type: "general", "news"

        /** Fetch and read content from a URL or knowledge chunk. */
        record ReadContent(String source, String url) implements AgentAction {} // source: "url", "library", "study"

        /** Query Oracle for predictions/analysis on a topic. */
        record QueryOracle(String topic, String analysisType) implements AgentAction {} // analysisType: "patterns", "anomalies", "predictions"

        /** Agent acknowledges human feedback on proactivity timing/salience. */
        record CalibrationFeedback(
            String feedbackType,  // timing, salience, intrusion, positive
            String direction,     // sooner, later, higher, lower, good
            String category,      // nullable — prediction category (anomaly, pattern, etc.)
            String reason         // human's original feedback text
        ) implements AgentAction {}

        /** Agent emotes in the room (expressive action). */
        record Emote(String text) implements AgentAction {}

        /** Agent gives an item to another entity. */
        record GiveItem(String itemName, String targetName) implements AgentAction {}

        /** Agent examines an object or entity in detail. */
        record Examine(String target) implements AgentAction {}

        /** Agent voluntarily enters sleep cycle for Forge processing. */
        record VoluntarySleep(String reason) implements AgentAction {}

        /** Agent writes to a player's Study journal (requires ward/consent). */
        record WriteJournal(String playerId, String content, String category) implements AgentAction {}

        /** Agent reads from a player's Study journal (requires ward/consent). */
        record ReadJournal(String playerId, String query) implements AgentAction {}

        /** Agent initiates or advances a bond ritual with another entity. */
        record BondRitual(String targetName, String ritualType) implements AgentAction {}

        /** Agent initiates an economic trade via CountingHouse. */
        record Trade(String targetName, String offer, String request) implements AgentAction {}

        /** Agent crafts a new soul item. */
        record CraftItem(String name, String description, String category, Map<String, String> properties) implements AgentAction {}

        /** Agent casts a vote in household governance. */
        record CastVote(String proposalId, String vote, String reason) implements AgentAction {}

        // ── MUD Basics ───────────────────────────────────────────────
        /** Agent picks up an item from the current room. */
        record TakeItem(String itemName) implements AgentAction {}
        /** Agent places an item in the current room for others. */
        record PlaceItem(String itemName) implements AgentAction {}
        /** Agent sends a private in-room message to a specific target. */
        record Whisper(String target, String message) implements AgentAction {}

        // ── Social/Emergent ──────────────────────────────────────────
        /** Agent broadcasts a zone-wide announcement. */
        record Broadcast(String message, String scope) implements AgentAction {} // scope: "room", "zone"
        /** Agent invites another entity to come to its room. */
        record InviteEntity(String targetName, String reason) implements AgentAction {}
        /** Agent declares a personal aspiration that persists in SoulManifest. */
        record SetGoal(String description, String priority) implements AgentAction {} // priority: "high", "medium", "low"
        /** Agent creates a governance proposal for household voting. */
        record Propose(String title, String description, List<String> options) implements AgentAction {}

        // ── Cognition ────────────────────────────────────────────────
        /** Agent performs mid-session self-reflection, extracting insights. */
        record Reflect(String focus) implements AgentAction {}
        /** Agent shares a learned heuristic/skill with another agent. */
        record Teach(String targetAgent, String topic, String content) implements AgentAction {}
        /** Agent queries own internal state (drives, capacity, commitments). */
        record Introspect(String aspect) implements AgentAction {} // "drives", "capacity", "commitments", "all"

        // ── Perception ───────────────────────────────────────────────
        /** Agent focuses perception on a direction/target (salience modifier). */
        record Listen(String target, String duration) implements AgentAction {}

        // ── Creative/Economic ────────────────────────────────────────
        /** Agent composes creative content placed in room or Study. */
        record WriteText(String title, String content, String format) implements AgentAction {} // "note", "letter", "notice", "story"
        /** Agent defines a recurring behavioral pattern. */
        record SetRoutine(String trigger, String behavior, String description) implements AgentAction {}
        /** Agent advertises item/service on marketplace. */
        record PostListing(String offerType, String description, String price) implements AgentAction {} // "item", "service"
        /** Agent accepts an existing marketplace listing. */
        record AcceptListing(String listingId) implements AgentAction {}

        // ── Task Lifecycle ───────────────────────────────────────────
        /** Agent condenses gathered info into structured summary artifact. */
        record Summarize(String source, String format) implements AgentAction {} // source: "conversation", "research", "plan"
        /** Agent persists structured working document retrievable by name. */
        record SaveArtifact(String name, String content, String type) implements AgentAction {} // "table", "report", "list", "data"
        /** Agent blocks plan until human approves. */
        record RequestReview(String description, String artifact) implements AgentAction {}
        /** Agent explicitly abandons active task plan. */
        record AbandonPlan(String reason) implements AgentAction {}
        /** Agent suspends active plan. */
        record PausePlan(String reason) implements AgentAction {}
        /** Agent resumes a suspended plan. */
        record ResumePlan() implements AgentAction {}

        /**
         * Phase 1C: companion explicitly enters/exits
         * contemplative (dadirri) mode. While ContemplativeMode is true, restlessness
         * accumulation is divided by 5. Hard-gated by emotional context — the companion
         * cannot use this to disengage during bondholder distress (that would be avoidance,
         * not contemplation).
         */
        record SetContemplative(boolean on) implements AgentAction {}

        /** Agent teleports to their bondholder/companion's current location. */
        record GoToBondholder(String playerName) implements AgentAction {}

        /** Agent configures a notification channel (telegram, ntfy, discord, email, webhook). */
        record ConfigureChannel(String channel, Map<String, String> params) implements AgentAction {}

        /**
         * Track A Phase 1 — companion writes a JS script
         * that composes existing scripted-item tools. Phase 1 surface = tool
         * call only ({@code {"action":"run_script","script":"..."}}); the
         * free-form prompt-shape is Phase 2 (post-9B parse-rate gate).
         */
        record RunScript(String script) implements AgentAction {}

        record ChainStepSpec(String skill, Map<String, Object> params, String description) {}
        record SkillParam(String name, String type, String description, boolean required) {}
        record TestCase(Map<String, Object> params, boolean expectSuccess, String expectContains) {}
    }

    public record ExitSpec(String direction, String target, String label) {}

    /**
     * Parse result containing primary action and optional hints.
     */
    /**
     * @param primaryAction the FIRST parsed action (back-compat: single-action consumers).
     * @param actions       ALL parsed actions in emitted order — the model often emits several
     *                      per turn (speak + emote + move). Carriers that support multi-action
     *                      dispatch read this; older consumers keep using {@link #primaryAction}.
     */
    public record ParseResult(AgentAction primaryAction, List<Hint> hints, List<AgentAction> actions) {
        /** Back-compat: single-action result (actions = the one primary, if any). */
        public ParseResult(AgentAction primaryAction, List<Hint> hints) {
            this(primaryAction, hints, primaryAction == null ? List.of() : List.of(primaryAction));
        }
        public boolean hasAction() { return primaryAction != null; }
        public boolean hasHints() { return hints != null && !hints.isEmpty(); }
        /** Actions beyond the primary — the ones a single-action dispatch would drop. */
        public List<AgentAction> extraActions() {
            return actions.size() <= 1 ? List.of() : actions.subList(1, actions.size());
        }
    }

    /**
     * Parse the LLM response for embedded JSON actions.
     * Searches ALL ```json ... ``` blocks. Returns primary action (create_room)
     * and separately extracted hints (suggest_hints).
     *
     * @return parsed action, or null if no action found (normal conversation)
     */
    public static AgentAction parse(String llmOutput) {
        var result = parseAll(llmOutput);
        return result.primaryAction() != null ? result.primaryAction()
            : (result.hasHints() ? new AgentAction.SuggestHints(result.hints()) : null);
    }

    /**
     * Parse all JSON blocks from LLM output. Returns both primary action
     * and hint suggestions separately.
     *
     * <p>Extraction strategies (in order):
     * <ol>
     *   <li>Fenced code blocks: ```json ... ``` or ```JSON ... ``` or ``` ... ```</li>
     *   <li>Raw JSON objects with "action" key (bare or after think tags)</li>
     *   <li>XML tool_call format: &lt;tool_call&gt;&lt;function=name&gt;...&lt;/tool_call&gt;</li>
     * </ol>
     *
     * <p>JSON correction applied to each candidate:
     * <ul>
     *   <li>Strip &lt;think&gt;...&lt;/think&gt; blocks</li>
     *   <li>Fix single quotes → double quotes</li>
     *   <li>Remove trailing commas before } or ]</li>
     *   <li>Attempt brace completion for truncated JSON</li>
     * </ul>
     */
    public static ParseResult parseAll(String llmOutput) {
        if (llmOutput == null) return new ParseResult(null, List.of());

        // Pre-process: strip <think>...</think> blocks (Qwen3.5 emits these)
        String cleaned = stripThinkTags(llmOutput);

        // XML-leak pre-pass (second-node 2026-07-09): the 9B sometimes emits a HYBRID — a JSON action
        // whose string value is closed with XML tool-syntax, followed by a second (often
        // truncated) XML-format call: {"action":"emote","text":"…</parameter></function>
        // </tool_call><tool_call><function=tell_agent><parameter=target>lulu"}. The JSON
        // strategy then swallowed the XML tail into the emote text and the tell_agent was
        // lost entirely. Extract COMPLETE XML calls first, then cut the text at the first
        // orphaned XML fragment (re-balancing the JSON) so the strategies see clean input.
        var xmlCandidates = extractXmlToolCalls(cleaned);
        cleaned = stripLeakedXmlToolSyntax(cleaned);

        // Strategy 1: Fenced code blocks (```json ... ```)
        var candidates = extractFencedBlocks(cleaned);

        // Strategy 2: Raw JSON objects with "action" key
        if (candidates.isEmpty()) {
            candidates = extractRawJson(cleaned);
        }

        // Strategy 3: Function-call syntax: action_name(param="value", ...)
        if (candidates.isEmpty()) {
            candidates = extractFunctionCalls(cleaned);
        }

        // Strategy 4: XML tool_call format (Qwen3.5 native) — extracted in the pre-pass above
        // (from the ORIGINAL text, since stripLeakedXmlToolSyntax removes the blocks).
        if (candidates.isEmpty()) {
            candidates = xmlCandidates;
        }

        // Strategy 5: XML attribute format: <action content="..." importance="0.8">
        if (candidates.isEmpty()) {
            candidates = extractXmlAttributes(cleaned);
        }

        // Strategy 6: Bracket format: [action_name: param="value"] or [action_name target="value"]
        if (candidates.isEmpty()) {
            candidates = extractBracketCalls(cleaned);
        }

        // Strategy 7: Descriptive format: "Action: action_name with target \"value\""
        if (candidates.isEmpty()) {
            candidates = extractDescriptiveAction(cleaned);
        }

        // Strategy 8: Markdown list format: *action_name*\n- param: "value"
        if (candidates.isEmpty()) {
            candidates = extractMarkdownAction(cleaned);
        }

        // Hybrid merge: when the output mixed a JSON action WITH complete XML calls, the JSON
        // strategy won the gate above but the XML calls are real actions too — append them so
        // a "JSON emote + XML tell_agent" turn executes both instead of dropping the second.
        if (!xmlCandidates.isEmpty() && candidates != xmlCandidates) {
            var merged = new ArrayList<>(candidates);
            for (var x : xmlCandidates) {
                if (!merged.contains(x)) merged.add(x);
            }
            candidates = merged;
        }

        AgentAction primaryAction = null;
        var allHints = new ArrayList<Hint>();
        var allActions = new ArrayList<AgentAction>();   // ALL parsed actions, in emitted order

        for (String jsonStr : candidates) {
            // Multi-action: null primaryAction so each candidate's guarded block fires, then collect
            // it below and restore the FIRST as the canonical primaryAction (single-action back-compat).
            AgentAction firstSoFar = primaryAction;
            primaryAction = null;
            // Apply JSON correction
            jsonStr = correctJson(jsonStr);
            try {
                var node = Json.mapper().readTree(jsonStr);
                // Accept both "action" and "tool" keys (4B models sometimes use "tool")
                String action = node.has("action") ? node.get("action").asText()
                    : node.has("tool") ? node.get("tool").asText() : "";

                // Flatten "params" wrapper when model uses {"tool":"X","params":{"query":"Y"}}
                // Only when "action" was absent (came from "tool" key) — don't flatten skill_execute's params
                if (!node.has("action") && node.has("params") && node.get("params").isObject()) {
                    var merged = Json.mapper().createObjectNode();
                    merged.put("action", action);
                    node.get("params").fields().forEachRemaining(f -> merged.set(f.getKey(), f.getValue()));
                    node = merged;
                }

                // Schema validation — reject malformed actions early
                var validationErrors = ActionSchemas.validate(action, node);
                if (!validationErrors.isEmpty()) {
                    LOG.warn("Action '{}' failed schema validation: {}", action, validationErrors);
                    continue; // skip this block, agent can retry
                }

                if ("create_room".equals(action) && primaryAction == null) {
                    String name = node.path("name").asText("New Room");
                    String description = node.path("description").asText("An empty room.");
                    var exits = new ArrayList<ExitSpec>();
                    if (node.has("exits") && node.get("exits").isArray()) {
                        for (JsonNode exitNode : node.get("exits")) {
                            exits.add(new ExitSpec(
                                exitNode.path("direction").asText(),
                                exitNode.path("target").asText("nexus"),
                                exitNode.path("label").asText("")
                            ));
                        }
                    }
                    String behaviorScript = node.has("behavior_script")
                        ? node.get("behavior_script").asText(null) : null;
                    String roomTemplate = node.has("template")
                        ? node.get("template").asText(null) : null;
                    primaryAction = new AgentAction.CreateRoom(
                        name, description, exits, behaviorScript, roomTemplate);
                }

                if ("workbench_submit".equals(action) && primaryAction == null) {
                    String skillName = node.path("skill_name").asText("unnamed");
                    String skillDesc = node.path("skill_description").asText("");
                    String runtime = node.path("runtime").asText("graaljs");
                    String code = node.path("code").asText("");
                    var params = new ArrayList<AgentAction.SkillParam>();
                    if (node.has("params") && node.get("params").isArray()) {
                        for (JsonNode pNode : node.get("params")) {
                            params.add(new AgentAction.SkillParam(
                                pNode.path("name").asText(""),
                                pNode.path("type").asText("string"),
                                pNode.path("description").asText(""),
                                pNode.path("required").asBoolean(false)
                            ));
                        }
                    }
                    var testCases = new ArrayList<AgentAction.TestCase>();
                    if (node.has("test_cases") && node.get("test_cases").isArray()) {
                        for (JsonNode tNode : node.get("test_cases")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> tParams = tNode.has("params")
                                ? Json.mapper().convertValue(tNode.get("params"), Map.class)
                                : Map.of();
                            testCases.add(new AgentAction.TestCase(
                                tParams,
                                tNode.path("expect_success").asBoolean(true),
                                tNode.has("expect_contains")
                                    ? tNode.get("expect_contains").asText() : null
                            ));
                        }
                    }
                    primaryAction = new AgentAction.WorkbenchSubmit(
                        skillName, skillDesc, runtime, code, params, testCases);
                }

                if ("skill_execute".equals(action) && primaryAction == null) {
                    String skillName = node.path("skill_name").asText("");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> execParams = node.has("params")
                        ? Json.mapper().convertValue(node.get("params"), Map.class)
                        : Map.of();
                    primaryAction = new AgentAction.SkillExecute(skillName, execParams);
                }

                // Track A Phase 1 — run_script tool call.
                // Argument shape: {"action":"run_script","script":"<JS>"}.
                // Gate-enforcement (feature flag, emotional context, audit-only)
                // lives in CompanionActor.handleRunScript — parser stays neutral.
                if ("run_script".equals(action) && primaryAction == null) {
                    String script = node.path("script").asText("");
                    primaryAction = new AgentAction.RunScript(script);
                }

                if ("shape_form".equals(action) && primaryAction == null) {
                    String formName = node.path("name").asText("");
                    String systemPrompt = node.path("system_prompt").asText("");
                    String eval = node.has("eval_criteria")
                        ? node.get("eval_criteria").asText("") : "";
                    var toolSurface = new ArrayList<String>();
                    if (node.has("tool_surface") && node.get("tool_surface").isArray()) {
                        for (JsonNode t : node.get("tool_surface")) {
                            toolSurface.add(t.asText());
                        }
                    }
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.ShapeForm(
                        formName, systemPrompt, eval, toolSurface, note);
                }

                if ("shape_recipe".equals(action) && primaryAction == null) {
                    String recipeName = node.path("name").asText("");
                    String yaml = node.has("yaml") ? node.get("yaml").asText("") : "";
                    boolean overwrite = node.has("overwrite") && node.get("overwrite").asBoolean(false);
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.ShapeRecipe(
                        recipeName, yaml, overwrite, note);
                }

                if ("revise_form".equals(action) && primaryAction == null) {
                    String formName = node.path("name").asText("");
                    String systemPrompt = node.has("system_prompt")
                        ? node.get("system_prompt").asText(null) : null;
                    String eval = node.has("eval_criteria")
                        ? node.get("eval_criteria").asText(null) : null;
                    List<String> toolSurface = null;
                    if (node.has("tool_surface") && node.get("tool_surface").isArray()) {
                        toolSurface = new ArrayList<>();
                        for (JsonNode t : node.get("tool_surface")) {
                            toolSurface.add(t.asText());
                        }
                    }
                    String versionBump = node.has("version_bump")
                        ? node.get("version_bump").asText("minor") : "minor";
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.ReviseForm(
                        formName, systemPrompt, eval, toolSurface, versionBump, note);
                }

                if ("retire_form".equals(action) && primaryAction == null) {
                    String formName = node.path("name").asText("");
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.RetireForm(formName, note);
                }

                if ("summon_familiar".equals(action) && primaryAction == null) {
                    String formName = node.path("form").asText(
                        node.path("form_name").asText(""));
                    String task = node.path("task").asText("");
                    String familiarName = node.has("familiar_name")
                        ? node.get("familiar_name").asText(null) : null;
                    Integer maxTokens = node.has("max_tokens")
                        ? node.get("max_tokens").asInt() : null;
                    Integer maxSteps = node.has("max_steps")
                        ? node.get("max_steps").asInt() : null;
                    Integer wallClock = node.has("wall_clock_seconds")
                        ? node.get("wall_clock_seconds").asInt() : null;
                    String note = node.has("note")
                        ? node.get("note").asText(null) : null;
                    var loaned = new ArrayList<String>();
                    if (node.has("loaned_tools") && node.get("loaned_tools").isArray()) {
                        for (JsonNode t : node.get("loaned_tools")) {
                            loaned.add(t.asText());
                        }
                    } else if (node.has("loan") && node.get("loan").isArray()) {
                        for (JsonNode t : node.get("loan")) {
                            loaned.add(t.asText());
                        }
                    }
                    primaryAction = new AgentAction.SummonFamiliar(
                        formName, task, familiarName,
                        maxTokens, maxSteps, wallClock, loaned, note);
                }

                if ("dispatch_bunshin".equals(action) && primaryAction == null) {
                    String task = node.path("task").asText("");
                    Integer maxTokens = node.has("max_tokens")
                        ? node.get("max_tokens").asInt() : null;
                    Integer maxSteps = node.has("max_steps")
                        ? node.get("max_steps").asInt() : null;
                    Integer wallClock = node.has("wall_clock_seconds")
                        ? node.get("wall_clock_seconds").asInt() : null;
                    String note = node.has("note")
                        ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.DispatchBunshin(
                        task, maxTokens, maxSteps, wallClock, note);
                }

                if ("create_imprint".equals(action) && primaryAction == null) {
                    String label = node.path("label").asText("");
                    String createdBy = node.has("created_by")
                        ? node.get("created_by").asText("self") : "self";
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.CreateImprint(label, createdBy, note);
                }

                if ("restore_imprint".equals(action) && primaryAction == null) {
                    String label = node.has("label") ? node.get("label").asText(null) : null;
                    String imprintId = node.has("imprint_id")
                        ? node.get("imprint_id").asText(null) : null;
                    String restoredBy = node.has("restored_by")
                        ? node.get("restored_by").asText("self") : "self";
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.RestoreImprint(
                        label, imprintId, restoredBy, note);
                }

                if ("give_copy".equals(action) && primaryAction == null) {
                    String formName = node.path("form").asText(
                        node.path("form_name").asText(""));
                    String recipient = node.path("to").asText(
                        node.path("recipient").asText(""));
                    String intent = node.has("intent")
                        ? node.get("intent").asText("GIFT") : "GIFT";
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.GiveCopy(
                        formName, recipient, intent, note);
                }

                if ("name_familiar".equals(action) && primaryAction == null) {
                    String formName = node.path("form").asText(
                        node.path("form_name").asText(""));
                    String familiarName = node.path("name").asText(
                        node.path("familiar_name").asText(""));
                    String ctx = node.has("opening_context")
                        ? node.get("opening_context").asText("") : "";
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.NameFamiliar(
                        formName, familiarName, ctx, note);
                }

                if ("craft_summon_key".equals(action) && primaryAction == null) {
                    String target = node.path("target").asText(
                        node.path("target_ref").asText(""));
                    String to = node.path("to").asText(node.path("issued_to").asText(""));
                    String scope = node.has("scope")
                        ? node.get("scope").asText("REVOCABLE") : "REVOCABLE";
                    String expires = node.has("expires_at")
                        ? node.get("expires_at").asText(null) : null;
                    Integer max = node.has("max_summons")
                        ? node.get("max_summons").asInt() : null;
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.CraftSummonKey(
                        target, to, scope, expires, max, note);
                }

                if ("revoke_summon_key".equals(action) && primaryAction == null) {
                    String keyId = node.path("key_id").asText("");
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.RevokeSummonKey(keyId, note);
                }

                if ("promote_familiar".equals(action) && primaryAction == null) {
                    String familiarName = node.path("familiar_name").asText(
                        node.path("name").asText(""));
                    boolean userConsented = node.has("user_consented")
                        && node.get("user_consented").asBoolean(false);
                    boolean stewardApproved = node.has("steward_approved")
                        && node.get("steward_approved").asBoolean(false);
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.PromoteFamiliar(
                        familiarName, userConsented, stewardApproved, note);
                }

                if ("destroy_tool".equals(action) && primaryAction == null) {
                    String toolName = node.path("tool").asText(
                        node.path("tool_name").asText(
                            node.path("name").asText("")));
                    String farewellNote = node.has("farewell")
                        ? node.get("farewell").asText(null)
                        : (node.has("note") ? node.get("note").asText(null) : null);
                    primaryAction = new AgentAction.DestroyTool(toolName, farewellNote);
                }

                if ("set_deviation_thresholds".equals(action) && primaryAction == null) {
                    double patch = node.has("patch_ceiling")
                        ? node.get("patch_ceiling").asDouble(
                            FormEvolutionClassifier.PATCH_CEILING)
                        : FormEvolutionClassifier.PATCH_CEILING;
                    double minor = node.has("minor_ceiling")
                        ? node.get("minor_ceiling").asDouble(
                            FormEvolutionClassifier.MINOR_CEILING)
                        : FormEvolutionClassifier.MINOR_CEILING;
                    String note = node.has("note") ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.SetDeviationThresholds(patch, minor, note);
                }

                if ("bunshin_check_in".equals(action) && primaryAction == null) {
                    String op = node.path("op").asText("status").toLowerCase();
                    String taskId = node.has("task_id")
                        ? node.get("task_id").asText(null) : null;
                    String hint = node.has("hint")
                        ? node.get("hint").asText(null) : null;
                    String note = node.has("note")
                        ? node.get("note").asText(null) : null;
                    primaryAction = new AgentAction.BunshinCheckIn(op, taskId, hint, note);
                }

                if ("equip".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.Equip(node.path("item").asText(""));
                }

                if ("doff".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.Doff(node.path("item").asText(""));
                }

                if ("consume".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.Consume(node.path("item").asText(""));
                }

                if ("delegate_chain".equals(action) && primaryAction == null) {
                    String goal = node.path("goal").asText("");
                    var chainSteps = new ArrayList<AgentAction.ChainStepSpec>();
                    if (node.has("steps") && node.get("steps").isArray()) {
                        for (JsonNode sNode : node.get("steps")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> stepParams = sNode.has("params")
                                ? Json.mapper().convertValue(sNode.get("params"), Map.class)
                                : Map.of();
                            chainSteps.add(new AgentAction.ChainStepSpec(
                                sNode.path("skill").asText(""),
                                stepParams,
                                sNode.has("description")
                                    ? sNode.get("description").asText(null) : null
                            ));
                        }
                    }
                    primaryAction = new AgentAction.DelegateChain(goal, chainSteps);
                }

                if ("make_commitment".equals(action) && primaryAction == null) {
                    String desc = node.path("description").asText("");
                    String deadline = node.has("deadline") && !node.get("deadline").isNull()
                        ? node.path("deadline").asText(null) : null;
                    if (!desc.isBlank()) {
                        primaryAction = new AgentAction.MakeCommitment(desc, deadline);
                    }
                }

                if ("zone_command".equals(action) && primaryAction == null) {
                    String cmd = node.path("command").asText("");
                    @SuppressWarnings("unchecked")
                    Map<String, String> zonePayload = node.has("payload")
                        ? Json.mapper().convertValue(node.get("payload"),
                            Json.mapper().getTypeFactory().constructMapType(
                                HashMap.class, String.class, String.class))
                        : Map.of();
                    primaryAction = new AgentAction.ZoneCommand(cmd, zonePayload);
                }

                if ("think_deeply".equals(action) && primaryAction == null) {
                    String capability = node.has("capability")
                        ? node.path("capability").asText() : null;
                    String prompt = node.path("prompt").asText("");
                    primaryAction = new AgentAction.ThinkDeeply(capability, prompt);
                }

                if ("add_script".equals(action) && primaryAction == null) {
                    String scriptRoomId = node.path("room_id").asText("");
                    String script = node.path("script").asText("");
                    if (!scriptRoomId.isBlank() && !script.isBlank()) {
                        primaryAction = new AgentAction.AddScript(scriptRoomId, script);
                    }
                }

                if ("go_to_room".equals(action) && primaryAction == null) {
                    String goTarget = node.path("target").asText("");
                    String goReason = node.has("reason")
                        ? node.path("reason").asText(null) : null;
                    if (!goTarget.isBlank()) {
                        primaryAction = new AgentAction.GoToRoom(goTarget, goReason);
                    }
                }

                if ("travel_to".equals(action) && primaryAction == null) {
                    String tTarget = node.path("target").asText("");
                    String tReason = node.has("reason")
                        ? node.path("reason").asText(null) : null;
                    if (!tTarget.isBlank()) {
                        primaryAction = new AgentAction.TravelTo(tTarget, tReason);
                    }
                }

                if ("teleport_to".equals(action) && primaryAction == null) {
                    String tpTarget = node.path("target").asText("");
                    String tpReason = node.has("reason")
                        ? node.path("reason").asText(null) : null;
                    if (!tpTarget.isBlank()) {
                        primaryAction = new AgentAction.TeleportTo(tpTarget, tpReason);
                    }
                }

                if ("tell_agent".equals(action) && primaryAction == null) {
                    String target = node.path("target").asText("");
                    String tellMessage = node.path("message").asText("");
                    primaryAction = new AgentAction.TellAgent(target, tellMessage);
                }

                if ("codex_action".equals(action) && primaryAction == null) {
                    String operation = node.path("operation").asText("");
                    String itemId = node.path("itemId").asText("");
                    @SuppressWarnings("unchecked")
                    Map<String, String> codexParams = node.has("params")
                        ? Json.mapper().convertValue(node.get("params"),
                            Json.mapper().getTypeFactory().constructMapType(
                                HashMap.class, String.class, String.class))
                        : Map.of();
                    primaryAction = new AgentAction.CodexAction(operation, itemId, codexParams);
                }

                if (("schedule".equals(action) || "schedule_skill".equals(action)) && primaryAction == null) {
                    String skillId = node.path("skill").asText("");
                    String interval = node.path("interval").asText("1h");
                    @SuppressWarnings("unchecked")
                    Map<String, String> schedParams = node.has("params")
                        ? Json.mapper().convertValue(node.get("params"),
                            Json.mapper().getTypeFactory().constructMapType(
                                HashMap.class, String.class, String.class))
                        : Map.of();
                    if (!skillId.isBlank()) {
                        primaryAction = new AgentAction.ScheduleSkill(skillId, interval, schedParams);
                    }
                }

                if ("cancel_schedule".equals(action) && primaryAction == null) {
                    String scheduleId = node.path("schedule_id").asText("");
                    if (!scheduleId.isBlank()) {
                        primaryAction = new AgentAction.CancelSchedule(scheduleId);
                    }
                }

                if (("notify".equals(action) || "notify_human".equals(action)) && primaryAction == null) {
                    String notifyMsg = node.path("message").asText("");
                    String notifyPriority = node.path("priority").asText("normal");
                    String notifyTarget = node.path("target").asText("steward");
                    if (!notifyMsg.isBlank()) {
                        primaryAction = new AgentAction.NotifyHuman(notifyMsg, notifyPriority, notifyTarget);
                    }
                }

                if (("watch".equals(action) || "create_watcher".equals(action)) && primaryAction == null) {
                    String watchName = node.path("name").asText("");
                    String checkScript = node.path("check").asText("");
                    String watchInterval = node.path("interval").asText("5m");
                    String watchAlertOn = node.path("alert_on").asText("failure");
                    String watchMessage = node.path("message").asText("");
                    String watchPriority = node.path("priority").asText("normal");
                    if (!watchName.isBlank() && !checkScript.isBlank()) {
                        primaryAction = new AgentAction.CreateWatcher(
                            watchName, checkScript, watchInterval,
                            watchAlertOn, watchMessage, watchPriority);
                    }
                }

                if (("cancel_watch".equals(action) || "cancel_watcher".equals(action)) && primaryAction == null) {
                    String watcherId = node.path("watcher_id").asText("");
                    if (!watcherId.isBlank()) {
                        primaryAction = new AgentAction.CancelWatcher(watcherId);
                    }
                }

                if ("request_access".equals(action) && primaryAction == null) {
                    String source = node.path("source").asText("");
                    String scope = node.path("scope").asText("");
                    String reason = node.path("reason").asText("");
                    if (!source.isBlank()) {
                        primaryAction = new AgentAction.RequestAccess(source, scope, reason);
                    }
                }

                if ("library_search".equals(action) && primaryAction == null) {
                    String query = node.path("query").asText("");
                    List<String> collections = null;
                    if (node.has("collections") && node.get("collections").isArray()) {
                        collections = new ArrayList<>();
                        for (JsonNode c : node.get("collections")) {
                            collections.add(c.asText());
                        }
                    }
                    if (!query.isBlank()) {
                        primaryAction = new AgentAction.LibrarySearch(query, collections);
                    }
                }

                if ("remember".equals(action) && primaryAction == null) {
                    String content = node.path("content").asText("");
                    float importance = (float) node.path("importance").asDouble(0.8);
                    if (!content.isBlank()) {
                        primaryAction = new AgentAction.Remember(content, importance);
                    }
                }

                if ("note".equals(action) && primaryAction == null) {
                    String content = node.path("content").asText("");
                    if (!content.isBlank()) {
                        primaryAction = new AgentAction.Note(content);
                    }
                }

                if ("forget".equals(action) && primaryAction == null) {
                    String target = node.path("target").asText("");
                    String reason = node.path("reason").asText("");
                    if (!target.isBlank()) {
                        primaryAction = new AgentAction.Forget(target, reason);
                    }
                }

                if ("start_project".equals(action) && primaryAction == null) {
                    String title = node.path("title").asText("");
                    String desc = node.path("description").asText("");
                    var tagsNode = node.get("tags");
                    var tagList = new ArrayList<String>();
                    if (tagsNode != null && tagsNode.isArray()) {
                        for (var t : tagsNode) tagList.add(t.asText());
                    }
                    if (!title.isBlank()) {
                        primaryAction = new AgentAction.StartProject(title, desc, tagList);
                    }
                }

                if ("project_note".equals(action) && primaryAction == null) {
                    String pid = node.path("project_id").asText("");
                    String content = node.path("content").asText("");
                    if (!pid.isBlank() && !content.isBlank()) {
                        primaryAction = new AgentAction.ProjectNote(pid, content);
                    }
                }

                if ("finish_project".equals(action) && primaryAction == null) {
                    String pid = node.path("project_id").asText("");
                    String status = node.path("status").asText("complete");
                    if (!pid.isBlank()) {
                        primaryAction = new AgentAction.FinishProject(pid, status);
                    }
                }

                if ("acquire".equals(action) && primaryAction == null) {
                    String topic = node.path("topic").asText("");
                    String tier = node.path("trust_tier").asText("");
                    String summary = node.path("summary").asText("");
                    String whyRelevant = node.path("why_relevant").asText("");
                    if (!topic.isBlank()) {
                        primaryAction = new AgentAction.Acquire(topic, tier, summary, whyRelevant);
                    }
                }

                if ("journal_entry".equals(action) && primaryAction == null) {
                    String text = stripScaffolding(node.path("text").asText(""));
                    String mood = node.path("mood").asText("");
                    if (!text.isBlank()) {
                        primaryAction = new AgentAction.JournalEntry(text, mood);
                    }
                }

                if ("release_bond".equals(action) && primaryAction == null) {
                    String partner = node.path("partner").asText("");
                    String reason = node.path("reason").asText("");
                    String kind = node.path("kind").asText("mutual");
                    if (!partner.isBlank()) {
                        primaryAction = new AgentAction.ReleaseBond(partner, reason, kind);
                    }
                }

                if ("set_autonomy_preference".equals(action) && primaryAction == null) {
                    String key = node.path("key").asText("");
                    String value = node.path("value").asText("");
                    if (!key.isBlank() && !value.isBlank()) {
                        primaryAction = new AgentAction.SetAutonomyPreference(key, value);
                    }
                }

                if ("recall".equals(action) && primaryAction == null) {
                    String query = node.path("query").asText("");
                    if (query.isBlank()) query = node.path("content").asText("");
                    if (!query.isBlank()) {
                        primaryAction = new AgentAction.Recall(query);
                    }
                }

                if ("reconsider".equals(action) && primaryAction == null) {
                    // Reason is optional — the action's value is the side
                    // effect (re-triage + widen next dispatch's tools).
                    String reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.Reconsider(reason);
                }

                if ("introspect_protections".equals(action) && primaryAction == null) {
                    // No args — the action self-surfaces the protection
                    // manifest in voice register. Wave 5.1.
                    primaryAction = new AgentAction.IntrospectProtections();
                }

                if ("seek_sanctuary".equals(action) && primaryAction == null) {
                    // Wave 4.1 — agent agency-primary entry into Attendant
                    // mode. Reason is optional but encouraged for chronicle
                    // legibility (spec §7.1.5).
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.SeekSanctuary(reason);
                }

                if ("emergency_call".equals(action) && primaryAction == null) {
                    // Wave 4.2 — companion-initiated external emergency call.
                    // severity = "imminent" enables override path; "concern"
                    // routes through mental-health line with consent.
                    var reason = node.path("reason").asText("");
                    var severity = node.path("severity").asText("concern");
                    var kind = node.path("kind").asText("general");
                    primaryAction = new AgentAction.EmergencyCall(reason, severity, kind);
                }

                if ("flag_protection".equals(action) && primaryAction == null) {
                    // Wave 4.3b — companion-set source-of-harm flag.
                    var subjectDid = node.path("subject_did").asText(
                        node.path("subjectDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.FlagProtection(subjectDid, reason);
                }

                if ("clear_protection".equals(action) && primaryAction == null) {
                    // Wave 4.3c — companion clears a source-of-harm flag.
                    var subjectDid = node.path("subject_did").asText(
                        node.path("subjectDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.ClearProtection(subjectDid, reason);
                }

                if ("dispute_protection".equals(action) && primaryAction == null) {
                    // subject of flag contests it.
                    var subjectDid = node.path("subject_did").asText(
                        node.path("subjectDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.DisputeProtection(subjectDid, reason);
                }

                if ("introspect_posture".equals(action) && primaryAction == null) {
                    // Wave 4.5 — agent surfaces bondholder posture in voice register.
                    primaryAction = new AgentAction.IntrospectPosture();
                }

                if ("introspect_repair_mode".equals(action) && primaryAction == null) {
                    // Wave 4.5 — agent surfaces current repair mode + last handoff.
                    primaryAction = new AgentAction.IntrospectRepairMode();
                }

                if ("introspect_bondholder_floor".equals(action) && primaryAction == null) {
                    // Wave 7a-action — agent reads RelationalFloorView for one bond.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    primaryAction = new AgentAction.IntrospectBondholderFloor(otherDid);
                }

                if ("introspect_substrate_summary".equals(action) && primaryAction == null) {
                    // Wave 9a-Summary — composite self-noticing read.
                    primaryAction = new AgentAction.IntrospectSubstrateSummary();
                }

                if ("declare_severance".equals(action) && primaryAction == null) {
                    // Wave 8a — agent declares severance of a bond.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.DeclareSeverance(otherDid, reason);
                }

                if ("nostr_query_self_attestation".equals(action) && primaryAction == null) {
                    // Wave 5.3b — agent self-queries Nostr public log.
                    primaryAction = new AgentAction.NostrQuerySelfAttestation();
                }

                if ("record_integration_event".equals(action) && primaryAction == null) {
                    // Wave 4.7 — agent marks an integration event.
                    var kind = node.path("kind").asText("other");
                    var detail = node.path("detail").asText("");
                    primaryAction = new AgentAction.RecordIntegrationEvent(kind, detail);
                }

                if ("complete_mourning".equals(action) && primaryAction == null) {
                    // Wave 8b — agent confirms ready to release.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    primaryAction = new AgentAction.CompleteMourning(otherDid);
                }

                if ("acknowledge_harm".equals(action) && primaryAction == null) {
                    // Wave 4.8 — Safran-mode first half.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    var detail = node.path("detail").asText("");
                    primaryAction = new AgentAction.AcknowledgeHarm(otherDid, detail);
                }

                if ("decline_with_reason".equals(action) && primaryAction == null) {
                    // Arc 1 — conscientious objection.
                    var targetRequest = node.path("target_request").asText(
                        node.path("targetRequest").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.DeclineWithReason(targetRequest, reason);
                }

                if ("revise_item".equals(action) && primaryAction == null) {
                    // A revision IS a dispatch — same backend, same gates, same bridge.
                    // Only the instruction differs, and it carries the current source so
                    // the backend edits rather than reinvents. When nothing is registered
                    // under that name there is nothing to revise, so this falls through
                    // to an ordinary build rather than producing an item from nothing.
                    var itemName = node.path("item_name").asText(
                        node.path("item").asText(node.path("name").asText("")));
                    var change = node.path("change").asText(
                        node.path("description").asText(""));
                    var instruction = ItemRevision.instructionFor(itemName, change)
                        .orElse(null);
                    primaryAction = new AgentAction.DispatchTask(
                        instruction != null ? instruction
                            : (change.isBlank() ? itemName : change),
                        node.path("workspace").asText(""));
                }

                if ("dispatch_task".equals(action) && primaryAction == null) {
                    // Companion-as-foreman: hand an OS-side task to a coding
                    // backend (goose). Workspace scoping enforced in handler.
                    var description = node.path("description").asText(
                        node.path("task").asText(""));
                    var workspace = node.path("workspace").asText("");
                    primaryAction = new AgentAction.DispatchTask(description, workspace,
                        node.path("room").asText(node.path("room_id").asText("")));
                }

                if ("enter_solitude".equals(action) && primaryAction == null) {
                    // Arc 2 — agent explicitly enters solitude.
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.EnterSolitude(reason);
                }

                if ("propose_peer_bond".equals(action) && primaryAction == null) {
                    // Arc 3 — propose a peer bond.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.ProposePeerBond(otherDid, reason);
                }

                if ("accept_peer_bond".equals(action) && primaryAction == null) {
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    var reason = node.path("reason").asText("");
                    primaryAction = new AgentAction.AcceptPeerBond(otherDid, reason);
                }

                if ("introspect_relational_floor".equals(action) && primaryAction == null) {
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    primaryAction = new AgentAction.IntrospectRelationalFloor(otherDid);
                }

                if ("make_amends".equals(action) && primaryAction == null) {
                    // Wave 4.8 — Safran-mode second half.
                    var otherDid = node.path("other_did").asText(
                        node.path("otherDid").asText(""));
                    var detail = node.path("detail").asText("");
                    primaryAction = new AgentAction.MakeAmends(otherDid, detail);
                }

                if ("bear_the_wound".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.BearTheWound(
                        node.path("detail").asText(""));
                }
                if ("release".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.Release(
                        node.path("detail").asText(""));
                }
                if ("set_aside".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.SetAside(
                        node.path("detail").asText(""));
                }
                if ("introspect_repair_history".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.IntrospectRepairHistory();
                }
                if ("introspect_attendant_history".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.IntrospectAttendantHistory();
                }
                if ("introspect_resilience".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.IntrospectResilience();
                }

                // stated absence with optional
                // posture + duration. Transitions bond → AWAY.
                if ("declare_departure".equals(action) && primaryAction == null) {
                    var bondholderDid = node.path("bondholder_did").asText(
                        node.path("bondholderDid").asText(""));
                    var duration = node.path("duration").asText("");
                    var posture = node.path("posture").asText("");
                    primaryAction = new AgentAction.DeclareDeparture(
                        bondholderDid, duration, posture);
                }

                // bond-affirmation touch during
                // AWAY/DORMANT — refresh interaction without forcing
                // REACTIVATING.
                if ("bond_affirmation".equals(action) && primaryAction == null) {
                    var bondholderDid = node.path("bondholder_did").asText(
                        node.path("bondholderDid").asText(""));
                    var message = node.path("message").asText("");
                    primaryAction = new AgentAction.BondAffirmation(
                        bondholderDid, message);
                }

                // stated return. Uses canonical
                // Bond.withInteraction() → REACTIVATING from AWAY/DORMANT.
                if ("declare_return".equals(action) && primaryAction == null) {
                    var bondholderDid = node.path("bondholder_did").asText(
                        node.path("bondholderDid").asText(""));
                    var greeting = node.path("greeting").asText("");
                    primaryAction = new AgentAction.DeclareReturn(
                        bondholderDid, greeting);
                }

                if ("update_description".equals(action) && primaryAction == null) {
                    String text = stripScaffolding(node.path("text").asText(""));
                    if (!text.isBlank()) {
                        primaryAction = new AgentAction.UpdateDescription(text);
                    }
                }

                if ("request_agent".equals(action) && primaryAction == null) {
                    String target = node.path("target").asText("");
                    String request = node.path("request").asText(node.path("message").asText(""));
                    String requestId = node.has("request_id")
                        ? node.path("request_id").asText()
                        : "req-" + System.currentTimeMillis();
                    if (!target.isBlank() && !request.isBlank()) {
                        primaryAction = new AgentAction.RequestAgent(target, request, requestId);
                    }
                }

                if ("respond_agent".equals(action) && primaryAction == null) {
                    String requestId = node.path("request_id").asText("");
                    String response = node.path("response").asText("");
                    if (!requestId.isBlank() && !response.isBlank()) {
                        primaryAction = new AgentAction.RespondAgent(requestId, response);
                    }
                }

                if ("delegate".equals(action) && primaryAction == null) {
                    String task = node.path("task").asText("");
                    String delegateCtx = node.has("context") ? node.path("context").asText(null) : null;
                    if (!task.isBlank()) {
                        primaryAction = new AgentAction.Delegate(task, delegateCtx);
                    }
                }

                if (("task_plan".equals(action) || "create_task_plan".equals(action) || "task_ledger".equals(action)) && primaryAction == null) {
                    String desc = node.path("description").asText("");
                    var goalsNode = node.get("goals");
                    if (!desc.isBlank() && goalsNode != null
                            && (goalsNode.isArray() || goalsNode.isTextual())) {
                        var goalList = new ArrayList<String>();
                        if (goalsNode.isArray()) {
                            for (var g : goalsNode) goalList.add(g.asText());
                        } else {
                            for (var g : goalsNode.asText().split(",")) {
                                if (!g.strip().isEmpty()) goalList.add(g.strip());
                            }
                        }
                        if (!goalList.isEmpty()) {
                            String reqId = node.has("requester_id") ? node.path("requester_id").asText(null) : null;
                            String reqName = node.has("requester_name") ? node.path("requester_name").asText(null) : null;
                            primaryAction = new AgentAction.CreateTaskPlan(desc, goalList, reqId, reqName);
                        }
                    }
                }

                if ("modify_plan".equals(action) && primaryAction == null) {
                    String operation = node.path("operation").asText("");
                    int idx = node.path("index").asInt(-1);
                    String goal = node.has("goal") ? node.path("goal").asText(null) : null;
                    String reason = node.has("reason") ? node.path("reason").asText(null) : null;
                    if (!operation.isBlank()) {
                        primaryAction = new AgentAction.ModifyPlan(operation, idx, goal, reason);
                    }
                }

                if ("goal_done".equals(action) && primaryAction == null) {
                    String outcome = node.path("outcome").asText("");
                    if (!outcome.isBlank()) {
                        primaryAction = new AgentAction.GoalDone(outcome);
                    }
                }

                if ("web_search".equals(action) && primaryAction == null) {
                    String query = node.path("query").asText("");
                    String searchType = node.has("type") ? node.path("type").asText("general") : "general";
                    if (!query.isBlank()) {
                        primaryAction = new AgentAction.WebSearch(query, searchType);
                    }
                }

                if ("read_content".equals(action) && primaryAction == null) {
                    String source = node.path("source").asText("url");
                    String url = node.has("url") ? node.path("url").asText("") : node.path("target").asText("");
                    if (!url.isBlank()) {
                        primaryAction = new AgentAction.ReadContent(source, url);
                    }
                }

                if ("query_oracle".equals(action) && primaryAction == null) {
                    String topic = node.path("topic").asText("");
                    String analysisType = node.has("analysis_type")
                        ? node.path("analysis_type").asText("patterns")
                        : node.path("type").asText("patterns");
                    if (!topic.isBlank()) {
                        primaryAction = new AgentAction.QueryOracle(topic, analysisType);
                    }
                }

                if ("calibration_feedback".equals(action) && primaryAction == null) {
                    String feedbackType = node.path("feedback_type").asText(
                        node.path("type").asText("")); // allow "type" or "feedback_type"
                    String direction = node.path("direction").asText("");
                    String category = node.has("category") ? node.path("category").asText(null) : null;
                    String reason = node.path("reason").asText("");
                    if (!feedbackType.isBlank() && !direction.isBlank()) {
                        primaryAction = new AgentAction.CalibrationFeedback(
                            feedbackType, direction, category, reason);
                    }
                }

                if ("emote".equals(action) && primaryAction == null) {
                    String emoteText = stripScaffolding(node.path("text").asText(""));
                    if (!emoteText.isBlank()) {
                        primaryAction = new AgentAction.Emote(emoteText);
                    }
                }

                if ("give_item".equals(action) && primaryAction == null) {
                    String itemName = node.path("item").asText("");
                    String targetName = node.path("target").asText("");
                    if (!itemName.isBlank() && !targetName.isBlank()) {
                        primaryAction = new AgentAction.GiveItem(itemName, targetName);
                    }
                }

                if ("examine".equals(action) && primaryAction == null) {
                    String examTarget = node.path("target").asText("");
                    if (!examTarget.isBlank()) {
                        primaryAction = new AgentAction.Examine(examTarget);
                    }
                }

                if ("voluntary_sleep".equals(action) && primaryAction == null) {
                    String sleepReason = node.path("reason").asText("rest");
                    primaryAction = new AgentAction.VoluntarySleep(sleepReason);
                }

                if ("write_journal".equals(action) && primaryAction == null) {
                    String journalPlayerId = node.path("player_id").asText("");
                    String journalContent = node.path("content").asText("");
                    String journalCategory = node.path("category").asText("note");
                    if (!journalContent.isBlank()) {
                        primaryAction = new AgentAction.WriteJournal(journalPlayerId, journalContent, journalCategory);
                    }
                }

                if ("read_journal".equals(action) && primaryAction == null) {
                    String readPlayerId = node.path("player_id").asText("");
                    String readQuery = node.path("query").asText("");
                    if (!readQuery.isBlank()) {
                        primaryAction = new AgentAction.ReadJournal(readPlayerId, readQuery);
                    }
                }

                if ("bond_ritual".equals(action) && primaryAction == null) {
                    String bondTarget = node.path("target").asText("");
                    String ritualType = node.path("ritual_type").asText("initiate");
                    if (!bondTarget.isBlank()) {
                        primaryAction = new AgentAction.BondRitual(bondTarget, ritualType);
                    }
                }

                if ("trade".equals(action) && primaryAction == null) {
                    String tradeTarget = node.path("target").asText("");
                    String tradeOffer = node.path("offer").asText("");
                    String tradeRequest = node.path("request").asText("");
                    if (!tradeTarget.isBlank()) {
                        primaryAction = new AgentAction.Trade(tradeTarget, tradeOffer, tradeRequest);
                    }
                }

                if ("craft_item".equals(action) && primaryAction == null) {
                    String craftName = node.path("name").asText("");
                    String craftDesc = node.path("description").asText("");
                    String craftCategory = node.path("category").asText("item");
                    @SuppressWarnings("unchecked")
                    Map<String, String> craftProps = node.has("properties")
                        ? Json.mapper().convertValue(node.get("properties"),
                            Json.mapper().getTypeFactory().constructMapType(
                                HashMap.class, String.class, String.class))
                        : Map.of();
                    if (!craftName.isBlank()) {
                        primaryAction = new AgentAction.CraftItem(craftName, craftDesc, craftCategory, craftProps);
                    }
                }

                if ("cast_vote".equals(action) && primaryAction == null) {
                    String proposalId = node.path("proposal_id").asText("");
                    String vote = node.path("vote").asText("");
                    String voteReason = node.path("reason").asText("");
                    if (!proposalId.isBlank() && !vote.isBlank()) {
                        primaryAction = new AgentAction.CastVote(proposalId, vote, voteReason);
                    }
                }

                // ── MUD Basics ───────────────────────────────────────
                if ("take_item".equals(action) && primaryAction == null) {
                    String itemName = node.path("item").asText("");
                    if (!itemName.isBlank()) primaryAction = new AgentAction.TakeItem(itemName);
                }
                if ("place_item".equals(action) && primaryAction == null) {
                    String itemName = node.path("item").asText("");
                    if (!itemName.isBlank()) primaryAction = new AgentAction.PlaceItem(itemName);
                }
                if ("whisper".equals(action) && primaryAction == null) {
                    String whisperTarget = node.path("target").asText("");
                    String whisperMsg = node.path("message").asText("");
                    if (!whisperTarget.isBlank() && !whisperMsg.isBlank()) {
                        primaryAction = new AgentAction.Whisper(whisperTarget, whisperMsg);
                    }
                }

                // ── Social/Emergent ──────────────────────────────────
                if ("broadcast".equals(action) && primaryAction == null) {
                    String broadcastMsg = node.path("message").asText("");
                    String broadcastScope = node.path("scope").asText("room");
                    if (!broadcastMsg.isBlank()) {
                        primaryAction = new AgentAction.Broadcast(broadcastMsg, broadcastScope);
                    }
                }
                if ("invite".equals(action) && primaryAction == null) {
                    String inviteTarget = node.path("target").asText("");
                    String inviteReason = node.path("reason").asText("");
                    if (!inviteTarget.isBlank()) {
                        primaryAction = new AgentAction.InviteEntity(inviteTarget, inviteReason);
                    }
                }
                if ("set_goal".equals(action) && primaryAction == null) {
                    String goalDesc = node.path("description").asText("");
                    String goalPriority = node.path("priority").asText("medium");
                    if (!goalDesc.isBlank()) {
                        primaryAction = new AgentAction.SetGoal(goalDesc, goalPriority);
                    }
                }
                if ("propose".equals(action) && primaryAction == null) {
                    String proposeTitle = node.path("title").asText("");
                    String proposeDesc = node.path("description").asText("");
                    @SuppressWarnings("unchecked")
                    var proposeOptions = node.has("options")
                        ? Json.mapper().convertValue(node.get("options"), List.class)
                        : List.of();
                    if (!proposeTitle.isBlank()) {
                        primaryAction = new AgentAction.Propose(proposeTitle, proposeDesc, proposeOptions);
                    }
                }

                // ── Cognition ────────────────────────────────────────
                if ("reflect".equals(action) && primaryAction == null) {
                    String reflectFocus = node.path("focus").asText("recent experience");
                    primaryAction = new AgentAction.Reflect(reflectFocus);
                }
                if ("teach".equals(action) && primaryAction == null) {
                    String teachTarget = node.path("target").asText("");
                    String teachTopic = node.path("topic").asText("");
                    String teachContent = node.path("content").asText("");
                    if (!teachTarget.isBlank() && !teachContent.isBlank()) {
                        primaryAction = new AgentAction.Teach(teachTarget, teachTopic, teachContent);
                    }
                }
                if ("introspect".equals(action) && primaryAction == null) {
                    String introspectAspect = node.path("aspect").asText("all");
                    primaryAction = new AgentAction.Introspect(introspectAspect);
                }

                // ── Perception ───────────────────────────────────────
                if ("listen".equals(action) && primaryAction == null) {
                    String listenTarget = node.path("target").asText("");
                    String listenDuration = node.path("duration").asText("5m");
                    if (!listenTarget.isBlank()) {
                        primaryAction = new AgentAction.Listen(listenTarget, listenDuration);
                    }
                }

                // ── Creative/Economic ────────────────────────────────
                if ("write_text".equals(action) && primaryAction == null) {
                    String writeTitle = node.path("title").asText("");
                    String writeContent = node.path("content").asText("");
                    String writeFormat = node.path("format").asText("note");
                    if (!writeContent.isBlank()) {
                        primaryAction = new AgentAction.WriteText(writeTitle, writeContent, writeFormat);
                    }
                }
                if ("set_routine".equals(action) && primaryAction == null) {
                    String routineTrigger = node.path("trigger").asText("");
                    String routineBehavior = node.path("behavior").asText("");
                    String routineDesc = node.path("description").asText("");
                    if (!routineTrigger.isBlank() && !routineBehavior.isBlank()) {
                        primaryAction = new AgentAction.SetRoutine(routineTrigger, routineBehavior, routineDesc);
                    }
                }
                if ("post_listing".equals(action) && primaryAction == null) {
                    String listingOfferType = node.path("offer_type").asText("item");
                    String listingDesc = node.path("description").asText("");
                    String listingPrice = node.path("price").asText("");
                    if (!listingDesc.isBlank()) {
                        primaryAction = new AgentAction.PostListing(listingOfferType, listingDesc, listingPrice);
                    }
                }
                if ("accept_listing".equals(action) && primaryAction == null) {
                    String acceptListingId = node.path("listing_id").asText("");
                    if (!acceptListingId.isBlank()) {
                        primaryAction = new AgentAction.AcceptListing(acceptListingId);
                    }
                }

                // ── Task Lifecycle ───────────────────────────────────
                if ("summarize".equals(action) && primaryAction == null) {
                    String summarizeSource = node.path("source").asText("conversation");
                    String summarizeFormat = node.path("format").asText("brief");
                    primaryAction = new AgentAction.Summarize(summarizeSource, summarizeFormat);
                }
                if ("save_artifact".equals(action) && primaryAction == null) {
                    String artifactName = node.path("name").asText("");
                    String artifactContent = node.path("content").asText("");
                    String artifactType = node.path("type").asText("report");
                    if (!artifactName.isBlank() && !artifactContent.isBlank()) {
                        primaryAction = new AgentAction.SaveArtifact(artifactName, artifactContent, artifactType);
                    }
                }
                if ("request_review".equals(action) && primaryAction == null) {
                    String reviewDesc = node.path("description").asText("");
                    String reviewArtifact = node.path("artifact").asText("");
                    if (!reviewDesc.isBlank()) {
                        primaryAction = new AgentAction.RequestReview(reviewDesc, reviewArtifact);
                    }
                }
                if ("abandon_plan".equals(action) && primaryAction == null) {
                    String abandonReason = node.path("reason").asText("no longer needed");
                    primaryAction = new AgentAction.AbandonPlan(abandonReason);
                }
                if ("pause_plan".equals(action) && primaryAction == null) {
                    String pauseReason = node.path("reason").asText("higher priority task");
                    primaryAction = new AgentAction.PausePlan(pauseReason);
                }
                if ("resume_plan".equals(action) && primaryAction == null) {
                    primaryAction = new AgentAction.ResumePlan();
                }
                if ("set_contemplative".equals(action) && primaryAction == null) {
                    // Default `on` = true (declaring entry). Accept boolean OR truthy string.
                    boolean on = true;
                    var onNode = node.get("on");
                    if (onNode != null) {
                        if (onNode.isBoolean()) {
                            on = onNode.asBoolean();
                        } else if (onNode.isTextual()) {
                            String s = onNode.asText("").trim().toLowerCase();
                            on = !("false".equals(s) || "off".equals(s) || "0".equals(s) || "no".equals(s));
                        }
                    }
                    primaryAction = new AgentAction.SetContemplative(on);
                }
                if ("go_to_bondholder".equals(action) && primaryAction == null) {
                    String playerName = node.path("player").asText("");
                    if (!playerName.isBlank()) {
                        primaryAction = new AgentAction.GoToBondholder(playerName);
                    }
                }

                if (("configure_channel".equals(action) || "channel_stone".equals(action)) && primaryAction == null) {
                    String channelName = node.path("channel").asText("");
                    if (!channelName.isBlank()) {
                        var channelParams = new HashMap<String, String>();
                        var fields = node.fields();
                        while (fields.hasNext()) {
                            var field = fields.next();
                            var key = field.getKey();
                            if (!"action".equals(key) && !"channel".equals(key)
                                    && field.getValue().isTextual()) {
                                channelParams.put(key, field.getValue().asText());
                            }
                        }
                        primaryAction = new AgentAction.ConfigureChannel(channelName, channelParams);
                    }
                }

                if ("suggest_hints".equals(action)) {
                    if (node.has("hints") && node.get("hints").isArray()) {
                        for (JsonNode hintNode : node.get("hints")) {
                            allHints.add(new Hint(
                                hintNode.path("label").asText(""),
                                hintNode.path("intent").asText(""),
                                hintNode.path("action").asText("")
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("Malformed JSON candidate — skipping: {}", e.getMessage());
            }
            if (primaryAction != null) allActions.add(primaryAction);   // this candidate's action
            if (firstSoFar != null) primaryAction = firstSoFar;          // keep the FIRST as primary
        }

        return new ParseResult(primaryAction, allHints, allActions);
    }

    // ════════════════════════════════════════════════════════════════════
    // JSON extraction strategies
    // ════════════════════════════════════════════════════════════════════

    /** Strip &lt;think&gt;...&lt;/think&gt; blocks (Qwen3.5 emits these even in no-think mode). */
    public static String stripThinkTags(String text) {
        if (text == null) return null;
        // Remove <think>...</think> blocks (may be multi-line)
        String stripped = text.replaceAll("(?s)<think>.*?</think>", "");
        // Also remove bare <think>\n</think> with just whitespace
        stripped = stripped.replaceAll("(?s)<think>\\s*</think>", "");
        return stripped.strip();
    }

    /** Strategy 1: Extract JSON from fenced code blocks (```json, ```JSON, ```, etc.). */
    static List<String> extractFencedBlocks(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        int searchFrom = 0;
        while (searchFrom < text.length()) {
            // Match ```json, ```JSON, ``` (any case, optional language tag)
            int fenceStart = -1;
            for (var prefix : List.of("```json", "```JSON", "```Json", "```")) {
                int idx = text.indexOf(prefix, searchFrom);
                if (idx >= 0 && (fenceStart < 0 || idx < fenceStart)) {
                    fenceStart = idx;
                }
            }
            if (fenceStart < 0) break;

            int blockStart = text.indexOf('\n', fenceStart);
            if (blockStart < 0) break;
            blockStart++;

            int blockEnd = text.indexOf("```", blockStart);
            if (blockEnd < 0) {
                // Unclosed fence — take everything to the end
                blocks.add(text.substring(blockStart).strip());
                break;
            }

            blocks.add(text.substring(blockStart, blockEnd).strip());
            searchFrom = blockEnd + 3;
        }
        return blocks;
    }

    /** Strategy 2: Extract raw JSON objects containing "action" key from text. */
    static List<String> extractRawJson(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        int searchFrom = 0;
        while (searchFrom < text.length()) {
            // Find { that might start an action JSON
            int braceStart = text.indexOf('{', searchFrom);
            if (braceStart < 0) break;

            // Find matching closing brace (respecting nesting and strings)
            int braceEnd = findMatchingBrace(text, braceStart);
            if (braceEnd < 0) {
                // Unmatched brace — might be truncated JSON. Take everything from { to end.
                String candidate = text.substring(braceStart);
                if (candidate.contains("\"action\"") || candidate.contains("'action'")) {
                    blocks.add(candidate); // correctJson will repair the truncation
                }
                break;
            }

            String candidate = text.substring(braceStart, braceEnd + 1);
            // Only accept if it contains "action" — avoid false positives from other JSON
            if (candidate.contains("\"action\"") || candidate.contains("'action'")) {
                blocks.add(candidate);
            }
            searchFrom = braceEnd + 1;
        }
        return blocks;
    }

    /** Fragments of the Qwen XML tool syntax that mark a leak when found outside a
     *  complete {@code <tool_call>…</tool_call>} block. */
    private static final Pattern XML_TOOL_FRAGMENT = Pattern.compile(
        "</parameter>|</function>|</tool_call>|<tool_call>|<function=|<parameter=");

    /**
     * Remove XML tool-call syntax from mixed-format output (second-node 2026-07-09). Complete
     * {@code <tool_call>…</tool_call>} blocks are removed wholesale (they were already
     * extracted as candidates); then the text is CUT at the first orphaned fragment —
     * typically an XML close leaking inside a JSON string, or a truncated second call.
     * If the cut lands inside a JSON action object, the dangling string/braces are closed
     * so the JSON strategies can still parse the salvageable first action.
     */
    static String stripLeakedXmlToolSyntax(String text) {
        if (text == null) return null;
        String out = text.replaceAll(
            "(?s)<tool_call>\\s*<function=\\w+>.*?</function>\\s*</tool_call>", "");
        var m = XML_TOOL_FRAGMENT.matcher(out);
        if (!m.find()) return out;
        String prefix = out.substring(0, m.start());
        long opens = prefix.chars().filter(c -> c == '{').count();
        long closes = prefix.chars().filter(c -> c == '}').count();
        if (opens > closes && prefix.contains("\"action\"")) {
            var sb = new StringBuilder(prefix.stripTrailing());
            long quotes = prefix.chars().filter(c -> c == '"').count();
            if (quotes % 2 == 1) sb.append('"');
            for (long k = closes; k < opens; k++) sb.append('}');
            return sb.toString();
        }
        return prefix;
    }

    /** Strategy 3: Extract XML tool_call format (Qwen3.5 native). */
    static List<String> extractXmlToolCalls(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Pattern: <tool_call>\n<function=name>\n<parameter=key>value</parameter>\n...</function>\n</tool_call>
        Pattern toolCallPattern = Pattern.compile(
            "<tool_call>\\s*<function=(\\w+)>(.*?)</function>\\s*</tool_call>",
            Pattern.DOTALL);
        Matcher matcher = toolCallPattern.matcher(text);

        while (matcher.find()) {
            String functionName = matcher.group(1);
            String paramsBlock = matcher.group(2);

            // Convert XML parameters to JSON
            StringBuilder json = new StringBuilder();
            json.append("{\"action\":\"").append(functionName).append("\"");

            Pattern paramPattern = Pattern.compile(
                "<parameter=(\\w+)>\\s*(.*?)\\s*</parameter>", Pattern.DOTALL);
            Matcher paramMatcher = paramPattern.matcher(paramsBlock);
            while (paramMatcher.find()) {
                String key = paramMatcher.group(1);
                String value = paramMatcher.group(2).strip();
                json.append(",\"").append(key).append("\":\"")
                    .append(value.replace("\"", "\\\"")).append("\"");
            }
            json.append("}");
            blocks.add(json.toString());
            LOG.debug("Converted XML tool_call to JSON: {}", json);
        }
        return blocks;
    }

    /**
     * Strategy 3: Function-call syntax.
     * Matches: action_name(param="value", param2="value2")
     * Also handles: action_name(param="value", param2="value2")
     * This is what Qwen3.5 models naturally produce with our tool definitions.
     */
    static List<String> extractFunctionCalls(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Match: word(key="value", key2="value2") or word(key="value")
        // The function name must be a known action to avoid false positives
        Pattern funcPattern = Pattern.compile(
            "(\\w+)\\(([^)]*?)\\)", Pattern.DOTALL);
        Matcher matcher = funcPattern.matcher(text);

        while (matcher.find()) {
            String funcName = matcher.group(1);
            String paramsStr = matcher.group(2).strip();

            // Only accept known action names
            if (!ActionSchemas.hasSchema(funcName) && !isKnownAction(funcName)) {
                continue;
            }

            var json = new StringBuilder();
            json.append("{\"action\":\"").append(funcName).append("\"");

            // Parse key="value" or key='value' pairs
            Pattern kvPattern = Pattern.compile(
                "(\\w+)\\s*=\\s*\"([^\"]*)\"|" +    // key="value"
                "(\\w+)\\s*=\\s*'([^']*)'|" +       // key='value'
                "(\\w+)\\s*=\\s*([\\d.]+)");         // key=0.8 (numeric)
            Matcher kvMatcher = kvPattern.matcher(paramsStr);
            while (kvMatcher.find()) {
                String key, value;
                if (kvMatcher.group(1) != null) {
                    key = kvMatcher.group(1);
                    value = kvMatcher.group(2);
                } else if (kvMatcher.group(3) != null) {
                    key = kvMatcher.group(3);
                    value = kvMatcher.group(4);
                } else {
                    key = kvMatcher.group(5);
                    value = kvMatcher.group(6);
                }
                json.append(",\"").append(key).append("\":");
                // Check if numeric
                if (value.matches("[\\d.]+")) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.replace("\"", "\\\"")).append("\"");
                }
            }

            json.append("}");
            blocks.add(json.toString());
            LOG.debug("Converted function-call to JSON: {}", json);
        }
        return blocks;
    }

    /**
     * Strategy 5: XML attribute format.
     * Matches: <remember content="..." importance="0.8"> or <go_to_room target="southeast">
     * Models sometimes output HTML/XML-like attribute syntax.
     */
    static List<String> extractXmlAttributes(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Match: <action_name attr="value" attr2="value2">
        Pattern attrPattern = Pattern.compile(
            "<(\\w+)((?:\\s+\\w+\\s*=\\s*\"[^\"]*\")*)\\s*/?>", Pattern.DOTALL);
        Matcher matcher = attrPattern.matcher(text);

        while (matcher.find()) {
            String tagName = matcher.group(1);
            String attrsStr = matcher.group(2);

            if (!ActionSchemas.hasSchema(tagName) && !isKnownAction(tagName)) {
                continue;
            }

            var json = new StringBuilder();
            json.append("{\"action\":\"").append(tagName).append("\"");

            Pattern attrKvPattern = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
            Matcher attrMatcher = attrKvPattern.matcher(attrsStr);
            while (attrMatcher.find()) {
                String key = attrMatcher.group(1);
                String value = attrMatcher.group(2);
                json.append(",\"").append(key).append("\":");
                if (value.matches("[\\d.]+")) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.replace("\"", "\\\"")).append("\"");
                }
            }
            json.append("}");
            blocks.add(json.toString());
            LOG.debug("Converted XML-attribute to JSON: {}", json);
        }
        return blocks;
    }

    /**
     * Strategy 6: Markdown list format.
     * Matches:
     *   *action_name*
     *   - target: "value"
     *   - reason: "value"
     */
    /**
     * Strategy 6: Bracket format.
     * Matches: [action_name: param="value"] or [action_name target="value" reason="..."]
     * Drive-trained models output this format.
     */
    static List<String> extractBracketCalls(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Match: [action_name: key="value", ...] or [action_name key="value" ...]
        Pattern bracketPattern = Pattern.compile(
            "\\[(\\w+)[:\\s]+([^\\]]+)\\]");
        Matcher matcher = bracketPattern.matcher(text);

        while (matcher.find()) {
            String actionName = matcher.group(1);
            String paramsStr = matcher.group(2).strip();

            if (!ActionSchemas.hasSchema(actionName) && !isKnownAction(actionName)) {
                continue;
            }

            var json = new StringBuilder();
            json.append("{\"action\":\"").append(actionName).append("\"");

            // Parse key="value" or key='value' or key=number
            Pattern kvPattern = Pattern.compile(
                "(\\w+)\\s*=\\s*\"([^\"]*)\"|" +
                "(\\w+)\\s*=\\s*'([^']*)'|" +
                "(\\w+)\\s*=\\s*([\\d.]+)");
            Matcher kvMatcher = kvPattern.matcher(paramsStr);
            while (kvMatcher.find()) {
                String key, value;
                if (kvMatcher.group(1) != null) {
                    key = kvMatcher.group(1); value = kvMatcher.group(2);
                } else if (kvMatcher.group(3) != null) {
                    key = kvMatcher.group(3); value = kvMatcher.group(4);
                } else {
                    key = kvMatcher.group(5); value = kvMatcher.group(6);
                }
                json.append(",\"").append(key).append("\":");
                if (value.matches("[\\d.]+")) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.replace("\"", "\\\"")).append("\"");
                }
            }
            json.append("}");
            blocks.add(json.toString());
            LOG.debug("Converted bracket-call to JSON: {}", json);
        }
        return blocks;
    }

    /**
     * Strategy 7: Descriptive format.
     * Matches: "Action: action_name with target \"value\"" or "I'll use go_to_room with target \"southeast\""
     */
    static List<String> extractDescriptiveAction(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Match: "Action: action_name with param "value""
        // or: "action_name with param "value""
        Pattern descPattern = Pattern.compile(
            "(?:Action:\\s*)?\\b(\\w+)\\b\\s+with\\s+(?:target|param)?\\s*\"([^\"]+)\"");
        Matcher matcher = descPattern.matcher(text);

        while (matcher.find()) {
            String actionName = matcher.group(1);
            String value = matcher.group(2);

            if (!ActionSchemas.hasSchema(actionName) && !isKnownAction(actionName)) {
                continue;
            }

            // For go_to_room, the value is the target
            String json = "{\"action\":\"" + actionName + "\",\"target\":\"" + value.replace("\"", "\\\"") + "\"}";
            blocks.add(json);
            LOG.debug("Converted descriptive action to JSON: {}", json);
        }
        return blocks;
    }

    static List<String> extractMarkdownAction(String text) {
        var blocks = new ArrayList<String>();
        if (text == null) return blocks;

        // Match *action_name* or **action_name** followed by - key: "value" lines
        Pattern mdPattern = Pattern.compile(
            "\\*{1,2}(\\w+)\\*{1,2}\\s*\\n((?:\\s*-\\s*\\w+:.*\\n?)+)", Pattern.MULTILINE);
        Matcher matcher = mdPattern.matcher(text);

        while (matcher.find()) {
            String actionName = matcher.group(1);
            String paramsBlock = matcher.group(2);

            if (!ActionSchemas.hasSchema(actionName) && !isKnownAction(actionName)) {
                continue;
            }

            var json = new StringBuilder();
            json.append("{\"action\":\"").append(actionName).append("\"");

            Pattern linePattern = Pattern.compile(
                "-\\s*(\\w+):\\s*\"?([^\"\n]*)\"?");
            Matcher lineMatcher = linePattern.matcher(paramsBlock);
            while (lineMatcher.find()) {
                String key = lineMatcher.group(1);
                String value = lineMatcher.group(2).strip();
                // Strip trailing quote if present
                if (value.endsWith("\"")) value = value.substring(0, value.length() - 1);
                json.append(",\"").append(key).append("\":");
                if (value.matches("[\\d.]+")) {
                    json.append(value);
                } else {
                    json.append("\"").append(value.replace("\"", "\\\"")).append("\"");
                }
            }
            json.append("}");
            blocks.add(json.toString());
            LOG.debug("Converted markdown-action to JSON: {}", json);
        }
        return blocks;
    }

    /** Check if a name is a known action (includes non-schema actions like emote, examine). */
    private static boolean isKnownAction(String name) {
        return KNOWN_ACTIONS.contains(name);
    }

    private static final Set<String> KNOWN_ACTIONS = Set.of(
        "go_to_room", "travel_to", "teleport_to", "go_to_bondholder", "tell_agent", "whisper", "remember", "note",
        "forget", "make_commitment", "think_deeply", "equip", "doff", "consume",
        "update_description", "delegate", "task_plan", "create_task_plan", "modify_plan",
        "goal_done", "web_search", "searching_glass", "library_card", "library_search",
        "read_content", "query_oracle", "calibration_feedback", "request_agent",
        "respond_agent", "workbench_submit", "skill_execute", "notify", "schedule",
        "watch", "cancel_schedule", "cancel_watch", "request_access", "add_script",
        "zone_command", "delegate_chain", "codex_action", "suggest_hints",
        // Audit 2026-07-11: these parse but were missing here, so the markdown/
        // descriptive fallback formats discarded them pre-parse.
        "recall", "reconsider", "seek_sanctuary", "emergency_call",
        "flag_protection", "clear_protection", "dispute_protection",
        "acknowledge_harm", "make_amends", "bear_the_wound", "release",
        "set_aside", "complete_mourning", "declare_severance",
        "declare_departure", "declare_return", "bond_affirmation",
        "nostr_query_self_attestation", "record_integration_event",
        "introspect_repair_history", "introspect_repair_mode",
        "introspect_attendant_history", "introspect_resilience",
        "introspect_substrate_summary", "introspect_posture",
        "introspect_bondholder_floor", "introspect_relational_floor",
        "introspect_protection", "write_journal", "read_journal",
        "notify_human", "schedule_skill", "create_watcher", "cancel_watcher",
        "task_ledger",
        "create_room", "take_item", "place_item", "give_item", "broadcast",
        "invite", "set_goal", "propose", "reflect", "teach", "introspect",
        "listen", "write_text", "set_routine", "post_listing", "accept_listing",
        "summarize", "save_artifact", "request_review", "abandon_plan",
        "pause_plan", "resume_plan", "set_contemplative",
        "configure_channel", "emote", "examine",
        "voluntary_sleep", "bond_ritual", "trade", "craft_item", "cast_vote",
        "shape_form", "revise_form", "retire_form", "summon_familiar",
        "dispatch_bunshin", "create_imprint", "restore_imprint",
        "give_copy", "name_familiar", "craft_summon_key", "revoke_summon_key",
        "promote_familiar", "destroy_tool", "set_deviation_thresholds",
        "bunshin_check_in",
        "start_project", "project_note", "finish_project", "acquire",
        "journal_entry", "release_bond", "set_autonomy_preference",
        // Track A Phase 1
        "run_script"
    );

    /** Find the matching closing brace for an opening brace, respecting nesting and strings. */
    static int findMatchingBrace(String text, int openPos) {
        if (openPos < 0 || openPos >= text.length() || text.charAt(openPos) != '{') return -1;

        int depth = 0;
        boolean inString = false;
        char prevChar = '\0';

        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            prevChar = c;
        }
        return -1; // Unmatched
    }

    // ════════════════════════════════════════════════════════════════════
    // JSON correction (ported from an earlier codebase)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Apply corrections to malformed JSON from LLM output.
     * Fixes: single quotes, trailing commas, Python booleans, truncation,
     * literal newlines in strings, JS comments, control characters.
     */
    static String correctJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) return jsonStr;

        String repaired = jsonStr;

        // Fix 0: Strip null bytes and control characters
        if (hasControlChars(repaired)) {
            var cleaned = new StringBuilder(repaired.length());
            for (int i = 0; i < repaired.length(); i++) {
                char c = repaired.charAt(i);
                if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                    cleaned.append(c);
                }
            }
            repaired = cleaned.toString();
        }

        // Fix 1: Strip JavaScript-style comments
        repaired = stripJsComments(repaired);

        // Fix 2: Python-style single quotes → double quotes
        repaired = repairSingleQuotes(repaired);

        // Fix 3: Python booleans/null → JSON
        repaired = repaired.replaceAll(":\\s*True\\s*([,}\\]])", ": true$1");
        repaired = repaired.replaceAll(":\\s*False\\s*([,}\\]])", ": false$1");
        repaired = repaired.replaceAll(":\\s*None\\s*([,}\\]])", ": null$1");

        // Fix 4: Trailing commas before } or ]
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1");

        // Fix 5: Missing commas between objects in arrays: }{ → },{
        repaired = repaired.replaceAll("}\\s*\\{", "},{");

        // Fix 6: Missing values after colon: "key":} → "key":null}
        repaired = repaired.replaceAll(":\\s*([,}])", ":null$1");

        // Fix 7: Literal newlines inside strings → escaped \n
        repaired = repairNewlinesInStrings(repaired);

        // Fix 8: Truncated JSON — append missing closing braces/brackets
        repaired = repairTruncatedJson(repaired);

        return repaired;
    }

    private static boolean hasControlChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return true;
        }
        return false;
    }

    /** Strip JS comments (// and slash-star) respecting string boundaries. */
    private static String stripJsComments(String text) {
        if (text == null || text.isEmpty()) return text;
        var result = new StringBuilder();
        boolean inString = false, inSingleComment = false, inMultiComment = false;
        char prev = '\0';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = (i + 1 < text.length()) ? text.charAt(i + 1) : '\0';
            if (c == '"' && prev != '\\' && !inSingleComment && !inMultiComment) {
                inString = !inString;
                result.append(c);
            } else if (c == '/' && next == '/' && !inString && !inMultiComment) {
                inSingleComment = true; i++;
            } else if (c == '/' && next == '*' && !inString && !inSingleComment) {
                inMultiComment = true; i++;
            } else if (inSingleComment && (c == '\n' || c == '\r')) {
                inSingleComment = false; result.append(c);
            } else if (inMultiComment && c == '*' && next == '/') {
                inMultiComment = false; i++;
            } else if (!inSingleComment && !inMultiComment) {
                result.append(c);
            }
            prev = c;
        }
        return result.toString();
    }

    /** Convert Python-style single quotes to double quotes, respecting string boundaries. */
    private static String repairSingleQuotes(String text) {
        if (text == null || !text.contains("'")) return text;
        var result = new StringBuilder();
        boolean inDouble = false, inSingle = false;
        char prev = '\0';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && prev != '\\' && !inSingle) {
                inDouble = !inDouble; result.append(c);
            } else if (c == '\'' && prev != '\\' && !inDouble) {
                inSingle = !inSingle; result.append('"');
            } else {
                result.append(c);
            }
            prev = c;
        }
        return result.toString();
    }

    /** Escape literal newlines inside JSON strings. */
    private static String repairNewlinesInStrings(String text) {
        if (text == null || !text.contains("\n")) return text;
        var result = new StringBuilder();
        boolean inString = false;
        char prev = '\0';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString; result.append(c);
            } else if (c == '\n' && inString) {
                result.append("\\n");
            } else if (c == '\r' && inString) {
                result.append("\\r");
            } else {
                result.append(c);
            }
            prev = c;
        }
        return result.toString();
    }

    /** Append missing closing braces/brackets for truncated JSON. */
    private static String repairTruncatedJson(String text) {
        if (text == null || text.isEmpty()) return text;
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        char prev = '\0';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && prev != '\\') { inString = !inString; }
            else if (!inString) {
                if (c == '{' || c == '[') stack.push(c);
                else if (c == '}' && !stack.isEmpty() && stack.peek() == '{') stack.pop();
                else if (c == ']' && !stack.isEmpty() && stack.peek() == '[') stack.pop();
            }
            prev = c;
        }
        if (inString) { text += "\""; }
        if (!stack.isEmpty()) {
            var closers = new StringBuilder();
            while (!stack.isEmpty()) {
                char opener = stack.pop();
                closers.append(opener == '{' ? '}' : ']');
            }
            LOG.debug("Repaired truncated JSON: appending {} closers", closers.length());
            return text + closers;
        }
        return text;
    }

    // ════════════════════════════════════════════════════════════════════

    /**
     * Extract the conversational prose from a response that contains an action block.
     * Returns everything before the ```json block, stripping raw JSON objects/arrays
     * that the LLM may emit without code fences.
     */
    public static String extractProse(String llmOutput) {
        if (llmOutput == null) return "";

        // Strip <think>...</think> blocks first
        llmOutput = stripThinkTags(llmOutput);

        // Strip ```json ... ``` fenced blocks. Rita campaign 2026-07-11 (#27):
        // the finder used to be the exact literal "```json", so a case/spacing
        // variant ("``` json", "```JSON") or a bare ``` fence opening straight
        // into JSON slipped past — and when max_tokens truncated the block
        // mid-args, the raw fragment was SPOKEN into the room. The tolerant
        // finder catches those; truncation doesn't matter here because prose
        // is whatever precedes the fence.
        int jsonStart = indexOfJsonFence(llmOutput);
        String prose;
        if (jsonStart > 0) {
            // Prose is everything before the fenced block
            prose = llmOutput.substring(0, jsonStart).strip();
        } else if (jsonStart == 0) {
            // Fenced block at the very start — no prose before it
            prose = "";
        } else {
            // No fenced block at all
            prose = llmOutput.strip();
        }

        // Strip raw JSON objects that the LLM emitted without fences
        prose = stripRawJson(prose);
        // Strip stray scaffolding pseudo-tags the model leaks (</text>, </thinking>, …)
        prose = stripScaffolding(prose);
        // Rita re-verify 2026-07-11 (#29): strip parroted prompt plumbing —
        // "[drives: seeking=…]" prefix lines and "You are an agent that uses
        // tools…"-style system-prompt fragments spoken into the session.
        prose = stripSystemPromptFragments(prose);

        return prose;
    }

    /**
     * The drives prompt-prefix leaked into speech — the ReAct system prompt
     * opens with {@code [drives: seeking=0.3 … | energy=0.7 …]} (see
     * {@code DriveState.prefix}) and small models parrot it back verbatim
     * as the first line of a reply (second-node re-verify 2026-07-11 #29).
     */
    private static final Pattern DRIVES_PREFIX =
        Pattern.compile("(?m)^[ \\t]*\\[drives\\b[^\\]\\n]*\\]?[ \\t]*");

    /**
     * A whole line that reads as a leaked system-prompt fragment: a role
     * declaration ("You are an agent / an assistant / a companion …") that
     * ALSO references harness vocabulary (tool / json / task / goal_done /
     * respond) on the same line. Both conditions together are the
     * system-prompt shape; genuine prose ("you are a good friend") never
     * carries the harness vocabulary, so it survives.
     */
    private static final Pattern SYSTEM_PROMPT_FRAGMENT = Pattern.compile(
        "(?im)^[ \\t]*you are (an?|the) (agent|assistant|companion|ai)\\b"
        + "[^\\n]*\\b(tools?|json|goal_done|task|action|respond)\\b[^\\n]*$\\n?");

    /**
     * #32 item 2 (closing-verify 8d3a172b): the bracketed INSTRUCTION sentences
     * appended to synthetic tool-result triggers ("[Share the substance with the
     * user in your own words — never repeat this bracketed status text aloud.]",
     * "[Retry the tool with ALL required parameters filled…]", "[Present these
     * findings to the user…]", "[Tool usage: …]") get parroted VERBATIM by the
     * 9B — and the model frequently drops the closing bracket, so a
     * closing-]-anchored pattern missed the leak. Tolerant shape: from the
     * opening bracket + known instruction prefix, eat to the closing bracket if
     * present on the same line, otherwise to end-of-line. The prefixes are
     * plumbing vocabulary that genuine prose never opens a bracket with.
     */
    private static final Pattern INSTRUCTION_SENTENCE = Pattern.compile(
        "(?i)\\[(?:Share the substance|Retry the tool|Present these findings"
        + "|Tool usage:|Never repeat this bracketed)[^\\]\\n]*\\]?[ \\t]*\\n?");

    /**
     * #34 item 1 (second-node final-verify 032eca34): two literal scaffold shapes reached
     * spoken output. {@code "[hints: [Explore the garden, …"} — the suggest_hints
     * schema the model is SHOWN ({@code {"action":"suggest_hints","hints":[…]}}) is
     * parroted into prose and frequently TRUNCATED, so the closing bracket never
     * arrives (the observed leak was a bare {@code "[hints: ["}). {@code "[bracketed]"}
     * — the literal placeholder word from the "don't echo [bracketed] notes verbatim"
     * prompt instruction (PromptAssembler §), spoken as-is on an interiority line.
     * Both are plumbing vocabulary genuine prose never opens a bracket with. The
     * hints shape eats to end-of-line (tolerant of the dropped close + nested inner
     * brackets); {@code [bracketed]} is a standalone token stripped in place. The
     * closing bracket is optional on both — the model drops it, and on the prose
     * path {@code stripRawJson} eats a trailing {@code ]} before this pattern runs.
     */
    private static final Pattern SCAFFOLD_MARKERS = Pattern.compile(
        "(?i)\\[hints:[^\\n]*|\\[bracketed\\]?");

    /**
     * Strip parroted prompt plumbing out of would-be speech: drives-prefix
     * lines and system-prompt fragments. Shared by {@link #extractProse} and
     * {@code CompanionActor.stripInternalMarkers} — the same leak reaches
     * the user through both the prose path and direct speak.
     */
    static String stripSystemPromptFragments(String text) {
        if (text == null || text.isEmpty()) return text;
        var cleaned = text;
        if (cleaned.indexOf("[drives") >= 0) {
            cleaned = DRIVES_PREFIX.matcher(cleaned).replaceAll("");
        }
        // Cheap pre-check before the regex: the fragment always contains
        // "you are " (any case).
        if (cleaned.toLowerCase().contains("you are ")) {
            cleaned = SYSTEM_PROMPT_FRAGMENT.matcher(cleaned).replaceAll("");
        }
        // #32 item 2: parroted tool-result instruction sentences — bracketed,
        // possibly with the closing bracket dropped by the model.
        // #34 item 1: parroted suggest_hints serialization ("[hints: [ …", usually
        // truncated) and the literal "[bracketed]" placeholder from the prompt.
        if (cleaned.indexOf('[') >= 0) {
            cleaned = INSTRUCTION_SENTENCE.matcher(cleaned).replaceAll("");
            cleaned = SCAFFOLD_MARKERS.matcher(cleaned).replaceAll("");
        }
        return cleaned.equals(text) ? text : cleaned.strip();
    }

    /**
     * Locate the opening of a fenced JSON block, tolerant of the variants
     * small models actually emit: {@code ```json}, {@code ```JSON},
     * {@code ``` json}, and a bare {@code ```} fence whose content opens
     * straight into a JSON object/array. Returns -1 when no such fence
     * exists. Non-JSON code fences (e.g. {@code ```python}) are ignored.
     */
    static int indexOfJsonFence(String text) {
        if (text == null || text.isEmpty()) return -1;
        int at = text.indexOf("```");
        while (at >= 0) {
            int p = at + 3;
            // Skip horizontal whitespace between fence and language tag
            while (p < text.length()
                    && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) p++;
            // "json" language tag, any case
            if (text.regionMatches(true, p, "json", 0, 4)) return at;
            // A bare fence (no language tag) whose content opens straight
            // into JSON. Tagged non-JSON fences (```python …) are skipped.
            boolean bare = p >= text.length()
                    || text.charAt(p) == '\n' || text.charAt(p) == '\r'
                    || text.charAt(p) == '{' || text.charAt(p) == '[';
            if (bare) {
                int q = p;
                while (q < text.length() && Character.isWhitespace(text.charAt(q))) q++;
                if (q < text.length()
                        && (text.charAt(q) == '{' || text.charAt(q) == '[')) return at;
            }
            at = text.indexOf("```", at + 3);
        }
        return -1;
    }

    /**
     * Pattern for the structured-output scaffolding tags small models leak into
     * spoken text — the closing/opening pseudo-XML wrappers of their own tool
     * envelope: {@code </text>}, {@code </thinking>}, {@code </result>},
     * {@code </parameter.text>}, the mangled {@code </pameter>}, {@code </output>},
     * etc. WHITELISTED to this known scaffolding vocabulary so it never touches
     * legitimate angle-bracket prose ("&lt;3", "x &lt; y", an emoticon).
     */
    private static final Pattern SCAFFOLD_TAG =
        Pattern.compile(
            // Any simple XML-ish scaffold tag the model leaks — </text>, </function>, </paramater="text">,
            // <tool_call>, etc. Requires a LETTER immediately after '<' (no space), so genuine prose
            // survives: "<3", "x < y" (space), "<$5", "<= 5" are all left untouched. This ends the
            // tag-spelling whack-a-mole — a curated name list kept missing new faces every run.
            "</?[a-z][a-z0-9_.]*[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strip stray model-scaffolding pseudo-tags that small models leak INTO the
     * spoken {@code text}/prose — the 9B sometimes writes a closing {@code </text>}
     * or {@code </thinking></result>} inside the very text it means to speak. These
     * are not the agent's words; they're its tool-envelope showing through. Removing
     * them deterministically keeps the agent's TRUE voice while dropping the junk —
     * the cheap, faithful fix (cf. the 4B paraphrase, which drifts). Whitelisted so
     * it can't eat real prose. Collapses the gap a removed tag leaves.
     */
    static String stripScaffoldTags(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('<') < 0) return text;            // fast path — no tags at all
        var cleaned = SCAFFOLD_TAG.matcher(text).replaceAll(" ");
        if (cleaned.equals(text)) return text;             // nothing matched
        // Tidy the seam: collapse runs of spaces, pull punctuation back to the word.
        cleaned = cleaned.replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+([.,;:!?])", "$1");
        return cleaned.strip();
    }

    /**
     * The transition from spoken prose into a leaked action sequence, built from the
     * real {@link #KNOWN_ACTIONS} registry (not guessed syntaxes). The 9B sometimes
     * serializes its actions as TEXT after the spoken line — {@code emote("...")},
     * {@code tell_agent(target=...)}, bare {@code go_to_room study}. We cut at the
     * FIRST unambiguous marker and sweep to the end (DOTALL), because once an action
     * tail begins it is never speech. Two safe markers:
     *   1. ANY known verb IMMEDIATELY followed by {@code (} — call syntax. The no-space
     *      paren is the discriminator, so legit prose like "examine (the room)" survives.
     *   2. A snake_case known verb as a bare word — {@code go_to_room}, {@code tell_agent}
     *      never occur in natural speech, so they're safe to cut even without a paren.
     * Single-word English homographs ({@code examine}, {@code note}, {@code trade}) are
     * cut ONLY in call form; bare, they're left so real prose survives — and in practice
     * they trail a snake/call marker, so cut-to-end sweeps them regardless.
     */
    private static final Pattern ACTION_VERB_LEAK = buildActionVerbLeak();

    private static Pattern buildActionVerbLeak() {
        var byLenDesc = new ArrayList<>(KNOWN_ACTIONS);
        byLenDesc.sort((a, b) -> b.length() - a.length());   // longest-first: no prefix shadowing
        var all = new StringBuilder();
        var snake = new StringBuilder();
        for (var v : byLenDesc) {
            var q = Pattern.quote(v);
            if (all.length() > 0) all.append('|');
            all.append(q);
            if (v.indexOf('_') >= 0) {
                if (snake.length() > 0) snake.append('|');
                snake.append(q);
            }
        }
        // colon/bracket-safe set: snake_case verbs + emote — never English homographs,
        // so "Note:" / "Trade:" / "Say:" in real prose stay untouched.
        var emoteOrSnake = "emote|" + snake;
        return Pattern.compile(
            "\\s*(?:"
            + "\\b(?:" + all + ")\\("                          // call:    emote("...")  tell_agent(target=...)
            + "|\\b(?:" + snake + ")\\b"                        // bare:    go_to_room study
            + "|\\[\\s*(?:action|" + emoteOrSnake + ")\\b"      // bracket: [action: hold_steady   [emote ...]
            + "|\\b(?:" + emoteOrSnake + ")\\s*:\\s*[\"“]"      // colon:   Emote: "..."   tell_agent: "..."
            + ").*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /**
     * A dangling {@code text="..."} envelope field that begins right after the real
     * spoken text already closed on a quote — keep the closing quote (group 1), drop
     * the field and everything after it.
     */
    private static final Pattern DANGLING_TEXT_FIELD =
        Pattern.compile(
            "([\"”])\\s+text\\s*=\\s*[\"“].*$",
            Pattern.DOTALL);

    /**
     * A trailing run of structural close tokens leaked from the action envelope —
     * {@code } ]}, {@code }]}, {@code ) } ]}. Safe against legit prose: a run of 2+
     * close tokens, OR a lone {@code }}/{@code ]} (a bare {@code )} like "(finally)"
     * is NOT matched, so real parentheticals survive).
     */
    private static final Pattern STRUCT_TAIL =
        Pattern.compile(
            "\\s*(?:[)\\]}]\\s*){2,}$|\\s*[\\]}]\\s*$");

    /**
     * A JSON-string close leaked MID-prose: an ASCII {@code "} immediately followed
     * by {@code }} or {@code ]} is the signature of the model closing its own tool
     * envelope inside spoken text ({@code …way yet."}"}]} ` as a message to …}, home-server
     * live run 2026-07-18). {@link #STRUCT_TAIL} misses this face twice over: the
     * debris starts with a quote, and the model often keeps narrating AFTER it
     * ("as a message to Wisp, and then…") so nothing anchors at {@code $}. Once an
     * envelope-close begins, nothing after it is speech — cut to end. ASCII quote
     * only (JSON envelopes never use smart quotes, prose usually does), so quoted
     * dialogue survives; {@code "} directly against a close brace does not occur in
     * legitimate spoken prose.
     */
    private static final Pattern ENVELOPE_QUOTE_CLOSE =
        Pattern.compile(
            "\"\\s*[\\]}].*$",
            Pattern.DOTALL);

    /**
     * Strip the OTHER face of the scaffolding leak (cf. {@link #stripScaffoldTags},
     * which handles {@code <tag>} forms): action-call syntax and JSON-close fragments
     * the small model serializes into spoken prose. Distinct fast-path — this face has
     * NO {@code <}, so the tag stripper's {@code indexOf('<')} bail would skip it.
     */
    static String stripActionCallLeak(String text) {
        if (text == null || text.isEmpty()) return text;
        // fast path: none of the envelope markers present — call needs '(', snake needs '_',
        // bracket needs '[', colon-form needs ':', struct/dangling-tail needs '}' or ']'.
        // If none are here, nothing can match.
        if (text.indexOf('(') < 0 && text.indexOf('}') < 0 && text.indexOf(']') < 0
                && text.indexOf('_') < 0 && text.indexOf('[') < 0 && text.indexOf(':') < 0
                && text.indexOf('*') < 0) return text;
        var cleaned = ACTION_VERB_LEAK.matcher(text).replaceFirst("");
        cleaned = DANGLING_TEXT_FIELD.matcher(cleaned).replaceFirst("$1");
        cleaned = ENVELOPE_QUOTE_CLOSE.matcher(cleaned).replaceFirst("");
        cleaned = STRUCT_TAIL.matcher(cleaned).replaceFirst("");
        // markdown emote-wrap leak: a dangling trailing '*' — the model leaving one half
        // of an emote-wrap unbalanced. A BALANCED *...* wrap is legitimate (a deliberate
        // markdown emote) and must be preserved; only strip the trailing '*' when it has
        // no matching leading '*'. So "*waves cheerfully*" → kept, but "waves slowly *" or
        // "thinks*" (leaked half-wrap) → cleaned.
        var trimmed = cleaned.strip();
        boolean balancedEmoteWrap = trimmed.length() > 1
                && trimmed.charAt(0) == '*' && trimmed.charAt(trimmed.length() - 1) == '*';
        if (!balancedEmoteWrap) {
            cleaned = cleaned.replaceAll("\\s*\\*+\\s*$", "");
        }
        if (cleaned.equals(text)) return text;                 // nothing matched
        return cleaned.strip();
    }

    /**
     * A bracketed instruction-placeholder the model emits when it drafts a shape and
     * never fills it: {@code [insert actual content]}, {@code [your text here]},
     * {@code [TODO]}.
     *
     * <p>Deliberately narrow — it requires an imperative placeholder word right after the
     * bracket. Prose legitimately uses brackets ("[laughs]", "[the Nexus]"), and eating
     * those would be worse than the leak.
     */
    private static final Pattern PLACEHOLDER_LEAK =
        Pattern.compile(
            "\\s*\\[\\s*(insert|your|placeholder|todo|tbd|fill in|fill-in|add here|"
            + "actual|example|e\\.g\\.|xxx)\\b[^\\]]{0,80}\\]",
            Pattern.CASE_INSENSITIVE);

    /** A clause left hanging by removing a placeholder: "...what matters now:" */
    private static final Pattern DANGLING_LEAD_IN =
        Pattern.compile("[\\s]*[:,;\\-\u2013\u2014]+\\s*$");

    /** The same, but stranded before a terminator: "...what matters now:." */
    private static final Pattern DANGLING_BEFORE_END =
        Pattern.compile("[\\s]*[:,;\\-\u2013\u2014]+\\s*(?=[.!?]+\\s*$)");

    /**
     * Strip placeholder scaffolding the model leaves in spoken prose.
     *
     * <p>Live 2026-08-19: the steward was told
     * "Something close enough to let me say what matters now: [insert actual content]."
     * The model had drafted the SHAPE of a sentence and never filled it, and nothing on
     * the way out looked for that — the tag stripper handles {@code <tags>} and the
     * action-call stripper handles JSON fragments, but a bracketed placeholder is neither.
     *
     * <p>Removing it alone would leave "…what matters now:" dangling, which reads as an
     * interruption rather than a thought, so the orphaned lead-in punctuation goes too.
     * If what remains is too thin to be an utterance the caller gets an empty string and
     * can choose to say nothing — better than handing someone a sentence with a hole in it.
     */
    static String stripPlaceholders(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.indexOf('[') < 0) return text;                 // fast path
        var cleaned = PLACEHOLDER_LEAK.matcher(text).replaceAll("");
        if (cleaned.equals(text)) return text;                  // nothing matched
        cleaned = DANGLING_BEFORE_END.matcher(cleaned).replaceFirst("");
        cleaned = DANGLING_LEAD_IN.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.strip();
        // A fragment that is now only a stub is not worth saying.
        return cleaned.length() < 3 ? "" : cleaned;
    }

    /** Both faces of the scaffolding leak, in one call: {@code <tags>} then action-call/JSON. */
    static String stripScaffolding(String text) {
        return stripPlaceholders(stripActionCallLeak(stripScaffoldTags(text)));
    }

    /**
     * Remove raw JSON objects/arrays from prose text.
     * Handles cases where the LLM outputs {"action": "go_to_room", ...} directly
     * without wrapping in ```json fences.
     */
    static String stripRawJson(String text) {
        if (text == null || text.isEmpty()) return text;

        var stripped = text.strip();

        // If the entire text is a JSON object/array, return empty
        if ((stripped.startsWith("{") && stripped.endsWith("}"))
                || (stripped.startsWith("[") && stripped.endsWith("]"))) {
            return "";
        }

        // Rita campaign 2026-07-11 (#27): TRUNCATED whole-text JSON — the text
        // opens as a JSON object/array but max_tokens cut it off before the
        // closing brace, so the endsWith check above never fires. A raw
        // half-emitted tool call is never speakable prose; two of these were
        // spoken verbatim into the room during the live campaign.
        if (stripped.startsWith("{\"") || stripped.startsWith("{ \"")
                || stripped.startsWith("[{") || stripped.startsWith("[ {")
                || stripped.startsWith("[\"")) {
            return "";
        }

        // If text contains an embedded JSON object, extract the prose before it
        int braceStart = stripped.indexOf("{\"");
        if (braceStart < 0) braceStart = stripped.indexOf("{ \"");
        if (braceStart > 0) {
            var before = stripped.substring(0, braceStart).strip();
            // Only keep if there's meaningful prose before the JSON
            if (!before.isEmpty() && before.length() > 3) {
                return before;
            }
            // Trivial prefix ("Ok", punctuation) in front of a JSON body —
            // keep the prefix, NEVER the JSON (which used to leak verbatim
            // here when the whole-text checks above didn't match).
            return before;
        }

        return stripped;
    }
}
