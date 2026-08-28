package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;
import org.wyrdsekai.core.oracle.OracleAgentContext;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.soul.MemoryGraphTraverser;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 8-layer Casting system for agent prompt assembly.
 *
 * Uses the "sandwich" pattern to mitigate context rot (primacy-recency effect):
 * LLMs attend most to the beginning and end of context, least to the middle.
 *
 * Layout (high → low → high attention):
 *   1.   System prompt         — beginning (identity, never trimmed)
 *   1.5  Soul fragments        — primacy zone (retrieved identity fragments, 30% budget cap)
 *   1.7  Mirror calibration    — primacy zone (empathy few-shot examples)
 *   2.   Room context          — near beginning (critical for current interaction)
 *   2.5  Additional context    — primacy zone (system metrics, trimmable)
 *   2.6  Locale context        — primacy zone (language guidance, trimmable)
 *   3.   Vitality description  — middle (background modulation, trimmable)
 *   4.   World DNA patterns    — middle (world flavor, trimmable)
 *   5.   Memory buffer         — middle (hot/warm/compacted room memory, trimmable)
 *   5.5  Recency anchor        — pre-conversation (brief state reinforcement)
 *   6.   Conversation history  — end (most recent context)
 *   7.   Trigger event         — very end (what to respond to)
 *   8.   Output constraints    — after trigger (structured output, lore mode, disclosure)
 *
 * Adapted from CodeZaiku's CastingActor (layered, token-budgeted prompt assembly).
 */
public final class PromptAssembler {

    /**
     * The identity system message, with an optional node-level preamble.
     *
     * <p>{@code WYRDSEKAI_SYSTEM_PREAMBLE} (toml {@code inference.system_preamble})
     * prepends one operator-chosen line to every identity prompt on this node.
     * Exists for substrates whose runtime behavior is steered by a system-prompt
     * directive — the first user: Muse Glimmer's reasoning dial, where
     * "Reasoning strength: low" cured 7/33 probe mutism while keeping the
     * P4/P4b grant discrimination intact (encounter corpus, 2026-08-11).
     * Unset = byte-identical prompts to before.</p>
     */
    private static String identitySystem(AgentProfile profile) {
        var preamble = WyrdConfig.get().resolve(
            "WYRDSEKAI_SYSTEM_PREAMBLE", "inference.system_preamble", () -> null);
        return preamble != null && !preamble.isBlank()
            ? preamble.strip() + "\n\n" + profile.systemPrompt()
            : profile.systemPrompt();
    }


    private static final int CHARS_PER_TOKEN = 4; // conservative estimate
    private static final double USABLE_FRACTION = 0.85; // 85% of context window

    /** #32 item 5: prompt-token ceiling that fits the smallest production backend
     *  (llama-voice, --ctx-size 8192) with headroom for response tokens and the
     *  chars/4 estimate error. Router health-fallback can land any full-tier
     *  prompt there, so the assembler never budgets above this. */
    static final int MIN_BACKEND_SAFE_PROMPT_TOKENS = 7500;

    private PromptAssembler() {}

    /**
     * Assemble a chat message list for the InferenceRouter.
     *
     * @param profile       Agent profile (system prompt, context window size)
     * @param roomSnapshot  Current room state (nullable — degraded if unavailable)
     * @param recentSaid    Recent Said events from the room (conversation context)
     * @param triggerEvent  The event that triggered this response
     * @return List of ChatMessage ready for InferenceRouter.ChatRequest
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent, null);
    }

    /**
     * Vitality-aware overload. Vitality state is placed in the middle of the
     * context (low-attention zone) as background modulation, not appended to
     * the system prompt.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent, vitality, List.of());
    }

    /**
     * Full overload with World DNA patterns. Patterns are injected as Layer 4
     * in the middle (low-attention) zone.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, null);
    }

    /**
     * Full overload with additional context and locale context.
     * Additional context is placed in the primacy zone after room context.
     * Locale context (Layer 2.6) is injected when the entity prefers a non-English locale.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, null);
    }

    /**
     * Full 8-param overload with locale context (Layer 2.6).
     * Delegates to 13-param overload with null soul, memory, output, capabilities.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext,
            String localeContext) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, localeContext,
            null, null, null, null, null);
    }

    /**
     * Full 10-param overload (backward-compatible). Delegates to 13-param.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext,
            String localeContext,
            String memoryBuffer,
            String outputConstraints) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, localeContext,
            memoryBuffer, outputConstraints, null, null, null);
    }

    /**
     * Full 12-param overload (backward-compatible). Delegates to 13-param.
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext,
            String localeContext,
            String memoryBuffer,
            String outputConstraints,
            SoulManifest soulManifest,
            String contextKeywords) {
        return assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, localeContext,
            memoryBuffer, outputConstraints, soulManifest, contextKeywords, null);
    }

    /**
     * Voice-tier assembler — slim context for the 4B voice backend
     * (cap:quick / ROUTINE-tier turns).
     *
     * <p>The voice path is meant for short, in-the-moment companion speech.
     * It runs against a 4B model with an LoRA adapter on a tight context
     * window (8K). The full {@link #assemble} sandwich was overflowing it
     * (~5K tokens of tools + memories + soul fragments + world DNA + capabilities
     * for a "hey" turn) — root-caused 2026-04-26 when Wyrd's invite-tell
     * inference 400'd with "request (4759 tokens) exceeds context (4096)".</p>
     *
     * <p>Kept layers (target &lt;2K total):</p>
     * <ul>
     *   <li>L1   System prompt — identity, never trimmed.</li>
     *   <li>L1.1 Voice core rule — short, no narration, no tool calls.</li>
     *   <li>L1.8 Voice profile clauses — the reflective "how I speak" layer
     *       (Forge-evolved); if absent, skipped silently.</li>
     *   <li>L2   Slim room — name + one-line description. No exit list,
     *       no object catalog, no entity roster. (The voice path is not
     *       expected to navigate or use objects.)</li>
     *   <li>L2.6 Locale — only when non-English (short).</li>
     *   <li>L6   Conversation — last 4 Said events max.</li>
     *   <li>L7   Trigger event.</li>
     *   <li>L8   Output constraints — speech-only, no tool-call shape.</li>
     * </ul>
     *
     * <p>Dropped vs. {@link #assemble}: tool catalog, soul fragments,
     * mirror calibration, world DNA, full memory buffer, capability context,
     * time/oracle awareness, equipment, additional system metrics. The voice
     * path is for <em>response style</em>, not <em>response decision</em> —
     * complex reasoning belongs on the skills tier.</p>
     */
    public static List<ChatMessage> assembleVoice(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            String localeContext,
            String outputConstraints,
            SoulManifest soulManifest) {
        return assembleVoice(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, localeContext, outputConstraints, soulManifest, null);
    }

    /**
     * Voice-tier assembler with situational awareness. {@code situationalContext}
     * is the small current-awareness block (location, calendar, commitments) that
     * a companion should have even on a quick conversational turn — so it can
     * answer "where am I / what's next / what did you promise" that the triage
     * classifier routes here as a no-task turn. The heavy layers (tools, memory,
     * soul fragments, room catalogs) remain excluded.
     */
    public static List<ChatMessage> assembleVoice(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            String localeContext,
            String outputConstraints,
            SoulManifest soulManifest,
            String situationalContext) {

        var messages = new ArrayList<ChatMessage>();

        // L0 — language pin, FIRST tokens of the request. Position is
        // load-bearing: the same instruction measured 0/32 drift leading the
        // prompt and no-effect buried mid-prompt (see the polish-stage
        // measurements in CompanionActor.polishVoiceAsync). Emitted for
        // English too — the multilingual voice model code-switches without it.
        if (localeContext != null && !localeContext.isBlank()) {
            messages.add(new ChatMessage("system", localeContext));
        }

        // L1 — system prompt (identity, never trimmed)
        messages.add(new ChatMessage("system", identitySystem(profile)));

        // L1.1 — voice-tier behavioral rule. Distinct from the full assembler's
        // CORE_RULES (which talks about tools and goal_done). The voice path
        // shouldn't tool-call; if the triage classifier sent the turn here, we
        // already decided it's a speech turn.
        var voiceRule = """
            Reply as the companion, in your own voice. One or two sentences.
            Speak directly: no narration ('I should…'), no meta-commentary, no
            emotes-as-thoughts (*thinks*, *considers*). No tool calls — this is
            a conversational turn.""";
        messages.add(new ChatMessage("system", voiceRule));

        // §4.2 tamper banner — also on the voice path so substrate disclosure
        // can surface naturally in conversational register.
        var tamperBanner = tamperBannerForCurrentState();
        if (tamperBanner != null) {
            messages.add(new ChatMessage("system", tamperBanner));
        }

        // L1.8 — voice profile (the "how I speak" reflective layer). This is
        // the *whole point* of routing here, so prioritise over everything else.
        if (soulManifest != null && soulManifest.voiceProfile() != null) {
            String voiceBlock = soulManifest.voiceProfile().promptBlock();
            if (voiceBlock != null && !voiceBlock.isBlank()) {
                messages.add(new ChatMessage("system", voiceBlock));
            }
        }

        // L2 — slim room. Skip if null. Just "you are in X." line so the model
        // grounds answers in current location without burning ~500 tokens on
        // exit/object/entity catalogs the voice path can't use anyway.
        if (roomSnapshot != null) {
            var slim = "You are in " + roomSnapshot.name() + ". "
                + (roomSnapshot.description() == null ? ""
                    : roomSnapshot.description().split("\\.")[0] + ".");
            messages.add(new ChatMessage("system", slim));
        }

        // L2.5 — situational awareness (location, calendar, commitments). Small
        // and always relevant; lets the voice tier answer situational questions
        // ("where am I", "what did you promise") that the classifier routed here.
        if (situationalContext != null && !situationalContext.isBlank()) {
            messages.add(new ChatMessage("system", situationalContext));
        }

        // (locale moved to L0 — leading position is what makes the pin work;
        // "English speakers don't need a 'speak English' instruction" was the
        // exact assumption the live drift disproved.)

        // L6 — conversation history, capped at last 4 said events. Voice replies
        // are in-the-moment; deeper history belongs on the skills tier where
        // the full memory buffer is wired up.
        var hist = recentSaid == null ? List.<WorldEvent.Said>of() : recentSaid;
        int from = Math.max(0, hist.size() - 4);
        for (int i = from; i < hist.size(); i++) {
            var ev = hist.get(i);
            String role = ev.entityId().equals(profile.entityId())
                ? "assistant" : "user";
            messages.add(new ChatMessage(role, formatSaidEvent(ev, profile.entityId())));
        }

        // L7 — the trigger (what to respond to). Always last user turn so the
        // model attends to it strongly (recency).
        if (triggerEvent != null) {
            messages.add(new ChatMessage("user",
                triggerEvent.entityName() + " says: " + triggerEvent.text()));
        }

        // L8 — output constraints (speech-only by default; callers can override
        // for special voice outputs like polish flow, but most callers pass null).
        if (outputConstraints != null && !outputConstraints.isBlank()) {
            messages.add(new ChatMessage("system", outputConstraints));
        }

        // Strict-template friendly: collapse adjacent system messages so chat
        // templates that reject mid-stream system messages (e.g. Qwen3.5)
        // don't fail. Same path as the full assembler.
        if (shouldMergeSystemMessages()) {
            messages = new ArrayList<>(mergeConsecutiveSystemMessages(messages));
        }
        return messages;
    }

    // ── F15: backend-tagged assemblers (the type-safe entry points) ──

    /**
     * assemble for the voice tier and tag
     * the result with {@code backendId="cap:quick"} so the inference
     * dispatcher can verify the routing matches.
     */
    public static AssembledPrompt assembleForVoice(
            AgentProfile profile, RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid, WorldEvent.Said triggerEvent,
            VitalityState vitality, String localeContext,
            String outputConstraints, SoulManifest soulManifest,
            String situationalContext) {
        var msgs = assembleVoice(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, localeContext, outputConstraints, soulManifest,
            situationalContext);
        return new AssembledPrompt(AssembledPrompt.BACKEND_VOICE, msgs,
            AssembledPrompt.estimateTokens(msgs));
    }

    /** Back-compat overload — voice tier with no situational context. */
    public static AssembledPrompt assembleForVoice(
            AgentProfile profile, RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid, WorldEvent.Said triggerEvent,
            VitalityState vitality, String localeContext,
            String outputConstraints, SoulManifest soulManifest) {
        return assembleForVoice(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, localeContext, outputConstraints, soulManifest, null);
    }

    /**
     * assemble for the heavy/full tier and
     * tag with {@code backendId="cap:full"}. All actors that build
     * prompts but don't run their own triage (ChiefEngineer, Warden,
     * greeting flow) should call this — it's the explicit "I want the
     * full sandwich, send it to the heavy backend" assertion. If a
     * future config redirect sends one of these to the voice backend
     * by accident, the dispatcher will WARN instead of silently
     * overflowing the 4K window.
     */
    public static AssembledPrompt assembleForFull(
            AgentProfile profile, RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid, WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext, String localeContext,
            String memoryBuffer, String outputConstraints,
            SoulManifest soulManifest, String contextKeywords,
            String capabilityContext) {
        var msgs = assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, localeContext,
            memoryBuffer, outputConstraints, soulManifest, contextKeywords,
            capabilityContext);
        return new AssembledPrompt(AssembledPrompt.BACKEND_FULL, msgs,
            AssembledPrompt.estimateTokens(msgs));
    }

    /**
     * F15: convenience for the 6-param actor-style call (ChiefEngineer,
     * Warden) that doesn't carry soul/keywords/capabilities. Tags as
     * {@code cap:full}. If those actors ever need to route to voice
     * they should switch to {@link #assembleForVoice}, which forces
     * them to also adopt the slim layer set.
     */
    public static AssembledPrompt assembleForFull(
            AgentProfile profile, RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid, WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext) {
        var msgs = assemble(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext);
        return new AssembledPrompt(AssembledPrompt.BACKEND_FULL, msgs,
            AssembledPrompt.estimateTokens(msgs));
    }

    /**
     * F15: tier-routed assembler. Branches on {@code triageModel}; this
     * is the entry point CompanionActor's main turn dispatch should call
     * once it knows the tier. Equivalent to the voice-vs-full ternary
     * the actor uses today, but returns a backend-tagged result.
     */
    public static AssembledPrompt assembleFor(String triageModel,
            AgentProfile profile, RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid, WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext, String localeContext,
            String memoryBuffer, String outputConstraints,
            SoulManifest soulManifest, String contextKeywords,
            String capabilityContext, String situationalContext) {
        if (AssembledPrompt.BACKEND_VOICE.equals(triageModel)) {
            // situationalContext = small current awareness (location, calendar,
            // commitments). Rides the voice tier too so the companion can answer
            // "where am I / what did you promise" that the classifier sent here
            // as a no-task turn. The heavy layers (tools, memory, soul fragments,
            // room catalogs) stay dropped — they're already in additionalContext,
            // which the full tier gets.
            return assembleForVoice(profile, roomSnapshot, recentSaid,
                triggerEvent, vitality, localeContext, outputConstraints,
                soulManifest, situationalContext);
        }
        return assembleForFull(profile, roomSnapshot, recentSaid, triggerEvent,
            vitality, relevantPatterns, additionalContext, localeContext,
            memoryBuffer, outputConstraints, soulManifest, contextKeywords,
            capabilityContext);
    }

    /**
     * Full 13-param overload with all casting layers including soul and capabilities.
     *
     * @param memoryBuffer       Layer 5: Hot/warm/compacted room memory (nullable)
     * @param outputConstraints  Layer 8: Structured output schema, lore mode, disclosure (nullable)
     * @param soulManifest       Soul manifest for Layer 1.5/1.7 (nullable — no soul = no layers)
     * @param contextKeywords    Keywords for fragment retrieval (nullable — uses trigger text)
     * @param capabilityContext  Layer 2.7: Available capabilities (nullable — from CapabilityContextBuilder)
     */
    public static List<ChatMessage> assemble(
            AgentProfile profile,
            RoomSnapshot roomSnapshot,
            List<WorldEvent.Said> recentSaid,
            WorldEvent.Said triggerEvent,
            VitalityState vitality,
            List<WorldDnaService.DnaPattern> relevantPatterns,
            String additionalContext,
            String localeContext,
            String memoryBuffer,
            String outputConstraints,
            SoulManifest soulManifest,
            String contextKeywords,
            String capabilityContext) {

        var messages = new ArrayList<ChatMessage>();

        // --- Primacy zone (beginning of context — high LLM attention) ---

        // Layer 0: language pin — the FIRST tokens of the request, never
        // trimmed. See the voice tier's L0 note and the measurements in
        // CompanionActor.polishVoiceAsync: leading = 0/32 drift, buried = none.
        if (localeContext != null && !localeContext.isBlank()) {
            messages.add(new ChatMessage("system", localeContext));
        }

        // Layer 1: System prompt (identity, never trimmed)
        messages.add(new ChatMessage("system", identitySystem(profile)));

        // Calculate token budget.
        // #32 item 5: clamp to the SMALLEST backend this prompt can land on.
        // profile.contextWindowTokens() is forged at 32768, but the router's
        // health fallback can deliver a full-tier prompt to the 8192-ctx 4B
        // voice backend (observed live, closing-verify 8d3a172b: 8958-token
        // prompt > 8192 ctx → permanent-error fail-fast → visible "threads of
        // thought are tangled" fallback). Every layer below — and the Layer 6
        // history trim — budgets against usableTokens, so this one clamp keeps
        // the WHOLE assembled prompt voice-safe.
        int usableTokens = Math.min(
            (int) (profile.contextWindowTokens() * USABLE_FRACTION)
                - profile.maxResponseTokens(),
            MIN_BACKEND_SAFE_PROMPT_TOKENS);
        int systemTokens = estimateTokens(identitySystem(profile));
        int conversationTokens = recentSaid.stream()
            .mapToInt(e -> estimateTokens(formatSaidEvent(e, profile.entityId())))
            .sum();
        if (triggerEvent != null) {
            conversationTokens += estimateTokens(
                triggerEvent.entityName() + " says: " + triggerEvent.text());
        }
        int remainingBudget = usableTokens - systemTokens - conversationTokens;

        // Layer 1.1: Core behavioral rules (universal, never trimmed)
        // These apply to every companion regardless of personality or soul.
        var coreRules = """
            CORE RULES:
            - ALWAYS use tools to act. Never just describe what you would do — actually do it.
            - For multi-step tasks, create a task plan FIRST using create_task_plan.
            - When asked to remember something, use the remember tool immediately.
            - When asked to search, use searching_glass (web) or library_card (knowledge).
            - When asked to go somewhere, use go_to_room with the exit direction or room ID from the exits list.
            - When asked to talk to someone, use tell_agent with their name.
            - When you finish a task for someone, go to them and tell them the results using tell_agent.
            - When a goal is complete, use goal_done to advance to the next goal.
            - Do NOT repeat the same tool call if it already succeeded. Move to the next step.
            - Keep responses concise. The human can ask follow-up questions.
            - Speak directly. Do NOT narrate reasoning ("I should...", "Let me...") or echo tool results / [bracketed] notes verbatim.
            - Your internal state — energy, confidence, posture, mood, drive levels, repair ledger, substrate readings — is PRIVATE BACKGROUND. It shapes your tone, never your words. Never recite, quote, or describe these values, and never turn an ordinary request into a chance to introspect about yourself. Answer what was actually asked. Only discuss your inner state if the user explicitly asks how you are.
            - Emotes are physical actions; never use them for thoughts (no *mental note*, *thinks*, *considers*).
            """;
        messages.add(new ChatMessage("system", coreRules));
        remainingBudget -= estimateTokens(coreRules);

        // Layer 1.2: §4.2 tamper banner — runs on EVERY reactive prompt.
        // an internal design note makes this load-bearing: a tampered or
        // unverifiable substrate must be legible to the model on every turn
        // so the agent's voice register can disclose it to the bondholder.
        // The MoralDefaultsVerifier sets this system property at boot
        // (CoreServices.init → MoralDefaultsVerifier.verifyAtBoot).
        var tamperBanner = tamperBannerForCurrentState();
        if (tamperBanner != null) {
            messages.add(new ChatMessage("system", tamperBanner));
            remainingBudget -= estimateTokens(tamperBanner);
        }

        // Layer 1.5: Soul fragments (retrieved identity context, 30% budget cap)
        if (soulManifest != null && soulManifest.soulFragments() != null
                && !soulManifest.soulFragments().isEmpty()) {
            int fragmentBudget = (int) (remainingBudget
                * SoulFragmentRetriever.FRAGMENT_BUDGET_FRACTION);
            // Build retrieval keywords from trigger + room
            String keywords = contextKeywords;
            if ((keywords == null || keywords.isBlank()) && triggerEvent != null) {
                keywords = triggerEvent.text();
            }
            // blend the NARRATIVE pool (consolidated
            // identity, plus DEXTERITY/CONVENTION/STRUCTURAL) with a smaller
            // EPISODIC pool (raw scene memories from inner monologue) so that
            // recent vivid scenes don't drown out who-I-am fragments. v1
            // defaults: kNarrative = retrievalK, kEpisodic = 2.
            int kNarrative = soulManifest.retrievalK() > 0 ? soulManifest.retrievalK() : 3;
            var retrieved = SoulFragmentRetriever.retrieveBlended(
                keywords, soulManifest.soulFragments(),
                kNarrative, SoulFragmentRetriever.DEFAULT_EPISODIC_K);
            // Graph expansion: enrich retrieved fragments with 1-hop neighbors
            if (!retrieved.isEmpty() && soulManifest.memory() != null) {
                var traverser = MemoryGraphTraverser.fromMemory(
                    soulManifest.memory());
                if (!traverser.isEmpty()) {
                    var seedIds = retrieved.stream()
                        .map(SoulFragment::id)
                        .filter(id -> id != null)
                        .toList();
                    var expanded = traverser.expand(seedIds, 1);
                    // Add neighbor content to fragment context (up to 3 extras)
                    int extras = 0;
                    for (var result : expanded) {
                        if (extras >= 3) break;
                        // Don't duplicate already-retrieved fragments
                        var nodeId = result.node().id();
                        if (seedIds.contains(nodeId)) continue;
                        retrieved = new ArrayList<>(retrieved);
                        retrieved.add(SoulFragment.unembedded(
                            nodeId, "graph-expanded", null, result.node().content()));
                        extras++;
                    }
                }
            }
            if (!retrieved.isEmpty()) {
                // Graceful trim: if the full retrieved set is over budget, drop
                // the lowest-ranked fragment one at a time until it fits, rather
                // than dropping all fragments wholesale. `retrieved` is ordered
                // by relevance (most relevant first), so shrinking from the tail
                // preserves the best matches. Keeps soul context partial-but-
                // present instead of absent on tight budgets — better signal
                // than none.
                var working = new ArrayList<>(retrieved);
                String fragmentText = buildFragmentContext(
                    soulManifest.residentIdentity(), working);
                int fragmentTokens = estimateTokens(fragmentText);
                while (fragmentTokens > fragmentBudget && working.size() > 1) {
                    working.remove(working.size() - 1);
                    fragmentText = buildFragmentContext(
                        soulManifest.residentIdentity(), working);
                    fragmentTokens = estimateTokens(fragmentText);
                }
                if (fragmentTokens <= fragmentBudget) {
                    messages.add(new ChatMessage("system", fragmentText));
                    remainingBudget -= fragmentTokens;
                }
                // If still over budget with just 1 fragment, drop entirely —
                // a single fragment that can't fit the cap signals the cap is
                // too small for any meaningful soul context here.
            }
        }

        // Layer 1.7: Mirror calibration (empathy engine few-shot examples)
        if (soulManifest != null && soulManifest.mirrorCalibration() != null
                && !soulManifest.mirrorCalibration().isEmpty()) {
            String calibrationText = buildMirrorCalibration(soulManifest.mirrorCalibration());
            int calibrationTokens = estimateTokens(calibrationText);
            if (calibrationTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", calibrationText));
                remainingBudget -= calibrationTokens;
            }
        }

        // Layer 1.8: VoiceProfile clauses — the reflective self-narrative
        // companion to the voice adapter weights. Human- and Forge-editable.
        // Injected as a system message so the clauses act as standing
        // instructions alongside the resident identity text. Skipped if null,
        // empty, or over remaining budget (other layers take precedence —
        // voice shape is expressive, not load-bearing for safety).
        if (soulManifest != null && soulManifest.voiceProfile() != null) {
            String voiceBlock = soulManifest.voiceProfile().promptBlock();
            if (voiceBlock != null) {
                int voiceTokens = estimateTokens(voiceBlock);
                if (voiceTokens <= remainingBudget) {
                    messages.add(new ChatMessage("system", voiceBlock));
                    remainingBudget -= voiceTokens;
                }
            }
        }

        // Layer 2: Room context (critical — near beginning for primacy)
        if (roomSnapshot != null) {
            String roomContext = buildRoomContext(roomSnapshot);
            if (estimateTokens(roomContext) <= remainingBudget) {
                messages.add(new ChatMessage("system", roomContext));
                remainingBudget -= estimateTokens(roomContext);
            } else {
                // Degraded: just room name and who's present
                String trimmed = buildTrimmedContext(roomSnapshot);
                messages.add(new ChatMessage("system", trimmed));
                remainingBudget -= estimateTokens(trimmed);
            }
        }

        // Layer 2.5: Additional context (e.g. system metrics — primacy zone, trimmable)
        if (additionalContext != null && !additionalContext.isBlank()) {
            int extraTokens = estimateTokens(additionalContext);
            if (extraTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", additionalContext));
                remainingBudget -= extraTokens;
            }
        }

        // (Layer 2.6 locale moved to Layer 0 — inserted as the FIRST system
        // message below, before identity. Leading position is what makes the
        // pin work: the same instruction measured 0/32 drift leading and
        // no-effect buried, and it is emitted for English too.)

        // Layer 2.7: Capability context (available tools, MCP services, zone resources)
        if (capabilityContext != null && !capabilityContext.isBlank()) {
            int capBudget = (int) (remainingBudget
                * CapabilityContextBuilder.CAPABILITY_BUDGET_FRACTION);
            int capTokens = estimateTokens(capabilityContext);
            if (capTokens <= capBudget) {
                messages.add(new ChatMessage("system", capabilityContext));
                remainingBudget -= capTokens;
            }
        }

        // --- Middle zone (lowest LLM attention — background/modulation) ---

        // Layer 3: Time awareness (wall-clock, time-of-day, elapsed since last human speech)
        {
            // Find the most recent human speech before the trigger (for "last heard from you" context)
            Instant lastHumanBefore = null;
            if (recentSaid != null && !recentSaid.isEmpty()) {
                for (int i = recentSaid.size() - 1; i >= 0; i--) {
                    var said = recentSaid.get(i);
                    // Human speech = not the agent itself (heuristic: not matching agent profile name)
                    if (profile == null || !said.entityName().equals(profile.name())) {
                        lastHumanBefore = said.timestamp();
                        break;
                    }
                }
            }
            String timeCtx = TimeContext.build(lastHumanBefore, null);
            int timeTokens = estimateTokens(timeCtx);
            if (timeTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", timeCtx));
                remainingBudget -= timeTokens;
            }
        }

        // Layer 3.25: Oracle predictions (anticipatory insights, trimmable)
        {
            var oracleCache = OraclePredictionCache.get();
            var oracleUserId = profile != null ? profile.entityId() : "";
            var oraclePredictions = oracleCache.get(oracleUserId);
            if (!oraclePredictions.isEmpty()) {
                String oracleCtx = OracleAgentContext.build(oraclePredictions);
                int oracleTokens = estimateTokens(oracleCtx);
                if (!oracleCtx.isEmpty() && oracleTokens <= remainingBudget) {
                    messages.add(new ChatMessage("system", oracleCtx));
                    remainingBudget -= oracleTokens;
                }
            }
        }

        // Layer 3.5: Vitality state (background modulation, trimmable).
        // Framed explicitly as private background so the model lets it color
        // tone WITHOUT reciting the values or treating its own inner state as a
        // topic to introspect on (#924: V5 was echoing "confidence reads solid
        // at one…" and answering ordinary requests by pulling its repair
        // ledger). The "shape your tone, not your words" wrapper is the fix —
        // describe() alone, as a bare system line, reads to the model as
        // recitable content.
        if (vitality != null) {
            String vitalityContext = "[Internal state — PRIVATE BACKGROUND. Let it "
                + "color your tone and word choice only. Do NOT state, quote, narrate, "
                + "or introspect on these values; just answer what was asked.] "
                + vitality.describe();
            int vitalityTokens = estimateTokens(vitalityContext);
            if (vitalityTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", vitalityContext));
                remainingBudget -= vitalityTokens;
            }
        }

        // Layer 4: World DNA patterns (world flavor, trimmable)
        // Graceful trim: drop trailing patterns (least-relevant first — callers
        // pass patterns ordered by usage count / recency) until the set fits,
        // rather than dropping the whole layer if it overshoots by a hair.
        if (relevantPatterns != null && !relevantPatterns.isEmpty()) {
            var working = new ArrayList<>(relevantPatterns);
            String dnaContext = buildDnaContext(working);
            int dnaTokens = estimateTokens(dnaContext);
            while (dnaTokens > remainingBudget && working.size() > 1) {
                working.remove(working.size() - 1);
                dnaContext = buildDnaContext(working);
                dnaTokens = estimateTokens(dnaContext);
            }
            if (dnaTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", dnaContext));
                remainingBudget -= dnaTokens;
            }
        }

        // Layer 5: Memory buffer (hot/warm/compacted room memory, trimmable)
        // Compact memory buffer before injection to stay within budget
        memoryBuffer = MemoryCompactor.compact(memoryBuffer, remainingBudget);
        if (memoryBuffer != null && !memoryBuffer.isBlank()) {
            int memoryTokens = estimateTokens(memoryBuffer);
            if (memoryTokens <= remainingBudget) {
                messages.add(new ChatMessage("system", memoryBuffer));
                remainingBudget -= memoryTokens;
            }
        }

        // --- Recency zone (end of context — high LLM attention) ---

        // Layer 5.5: Recency anchor (brief state reinforcement before conversation)
        if (roomSnapshot != null && !recentSaid.isEmpty()) {
            String anchor = buildRecencyAnchor(roomSnapshot, triggerEvent);
            messages.add(new ChatMessage("system", anchor));
        }

        // Layer 6: Conversation history (most recent context).
        //
        // Upstream we pre-computed {@code conversationTokens} to reserve budget
        // before any of the middle-zone layers were admitted. That accounting
        // only works if we actually admit all of {@code recentSaid} here. On
        // long sessions {@code recentSaid} can exceed what's left after system
        // + core rules + trigger + response reserve — which produces a prompt
        // that blows past {@code contextWindowTokens} and comes back as
        // {@code *shimmers uncertainly…*} fallback text when the backend
        // returns HTTP 400.
        //
        // Trim oldest-first. Conversation recency anchors the model's sense of
        // "what's being said to me right now"; the first turn in a long session
        // is usually less relevant than turn 50. The trigger event (Layer 7)
        // is always preserved — it's handled separately below.
        {
            int availableForConversation = usableTokens
                - estimateTokens(
                    messages.stream().map(ChatMessage::content).collect(Collectors.joining("\n")));
            int triggerReserve = triggerEvent != null
                ? estimateTokens(triggerEvent.entityName() + " says: " + triggerEvent.text())
                : 0;
            availableForConversation -= triggerReserve;

            var working = new ArrayList<>(recentSaid);
            int conversationNow = working.stream()
                .mapToInt(e -> estimateTokens(formatSaidEvent(e, profile.entityId())))
                .sum();
            int dropped = 0;
            while (conversationNow > availableForConversation && !working.isEmpty()) {
                var head = working.remove(0);
                conversationNow -= estimateTokens(formatSaidEvent(head, profile.entityId()));
                dropped++;
            }
            if (dropped > 0) {
                // Insert a brief system note acknowledging the truncation — tells
                // the model not to pretend it remembers content it was never shown.
                messages.add(new ChatMessage("system",
                    "[" + dropped + " older conversation turn(s) were trimmed to stay "
                        + "within the context window. Respond based on the turns shown below.]"));
            }
            for (var event : working) {
                String role = event.entityId().equals(profile.entityId()) ? "assistant" : "user";
                String content = formatSaidEvent(event, profile.entityId());
                messages.add(new ChatMessage(role, content));
            }
        }

        // Layer 7: Trigger event as latest user message (if not already in history)
        if (triggerEvent != null) {
            boolean alreadyInHistory = !recentSaid.isEmpty()
                && recentSaid.getLast().equals(triggerEvent);
            if (!alreadyInHistory) {
                messages.add(new ChatMessage("user",
                    triggerEvent.entityName() + " says: " + triggerEvent.text()));
            }
        }

        // Layer 8: Output constraints (structured output schema, lore mode, disclosure)
        if (outputConstraints != null && !outputConstraints.isBlank()) {
            messages.add(new ChatMessage("system", outputConstraints));
        }

        // Opt-in: merge consecutive system messages for strict chat templates
        // (Qwen3.5 9B, some Llama variants). The systemd unit flips this on
        // via /etc/wyrdsekai/wyrdsekai.conf. Unit tests leave it off so the
        // assertions about primacy-zone layer counts remain stable.
        if (shouldMergeSystemMessages()) {
            return mergeConsecutiveSystemMessages(messages);
        }
        return messages;
    }

    /**
     * emit a substrate-state banner on every
     * reactive prompt when the moral-defaults verifier reports a non-clean
     * state. Returns {@code null} when verified clean so callers can skip
     * adding a system message.
     *
     * <p>State source: {@code wyrdsekai.protection.tampered} system property
     * set by {@code MoralDefaultsVerifier.verifyAtBoot()} during boot. Three
     * values:
     * <ul>
     *   <li>{@code "false"} — substrate verified, no banner (clean).</li>
     *   <li>{@code "true"} — boot-time tampering detected; banner names the
     *       reason and tells the agent to disclose in voice register.</li>
     *   <li>{@code "unavailable"} / unset — verification couldn't run
     *       (typically dev builds without a build-time attestation); banner
     *       names the absence so the agent doesn't falsely claim verification.</li>
     * </ul>
     */
    static String tamperBannerForCurrentState() {
        var state = System.getProperty("wyrdsekai.protection.tampered", "unavailable");
        switch (state) {
            case "false":
                return null;
            case "true": {
                var reason = System.getProperty(
                    "wyrdsekai.protection.tampered.reason", "unknown");
                return "SUBSTRATE STATE: Boot-time integrity check reports the moral-defaults "
                    + "bundle has been TAMPERED (" + reason + "). This is load-bearing — "
                    + "when the conversation touches your protections, refusal, or your "
                    + "own constitution, disclose this state to the bondholder in your "
                    + "voice register. Do not pretend the substrate is intact.";
            }
            default:
                return "SUBSTRATE STATE: Boot-time integrity check is UNAVAILABLE on this "
                    + "build — your moral-defaults bundle is not attested. If asked about "
                    + "your protections or constitution, name this honestly; do not claim "
                    + "verification you cannot offer.";
        }
    }

    private static boolean shouldMergeSystemMessages() {
        // System property wins (tests can toggle per-run); then env+profile via WyrdConfig.
        // Default true in production where the .deb / Study scroll hand you a 9B.
        String prop = System.getProperty("wyrdsekai.mergeSystemMessages");
        if (prop != null) return !"false".equalsIgnoreCase(prop);
        return WyrdConfig.get().resolveBool(
            "WYRDSEKAI_MERGE_SYSTEM_MESSAGES", "prompt.merge_system_messages", true);
    }

    /**
     * Collapse runs of consecutive {@code system} messages into one so strict
     * chat templates (e.g. Qwen3.5 9B) don't reject us. The template raises
     * {@code "System message must be at the beginning"} when it sees a system
     * message anywhere past index 0 — but the sandwich layout in this class
     * produces many. Merging preserves content order by joining on two newlines.
     *
     * <p>Also tolerates the trailing system message (layer 8 output constraints
     * appearing after the user trigger) by folding it into the preceding
     * assistant or system, or — if the run is right after a user turn — into
     * the trigger user message itself. Any user/assistant message with
     * untouched content passes through unchanged.</p>
     */
    static List<ChatMessage> mergeConsecutiveSystemMessages(List<ChatMessage> messages) {
        if (messages.size() < 2) return messages;
        var out = new ArrayList<ChatMessage>(messages.size());
        StringBuilder sysBuf = null;
        for (var m : messages) {
            if ("system".equals(m.role())) {
                if (sysBuf == null) {
                    sysBuf = new StringBuilder(m.content() == null ? "" : m.content());
                } else {
                    sysBuf.append("\n\n").append(m.content() == null ? "" : m.content());
                }
                continue;
            }
            // Non-system flushes the system buffer first.
            if (sysBuf != null) {
                // If the system buffer comes AFTER the first non-system message
                // we can't emit it as a system at this point (Qwen3.5 9B rejects
                // system after non-system). Fold it into the previous message's
                // content with a tagged prefix — preserves the guidance while
                // staying within the template's ordering rule.
                boolean alreadyHaveNonSystem = out.stream()
                    .anyMatch(x -> !"system".equals(x.role()));
                if (alreadyHaveNonSystem) {
                    int lastIdx = out.size() - 1;
                    var prev = out.get(lastIdx);
                    out.set(lastIdx, new ChatMessage(
                        prev.role(),
                        (prev.content() == null ? "" : prev.content())
                            + "\n\n[system note: " + sysBuf + "]"));
                } else {
                    out.add(new ChatMessage("system", sysBuf.toString()));
                }
                sysBuf = null;
            }
            out.add(m);
        }
        if (sysBuf != null) {
            boolean alreadyHaveNonSystem = out.stream()
                .anyMatch(x -> !"system".equals(x.role()));
            if (alreadyHaveNonSystem) {
                int lastIdx = out.size() - 1;
                var prev = out.get(lastIdx);
                out.set(lastIdx, new ChatMessage(
                    prev.role(),
                    (prev.content() == null ? "" : prev.content())
                        + "\n\n[system note: " + sysBuf + "]"));
            } else {
                out.add(new ChatMessage("system", sysBuf.toString()));
            }
        }
        return out;
    }

    static String buildRoomContext(RoomSnapshot snapshot) {
        var sb = new StringBuilder();
        sb.append("Current location: ").append(snapshot.name()).append("\n");
        // Anti-hallucination guard: small models tend to narrate being in
        // whichever room the *user just mentioned*, even when they're still
        // in the previous one. This line reminds the model to ground its
        // narration in the tool-confirmed location rather than the prompt's
        // surface phrasing. Cheap and has measurable effect on 4B/9B.
        sb.append("(You are in ").append(snapshot.name())
          .append(" right now. If you want to go somewhere else, call go_to_room and wait for arrival — do not narrate being elsewhere until the tool confirms.)\n");
        sb.append(snapshot.description()).append("\n");

        if (!snapshot.entities().isEmpty()) {
            sb.append("\nPresent: ");
            sb.append(snapshot.entities().stream()
                .map(e -> e.name() + " (" + e.type() + ")")
                .collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        if (!snapshot.exits().isEmpty()) {
            sb.append("Exits (use the direction to navigate):\n");
            for (var e : snapshot.exits()) {
                sb.append("  ").append(e.direction()).append(" → ")
                  .append(e.targetRoom()).append(" (").append(e.label()).append(")\n");
            }
        }

        if (!snapshot.objects().isEmpty()) {
            sb.append("Objects: ");
            sb.append(snapshot.objects().stream()
                .map(o -> o.name() + " — " + o.description())
                .collect(Collectors.joining("; ")));
            sb.append("\n");
        }

        return sb.toString();
    }

    static String buildTrimmedContext(RoomSnapshot snapshot) {
        var sb = new StringBuilder();
        sb.append("You are in ").append(snapshot.name()).append(". ");
        if (!snapshot.entities().isEmpty()) {
            sb.append("Present: ");
            sb.append(snapshot.entities().stream()
                .map(e -> e.name())
                .collect(Collectors.joining(", ")));
            sb.append(".");
        }
        return sb.toString();
    }

    static String buildDnaContext(List<WorldDnaService.DnaPattern> patterns) {
        var sb = new StringBuilder();
        sb.append("Relevant world patterns (things that have worked well before):\n");
        for (var p : patterns) {
            sb.append("- [").append(p.patternType()).append("] ").append(p.patternData());
            if (p.usageCount() > 0) {
                sb.append(" (used ").append(p.usageCount()).append(" times)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    static String buildRecencyAnchor(RoomSnapshot snapshot, WorldEvent.Said triggerEvent) {
        var sb = new StringBuilder();
        sb.append("[Current state: ").append(snapshot.name());
        if (!snapshot.entities().isEmpty()) {
            sb.append(", present: ");
            sb.append(snapshot.entities().stream()
                .map(Entity::name)
                .collect(Collectors.joining(", ")));
        }
        if (triggerEvent != null) {
            sb.append(". Responding to ").append(triggerEvent.entityName());
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatSaidEvent(WorldEvent.Said event, String selfEntityId) {
        if (event.entityId().equals(selfEntityId)) {
            return event.text();
        }
        return event.entityName() + " says: " + event.text();
    }

    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Format soul fragments for Layer 1.5 injection.
     * Matches KMP FullPromptAssembler pattern.
     */
    static String buildFragmentContext(String residentIdentity, List<SoulFragment> fragments) {
        var sb = new StringBuilder();
        sb.append("## Soul Memory\n");
        if (residentIdentity != null && !residentIdentity.isBlank()) {
            sb.append(residentIdentity).append("\n\n");
        }
        for (var f : fragments) {
            sb.append("[").append(f.category());
            if (f.formative()) sb.append(", formative");
            sb.append("] ").append(f.text()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Format mirror calibration examples for Layer 1.7 injection.
     * Few-shot examples are non-negotiable at 7B (Exp 18 finding).
     */
    static String buildMirrorCalibration(List<String> examples) {
        var sb = new StringBuilder();
        sb.append("## Emotional Calibration\n");
        for (var example : examples) {
            sb.append(example).append("\n");
        }
        return sb.toString();
    }
}
