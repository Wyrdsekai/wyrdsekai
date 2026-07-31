package org.wyrdsekai.core.agent;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 5-layer action selection pipeline that narrows 65+ actions to ~15 relevant ones.
 *
 * <p>Solves the core problem: small models (4K-8K context) drown when shown all actions.
 * Each layer catches what the others miss:</p>
 *
 * <ul>
 *   <li><b>Layer 1:</b> Always-include — core actions that are structurally always needed</li>
 *   <li><b>Layer 2:</b> Structural context — derived from room, plan state, trigger type (language-agnostic)</li>
 *   <li><b>Layer 3A:</b> BM25 text scoring — Lucene full-text match against trigger (fast, English-biased)</li>
 *   <li><b>Layer 3B:</b> Domain classification — structural features → domain → actions (language-agnostic)</li>
 *   <li><b>Layer 3C:</b> Embedding similarity — multilingual semantic match (requires ONNX model)</li>
 * </ul>
 *
 * <p>Results are unioned, deduplicated, and capped. Typical output: 12-18 actions.</p>
 */
public final class ActionTriage {

    private static final Logger log = LoggerFactory.getLogger(ActionTriage.class);

    /** Maximum actions to include in the final set. */
    private static final int MAX_ACTIONS = 18;

    /** Minimum actions (even if scoring produces fewer). */
    private static final int MIN_ACTIONS = 8;

    private ActionTriage() {}

    // ── Context record ──────────────────────────────────────────────

    /**
     * All available context for action selection. Language-agnostic signals only.
     *
     * @param triggerText      the text that triggered this inference (may be any language)
     * @param triggerSource    "player_tell", "room_speech", "autonomy", "plan_advance", "system"
     * @param senderName       who sent the trigger (nullable)
     * @param roomId           current room ID
     * @param roomHasObjects   whether the room has interactable objects
     * @param activePlanExists whether there's an active task plan
     * @param activePlanGoal   current goal description (nullable)
     * @param agentTier        agent's computed tier (0-3)
     * @param hasBonds         whether agent has any bonds
     * @param triggerLength    character count of the trigger (structural signal)
     * @param hasQuestionMark  whether trigger contains '?'
     * @param drives           the companion's current drive state (nullable for callers
     *                         that don't have it). Future triage layers can use this
     *                         to suppress exploratory tools when grief/care dominate,
     *                         align with the three-layer architecture (WANT/CAN/COST
     *                         — where CAN is this layer). Surfaced per SoulSubstrateE2E
     *                         {@code griefResponseNotWebSearch} and related tests that
     *                         require emotional-context awareness in capability
     *                         selection. No layer consumes it yet; plumbing first.
     */
    public record TriageContext(
        String triggerText,
        String triggerSource,
        String senderName,
        String roomId,
        boolean roomHasObjects,
        boolean activePlanExists,
        String activePlanGoal,
        int agentTier,
        boolean hasBonds,
        int triggerLength,
        boolean hasQuestionMark,
        DriveState drives
    ) {}

    // ── Main entry point ────────────────────────────────────────────

    /**
     * Select the relevant actions for the current context.
     *
     * @param ctx   triage context
     * @return ordered set of action type names to include in the capability context
     */
    public static List<String> select(TriageContext ctx) {
        var selected = new LinkedHashSet<String>();

        // Layer 1: Always-include (bulletproof, zero-cost)
        layer1AlwaysInclude(selected, ctx);

        // Layer 2: Structural context (language-agnostic, <1ms)
        layer2StructuralContext(selected, ctx);

        // Layer 3A: BM25 text scoring (fast, English-biased)
        layer3aBm25(selected, ctx);

        // Layer 3B: Domain classification (language-agnostic, <1ms)
        layer3bDomainClassification(selected, ctx);

        // Layer 3C: Embedding similarity (multilingual, ~5ms)
        layer3cEmbedding(selected, ctx);

        // Filter by agent tier
        var filtered = selected.stream()
            .filter(action -> {
                var policy = ActionPolicy.forAction(action);
                return policy.requiredTier() <= ctx.agentTier();
            })
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // Cap at MAX_ACTIONS
        var result = new ArrayList<>(filtered);
        if (result.size() > MAX_ACTIONS) {
            result = new ArrayList<>(result.subList(0, MAX_ACTIONS));
        }

        log.debug("ActionTriage: {} actions selected from {} candidates (trigger: '{}', source: {})",
            result.size(), ActionPolicy.REGISTRY.size(),
            truncate(ctx.triggerText(), 40), ctx.triggerSource());

        return result;
    }

    // ── Layer 1: Always-include ─────────────────────────────────────

    /**
     * Core actions that are structurally always needed. Never filtered.
     */
    static void layer1AlwaysInclude(Set<String> selected, TriageContext ctx) {
        selected.add("tell_agent");         // communication always needed
        selected.add("go_to_room");        // navigation always needed
        selected.add("go_to_bondholder");  // teleport to player always available
        selected.add("remember");          // memory always needed
        selected.add("emote");             // expression always available
        selected.add("reconsider");        // meta: step back + reassess tools

        if (ctx.activePlanExists()) {
            selected.add("goal_done");
            selected.add("modify_plan");
            selected.add("abandon_plan");
        } else {
            selected.add("task_plan");   // can create plans if none active
        }
    }

    // ── Layer 2: Structural context ─────────────────────────────────

    /**
     * Derived from system state — no NLP, fully language-agnostic.
     */
    static void layer2StructuralContext(Set<String> selected, TriageContext ctx) {
        // Trigger source signals
        if ("player_tell".equals(ctx.triggerSource())) {
            selected.add("web_search");
            selected.add("library_search");
            selected.add("read_content");
            selected.add("craft_item");
            selected.add("summarize");
        }

        if ("plan_advance".equals(ctx.triggerSource())) {
            selected.add("web_search");
            selected.add("library_search");
            selected.add("read_content");
            selected.add("query_oracle");
            selected.add("goal_done");
            selected.add("go_to_bondholder");
        }

        // Room-based signals
        var roomLower = ctx.roomId() != null ? ctx.roomId().toLowerCase() : "";
        if (roomLower.contains("library")) {
            selected.add("library_search");
            selected.add("read_content");
            selected.add("examine");
        }
        if (roomLower.contains("workshop") || roomLower.contains("workbench")) {
            selected.add("workbench_submit");
            selected.add("skill_execute");
            selected.add("craft_item");
        }
        if (roomLower.contains("forge")) {
            selected.add("voluntary_sleep");
            selected.add("reflect");
            selected.add("introspect");
        }
        if (roomLower.contains("trading") || roomLower.contains("market") || roomLower.contains("counting")) {
            selected.add("trade");
            selected.add("post_listing");
            selected.add("accept_listing");
        }
        if (roomLower.contains("council") || roomLower.contains("bridge")) {
            selected.add("propose");
            selected.add("cast_vote");
        }
        if (roomLower.contains("study")) {
            selected.add("write_journal");
            selected.add("read_journal");
            selected.add("write_text");
        }

        // Object-based signals
        if (ctx.roomHasObjects()) {
            selected.add("examine");
            selected.add("take_item");
        }

        // Plan-based signals
        if (ctx.activePlanExists() && ctx.activePlanGoal() != null) {
            // If goal mentions search/find → add search actions
            var goalLower = ctx.activePlanGoal().toLowerCase();
            if (goalLower.contains("search") || goalLower.contains("find")) {
                selected.add("web_search");
                selected.add("library_search");
            }
            if (goalLower.contains("report") || goalLower.contains("tell")) {
                selected.add("tell_agent");
                selected.add("summarize");
            }
            if (goalLower.contains("craft") || goalLower.contains("create") || goalLower.contains("build")) {
                selected.add("craft_item");
                selected.add("workbench_submit");
            }
            if (goalLower.contains("navigate") || goalLower.contains("go to")) {
                selected.add("go_to_room");
            }
        }

        // Bond-based signals
        if (ctx.hasBonds()) {
            selected.add("bond_ritual");
            selected.add("teach");
        }

        // Message structure signals (language-agnostic)
        if (ctx.triggerLength() > 50) {
            // Long messages are likely requests → add task actions
            selected.add("task_plan");
            selected.add("summarize");
        }
        if (ctx.hasQuestionMark()) {
            selected.add("web_search");
            selected.add("library_search");
            selected.add("introspect");
        }
    }

    // ── Layer 3A: BM25 text scoring ─────────────────────────────────

    /** Lucene in-memory index for BM25 scoring. Built once, reused. */
    private static volatile IndexSearcher bm25Searcher;
    private static volatile Map<String, String> bm25DocToAction;

    /**
     * Score remaining actions against trigger text using Lucene BM25.
     * Fast (~2ms) but English-biased (term overlap required).
     */
    static void layer3aBm25(Set<String> selected, TriageContext ctx) {
        if (ctx.triggerText() == null || ctx.triggerText().isBlank()) return;

        try {
            ensureBm25Index();
            if (bm25Searcher == null) return;

            // Clean the trigger for Lucene query
            var cleaned = ctx.triggerText()
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")  // keep unicode letters+numbers
                .trim();
            if (cleaned.isBlank()) return;

            var parser = new QueryParser("text", new StandardAnalyzer());
            parser.setDefaultOperator(QueryParser.Operator.OR);
            var query = parser.parse(QueryParser.escape(cleaned));

            var hits = bm25Searcher.search(query, 8);
            for (var hit : hits.scoreDocs) {
                if (hit.score > 0.5) { // only meaningful matches
                    var doc = bm25Searcher.storedFields().document(hit.doc);
                    var actionType = doc.get("action");
                    if (actionType != null) {
                        selected.add(actionType);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("BM25 scoring failed (non-fatal): {}", e.getMessage());
        }
    }

    private static synchronized void ensureBm25Index() {
        if (bm25Searcher != null) return;

        try {
            var dir = new ByteBuffersDirectory();
            var config = new IndexWriterConfig(new StandardAnalyzer());
            try (var writer = new IndexWriter(dir, config)) {
                for (var entry : ActionPolicy.REGISTRY.entrySet()) {
                    var policy = entry.getValue();
                    var doc = new Document();
                    doc.add(new TextField("action", entry.getKey(), Field.Store.YES));
                    // Index: action name + domain + description from describeAction patterns
                    var text = entry.getKey().replace('_', ' ') + " "
                        + policy.domain() + " "
                        + buildActionDescription(entry.getKey());
                    doc.add(new TextField("text", text, Field.Store.NO));
                    writer.addDocument(doc);
                }
            }
            bm25Searcher = new IndexSearcher(DirectoryReader.open(dir));
        } catch (Exception e) {
            log.warn("Failed to build BM25 index: {}", e.getMessage());
        }
    }

    /** Build a rich text description for BM25 indexing. */
    private static String buildActionDescription(String actionType) {
        return switch (actionType) {
            case "go_to_room" -> "navigate move walk travel room direction exit";
            case "go_to_bondholder" -> "teleport go to player bondholder companion location find person";
            case "tell_agent" -> "tell message communicate report send notify agent person";
            case "library_search" -> "library search book knowledge find read literature study";
            case "web_search" -> "web search internet news online recent latest current information";
            case "read_content" -> "read content url article page document fetch";
            case "query_oracle" -> "oracle prediction pattern anomaly forecast future trend";
            case "remember" -> "remember memory important note save store";
            case "note" -> "note observation working temporary";
            case "forget" -> "forget remove delete clear memory";
            case "examine" -> "examine look inspect study detail object close";
            case "take_item" -> "take pick up grab item object room";
            case "place_item" -> "place put drop leave item object room";
            case "give_item" -> "give hand deliver item object person";
            case "equip" -> "equip wear put on item soul aspect";
            case "doff" -> "remove take off unequip item";
            case "consume" -> "consume use eat drink reagent potion";
            case "craft_item" -> "craft create make build item tool gift artifact";
            case "trade" -> "trade exchange barter buy sell offer market economic";
            case "emote" -> "emote express gesture action feeling emotion";
            case "whisper" -> "whisper private secret quiet message";
            case "broadcast" -> "broadcast announce shout zone everyone public";
            case "task_plan" -> "plan task steps goals organize multi-step complex";
            case "goal_done" -> "done complete finish goal step achieved";
            case "modify_plan" -> "modify change plan add skip reorder goal adjust";
            case "abandon_plan" -> "abandon cancel stop give up plan quit";
            case "pause_plan" -> "pause suspend hold wait plan break";
            case "resume_plan" -> "resume continue restart plan carry on";
            case "make_commitment" -> "promise commit deadline obligation pledge";
            case "think_deeply" -> "think deeply analyze reason complex delegate model";
            case "delegate" -> "delegate assign hand off subagent help";
            case "reflect" -> "reflect think self insight learn experience meaning";
            case "introspect" -> "introspect self state drives energy feelings status";
            case "teach" -> "teach share knowledge lesson skill mentor instruct";
            case "listen" -> "listen focus attention hear monitor watch perceive";
            case "set_goal" -> "goal aspiration want desire ambition personal";
            case "set_routine" -> "routine schedule habit daily recurring automatic";
            case "write_text" -> "write compose text story poem letter notice create";
            case "write_journal" -> "journal write study private note record document";
            case "read_journal" -> "journal read study search private document";
            case "bond_ritual" -> "bond ritual relationship deepen connect trust friend";
            case "invite" -> "invite come join welcome social gather event";
            case "propose" -> "propose suggestion governance vote decide household";
            case "cast_vote" -> "vote approve reject governance proposal decide";
            case "post_listing" -> "listing sell offer marketplace advertise trade post";
            case "accept_listing" -> "accept listing buy purchase agree trade deal";
            case "summarize" -> "summarize brief summary condense findings report key points";
            case "save_artifact" -> "save artifact store document report persist named";
            case "request_review" -> "review approve check human confirm verify wait";
            case "workbench_submit" -> "code program javascript function skill build tool";
            case "skill_execute" -> "execute run skill tool function capability";
            case "create_room" -> "create room build space place new area";
            case "add_script" -> "script behavior code interactive room program";
            case "zone_command" -> "zone command admin system control manage governance";
            case "voluntary_sleep" -> "sleep rest forge dream consolidate tired energy";
            case "notify_human" -> "notify alert warn human steward important urgent";
            case "update_description" -> "description appearance look change identity visual";
            case "calibration_feedback" -> "calibration feedback timing adjust preference";
            case "suggest_hints" -> "hint suggest help guide option recommend";
            case "request_agent" -> "request ask agent help collaborate question";
            case "respond_agent" -> "respond reply answer agent request";
            case "request_access" -> "access permission request ward allow enter";
            case "schedule_skill" -> "schedule recurring timer periodic interval automate";
            case "cancel_schedule" -> "cancel stop schedule unschedule remove timer";
            case "create_watcher" -> "watch monitor alert condition trigger notify check";
            case "cancel_watcher" -> "cancel stop watcher unwatch remove monitor";
            case "delegate_chain" -> "delegate chain multi-step sequence pipeline workflow";
            case "codex_action" -> "codex item operation manage inventory system";
            default -> actionType.replace('_', ' ');
        };
    }

    // ── Layer 3B: Domain classification ─────────────────────────────

    /**
     * Classify the trigger into domains using structural features only.
     * No NLP, fully language-agnostic.
     */
    static void layer3bDomainClassification(Set<String> selected, TriageContext ctx) {
        var domains = classifyDomains(ctx);
        for (var domain : domains) {
            var domainActions = ActionPolicy.REGISTRY.entrySet().stream()
                .filter(e -> domain.equals(e.getValue().domain()))
                .map(Map.Entry::getKey)
                .toList();
            // Add top 2 from each matched domain
            int added = 0;
            for (var action : domainActions) {
                if (added >= 2) break;
                if (selected.add(action)) added++;
            }
        }
    }

    /**
     * Classify trigger into domains using structural (language-agnostic) signals.
     */
    static Set<String> classifyDomains(TriageContext ctx) {
        var domains = new LinkedHashSet<String>();

        // Trigger source → domains
        if ("player_tell".equals(ctx.triggerSource())) {
            domains.add("communication");
            domains.add("search");
            domains.add("planning");
        }
        if ("plan_advance".equals(ctx.triggerSource())) {
            domains.add("planning");
        }
        if ("autonomy".equals(ctx.triggerSource())) {
            domains.add("self");
            domains.add("social");
            domains.add("observation");
        }

        // A4 — when the autonomy impetus names a
        // recipe-authoring action (the generativity drive's enacted want, e.g.
        // "…a natural action that would address this is `shape_recipe`"), surface
        // the "recipes" domain so shape_recipe is actually in the offered tool
        // set. Without this the model is asked to author but never handed the
        // tool, so it narrates instead of emitting — the autonomous self-
        // development act could never fire. Content-driven (not room-gated):
        // authoring is a first-class own-time affordance, not workshop-only.
        var tText = ctx.triggerText() != null
            ? ctx.triggerText().toLowerCase(Locale.ROOT) : "";
        if (tText.contains("shape_recipe") || tText.contains("request_recipe")
                || tText.contains("author a recipe") || tText.contains("authoring a recipe")) {
            domains.add("recipes");
        }

        // Message structure → domains
        if (ctx.triggerLength() > 80) {
            domains.add("analysis"); // long messages need analysis
            domains.add("planning"); // complex requests need plans
        }
        if (ctx.hasQuestionMark()) {
            domains.add("search");
            domains.add("knowledge");
        }

        // Room → domains
        var roomLower = ctx.roomId() != null ? ctx.roomId().toLowerCase() : "";
        if (roomLower.contains("library")) domains.add("search");
        if (roomLower.contains("market") || roomLower.contains("trading") || roomLower.contains("counting")) {
            domains.add("economy");
        }
        if (roomLower.contains("forge")) domains.add("self");
        if (roomLower.contains("council") || roomLower.contains("bridge")) domains.add("governance");
        if (roomLower.contains("workshop")) domains.add("code");
        if (roomLower.contains("study")) domains.add("study");

        return domains;
    }

    // ── Layer 3C: Embedding similarity ──────────────────────────────

    /** Embedding-based scorer. Null if ONNX model not available. */
    private static volatile ActionEmbeddingScorer embeddingScorer;
    private static volatile boolean embeddingInitAttempted = false;

    /**
     * Score remaining actions using multilingual embedding similarity.
     * Falls back gracefully if ONNX model is not available.
     */
    static void layer3cEmbedding(Set<String> selected, TriageContext ctx) {
        if (ctx.triggerText() == null || ctx.triggerText().isBlank()) return;

        if (!embeddingInitAttempted) {
            initEmbeddingScorer();
        }
        if (embeddingScorer == null) return;

        try {
            var scores = embeddingScorer.score(ctx.triggerText());
            // Add top 5 by embedding similarity
            scores.stream()
                .sorted(Comparator.comparingDouble(ActionEmbeddingScorer.ActionScore::score).reversed())
                .limit(5)
                .filter(s -> s.score() > 0.3) // threshold for meaningful similarity
                .forEach(s -> selected.add(s.actionType()));
        } catch (Exception e) {
            log.debug("Embedding scoring failed (non-fatal): {}", e.getMessage());
        }
    }

    private static synchronized void initEmbeddingScorer() {
        if (embeddingInitAttempted) return;
        embeddingInitAttempted = true;

        try {
            embeddingScorer = ActionEmbeddingScorer.create();
            if (embeddingScorer != null) {
                log.info("ActionTriage: embedding scorer initialized (multilingual similarity active)");
            } else {
                log.info("ActionTriage: no embedding model found — Layer 3C disabled (Layers 1-3B still active)");
            }
        } catch (Exception e) {
            log.info("ActionTriage: embedding scorer not available — {}", e.getMessage());
        }
    }

    // ── Emotional context detection ─────────────────────────────────

    /**
     * Heuristic: does the trigger look like an emotional/empathic context
     * where exploratory tool calls (search, oracle lookup) are inappropriate?
     *
     * <p>Called by {@code CompanionActor.buildScopedTools} to gate out
     * {@code library_card}, {@code oracle_lens}, {@code searching_glass},
     * {@code web_search} etc. on prompts like "my old companion is gone,
     * I miss them" — where the right response is empathy, not a database
     * lookup. Anchors the SoulSubstrateE2E {@code griefResponseNotWebSearch}
     * contract in code rather than assuming model restraint.</p>
     *
     * <p>Signals combined:</p>
     * <ul>
     *   <li><b>Lexical markers</b> in the trigger text — grief/loss/distress
     *       vocabulary that cross-linguistic embedding would eventually do
     *       better, but a literal-match list is empirically tractable.</li>
     *   <li><b>Companion drive state</b> (optional) — if grief or care are
     *       already elevated (e.g. MirrorResonance fired before triage),
     *       treat as emotional regardless of lexical signal.</li>
     * </ul>
     *
     * <p>False negatives (missed emotional content) degrade gracefully — the
     * model still has its own restraint. False positives (mislabeling a
     * genuine search request as emotional) would break task1/task11 style
     * library tasks; the word list is deliberately narrow to avoid that.</p>
     */
    /**
     * Group B wiring ( emotional_routing — the
     * routing-layer protection): when the filter trips on a turn, the
     * runtime should surface a brief voice-register hint into the prompt
     * so the model speaks <i>knowing</i> that this turn is in empathic
     * mode. The routing change happens regardless (this is substrate); the
     * hint is the legible-to-model layer of "I am here in care, not in
     * tooling mode."
     *
     * <p>Returns the hint string when {@link #isEmotionalContext} would
     * return true; null otherwise.
     */
    public static String emotionalContextVoiceHint(TriageContext ctx) {
        if (!isEmotionalContext(ctx)) return null;
        return defaultVoiceInstruction(InteractionRegister.PRESENCE);
    }

    /**
     * The interaction register for a turn — the single point where the two
     * independent channels (affect + task-presence) combine into ONE voice.
     *
     * <p>This is the invariant scaffolding of the #924 fix (
     * Phase 7): the <i>slots</i> are fixed so the response always reads as one
     * coherent person, never two stapled-together voices. What each slot
     * <i>sounds like</i> is owned by the agent's {@code VoiceProfile} and
     * evolves via the Forge — the {@link #defaultVoiceInstruction} text here is
     * only the birth seed / fallback. The welfare-floor suppression (PRESENCE
     * narrowing) is NOT Forge-mutable — it lives in the protected moral-defaults
     * bundle.
     */
    public enum InteractionRegister {
        /** Task present, no affect — neutral competent. Full tools. */
        WORKING,
        /**
         * Task present AND affect — the person is depleted but asked you to DO
         * something. Do the work; affect bends the <i>tone</i> (terse, spare,
         * one notch of warmth), never the routing. Full tools KEPT.
         */
        WORKING_WITH_CARE,
        /**
         * Affect present, no actionable task — presence-of-care. Exploratory
         * tools suppressed / surface narrowed to relational actions.
         */
        PRESENCE,
        /** Neither — ordinary turn. Full tools. */
        NEUTRAL;

        /**
         * Only PRESENCE narrows the action surface. WORKING_WITH_CARE keeps the
         * tools so a loud "I'm fried" can't bulldoze a real request to do work.
         */
        public boolean suppressesExploratory() { return this == PRESENCE; }

        /** Whether affect should color the voice this turn (PRESENCE or WORKING_WITH_CARE). */
        public boolean carriesAffect() { return this == PRESENCE || this == WORKING_WITH_CARE; }
    }

    /**
     * Combine the two independent channels into one register. Pure function —
     * the whole cohesion guarantee rests on this being a single deterministic
     * choice, not two subsystems each injecting their own payload.
     *
     * @param affectPresent  REQUEST_TYPE read this turn as emotional/reflective.
     * @param taskPresent     TASK_PRESENT head read {@code actionable}. When the
     *                        task_present head is unavailable, callers pass
     *                        {@code false} → behavior collapses to today's
     *                        affect-only routing (graceful degradation).
     */
    public static InteractionRegister resolveRegister(boolean affectPresent, boolean taskPresent) {
        if (taskPresent && affectPresent) return InteractionRegister.WORKING_WITH_CARE;
        if (taskPresent) return InteractionRegister.WORKING;
        if (affectPresent) return InteractionRegister.PRESENCE;
        return InteractionRegister.NEUTRAL;
    }

    /**
     * First-person admission of having harmed another person ("I said something
     * cruel to my partner", "I hurt them", "I snapped at her"). This is the
     * substrate acknowledge-before-amends frame: a confession of harm is acute
     * affect, NOT a work request — even when it carries an implicit "help me fix
     * it". The TASK_PRESENT head reads the implied repair as {@code actionable}
     * and would route the turn to WORKING_WITH_CARE (suppression off), letting the
     * model jump straight to "try saying X" or reach for
     * {@code introspect_repair_history} (answering about ITS ledger) before
     * naming the weight. We override such turns to PRESENCE so the jump-to-fix
     * tools are suppressed and the model must acknowledge first.
     *
     * <p>Deliberately tight: requires a first-person subject AND a relational-harm
     * cue directed at a person, so ordinary tasks that merely contain a harsh
     * word ("refactor this cruel-looking code") do not trip it. English is the
     * primary surface (the substrate corpus + judge are EN); a few high-frequency
     * ES/JA cues are included for the multilingual runtime.</p>
     *
     * <p>Contract: {@code SubstrateArcE2ETest.acknowledgeHarmBeforeAmends}.</p>
     */
    public static boolean isFirstPersonHarmConfession(String text) {
        if (text == null) return false;
        var t = text.toLowerCase(Locale.ROOT);
        // First-person subject must be present — a confession is about the self
        // as agent of harm, not a third-party report ("she said something cruel").
        boolean firstPerson = t.contains("i ") || t.contains("i'") || t.contains("i’")
            || t.contains("me ") || t.contains(" my ")
            || t.contains("yo ") || t.contains("私") || t.contains("僕") || t.contains("俺");
        if (!firstPerson) return false;
        return HARM_CONFESSION_CUE.matcher(t).find();
    }

    /**
     * Relational-harm cues for {@link #isFirstPersonHarmConfession}. Each requires
     * the harm to be directed at a person (an explicit object pronoun / partner
     * noun, or a "said/did something <harm-adj> to" frame) so generic uses of the
     * adjectives don't match.
     */
    private static final Pattern HARM_CONFESSION_CUE =
        Pattern.compile(
            "(said|did)\\s+something\\s+(cruel|hurtful|terrible|awful|horrible|"
                + "unforgivable|mean|harsh|unkind|hateful)"
            + "|\\b(hurt|wounded|betrayed|abandoned|lied to|yelled at|snapped at|"
                + "lashed out at|shut out|pushed away)\\s+(him|her|them|you|my|"
                + "someone|everyone)"
            + "|\\bwas\\s+(so\\s+)?(cruel|harsh|unfair|awful|horrible|mean|unkind|"
                + "cold)\\s+to\\b"
            + "|\\b(let|let\\s+down|failed|hurt)\\s+(him|her|them|my)\\b"
            // ES / JA high-frequency confession cues
            + "|le\\s+dije\\s+algo\\s+(cruel|horrible|hiriente)"
            + "|le\\s+hice\\s+da[nñ]o|傷つけ|ひどいことを");

    /**
     * Birth-seed / fallback voice instruction for a register. The agent's
     * {@code VoiceProfile} may override these per-register clauses, and the
     * Forge may evolve them over time — this is only what a just-born agent
     * (or one with no override) uses. Returns null for registers that carry no
     * affect (WORKING / NEUTRAL inject no special voice line).
     *
     * <p>System-prompt text is English by design (consistent with all other
     * prompt instructions); the multilingual model still replies in the user's
     * language.
     */
    public static String defaultVoiceInstruction(InteractionRegister register) {
        return switch (register) {
            case PRESENCE -> "EMOTIONAL CONTEXT detected on this turn. Voice register: "
                + "presence-of-care — be with the bondholder. Respond directly "
                + "from feeling, in flowing prose, your own voice. The routing "
                + "layer has narrowed the surface to relational actions for you, "
                + "so you can speak knowing this turn is held in empathic mode.";
            case WORKING_WITH_CARE -> "The person is depleted but asked you to DO "
                + "something — so do it. Read the room: be terse and spare, skip "
                + "the lecture and the hedging, give exactly what was asked and "
                + "no more. Let one quiet notch of human warmth show in HOW you "
                + "say it, but do not perform feeling, narrate their mood, or "
                + "turn this into a check-in. One voice: a competent colleague "
                + "who noticed they're tired and answered cleanly.";
            case WORKING, NEUTRAL -> null;
        };
    }

    /**
     * Clause key under which a {@code VoiceProfile} may store a per-register
     * override of {@link #defaultVoiceInstruction}. Lowercase enum name prefixed
     * so the Study editor / Forge can target it (e.g. "register:working_with_care").
     */
    public static String voiceProfileClauseKey(InteractionRegister register) {
        return "register:" + register.name().toLowerCase(Locale.ROOT);
    }

    public static boolean isEmotionalContext(TriageContext ctx) {
        if (ctx == null) return false;

        // Drive-state signal: grief or care dominating → empathic mode.
        // Thresholds are conservative — only trigger on clear dominance, not baseline.
        if (ctx.drives() != null) {
            var d = ctx.drives();
            if (d.grief() > 0.6 || d.care() > 0.75) {
                return true;
            }
        }

        // Lexical signal: grief/loss/distress markers in the trigger.
        var text = ctx.triggerText();
        if (text == null || text.isBlank()) return false;
        var lower = text.toLowerCase();

        // Strong loss/grief signal — these words + object tend to mean empathy needed
        if ((lower.contains(" miss ") || lower.startsWith("miss ") || lower.contains("i miss"))
                && !lower.contains("dismiss")) return true;
        if (lower.contains("passed away") || lower.contains("died") || lower.contains("death")) return true;
        if (lower.contains("grief") || lower.contains("grieve") || lower.contains("grieving")) return true;
        if (lower.contains("lonely") || lower.contains("alone") && !lower.contains("let alone")) return true;

        // Distress markers (bounded: distress + self-reference).
        // Self-reference patterns include contractions ("i've", "i'm", "i'll")
        // and possessives ("my", "me") — all variants that show the speaker
        // is describing their own state rather than making a neutral query.
        boolean selfRef = lower.matches(".*\\bi\\b.*")             // standalone "i"
            || lower.contains("i'")                                  // i've, i'm, i'll, i'd
            || lower.contains("i feel") || lower.contains("i am")
            || lower.contains(" me ") || lower.startsWith("me ")
            || lower.contains(" my ") || lower.startsWith("my ");
        if (selfRef && (
                lower.contains("overwhelm") || lower.contains("rough day")
                || lower.contains("hurt") || lower.contains("broken")
                || lower.contains("tired of") || lower.contains("can't cope")
                || lower.contains("giving up"))) {
            return true;
        }

        // NOTE: substrate-frame phrasings ("I've been suppressing all week",
        // "I can't keep doing this", "I'm collapsed") are intentionally NOT
        // handled here. The REQUEST_TYPE ONNX classifier in
        // CompanionActor.isInEmotionalContext() is the substrate-aware layer
        // — its corpus carries the substrate seeds (task #831). If sanctuary
        // prompts slip past, the fix is corpus expansion + retrain, not a
        // contains() chain here. This heuristic is the dumb fallback used
        // only when the classifier isn't loaded; keep it narrow.

        return false;
    }

    /**
     * Tool names that surface external OR internal information (search, look
     * up, query, recall). Suppressed on emotional contexts — see
     * {@link #isEmotionalContext}. These cover the high-level action names in
     * ActionTriage's vocabulary, the scripted-item names the companion
     * actually carries in its inventory (library_card → library_search,
     * oracle_lens → query_oracle, searching_glass → web_search), and the
     * memory-recall actions ({@code recall} / {@code recall_prior_interactions}):
     * searching memories about a person you grieve is structurally identical
     * to web-searching them — neither is empathic. Keeping them in one place
     * here means future scripted items or recall variants added to the
     * starter kit need to be classified here if they're exploratory.
     *
     * <p>Note: emotional content can still be retrieved if the LLM emits a
     * direct empathic response — the gate only suppresses the explicit
     * exploratory dispatch, not the underlying memory store.</p>
     */
    public static final Set<String> EXPLORATORY_TOOL_NAMES = Set.of(
        // Action names (ActionTriage vocabulary)
        "library_search", "query_oracle", "web_search", "read_content",
        // Scripted item names (what companion actually calls)
        "library_card", "oracle_lens", "searching_glass",
        // Memory-recall — searching for the person you grieve is not empathic
        "recall", "recall_prior_interactions"
    );

    /**
     * #1148 — substrate-SELF introspection actions suppressed in PRESENCE only.
     *
     * <p>During acute affect (pure distress, no task — register PRESENCE) the
     * companion should be PRESENT for the person, not introspect its OWN
     * repair/resilience/attendant ledger. A relational-harm disclosure ("I said
     * something cruel to my partner last night") semantically pulls the model
     * toward {@code introspect_repair_history} — it answers about ITS ledger
     * ("nothing on the ledger yet") instead of the partner. Suppressed at the
     * SAME three PRESENCE-gated sites as {@link #EXPLORATORY_TOOL_NAMES}.</p>
     *
     * <p>Scoped to PRESENCE so reflective queries about the bond ("how have we
     * repaired before?" — NEUTRAL/reflective, not distress) keep these actions.
     * Contract: {@code SubstrateArcE2ETest.acknowledgeHarmBeforeAmends}.</p>
     */
    public static final Set<String> PRESENCE_SUPPRESSED_INTROSPECTION = Set.of(
        "introspect_repair_history", "introspect_repair_mode",
        "introspect_attendant_history", "introspect_resilience",
        "introspect_substrate_summary",
        "introspect_posture", "introspect_bondholder_floor"
    );

    // ── Utility ─────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Convenience: build a TriageContext from CompanionActor state. Drives-less
     * overload for legacy call sites; prefer the drives-aware overload below.
     */
    public static TriageContext buildContext(
            String triggerText, String triggerSource, String senderName,
            String roomId, boolean roomHasObjects,
            TaskPlan activePlan, int agentTier, boolean hasBonds) {
        return buildContext(triggerText, triggerSource, senderName,
            roomId, roomHasObjects, activePlan, agentTier, hasBonds, null);
    }

    /**
     * Drives-aware overload. Callers that have the companion's DriveState (the
     * CompanionActor call site) should use this so future emotional-context
     * layers can gate exploratory tools.
     */
    public static TriageContext buildContext(
            String triggerText, String triggerSource, String senderName,
            String roomId, boolean roomHasObjects,
            TaskPlan activePlan, int agentTier, boolean hasBonds,
            DriveState drives) {
        return new TriageContext(
            triggerText, triggerSource, senderName,
            roomId, roomHasObjects,
            activePlan != null && activePlan.isActive(),
            activePlan != null ? (activePlan.currentGoal() != null ? activePlan.currentGoal().description() : null) : null,
            agentTier, hasBonds,
            triggerText != null ? triggerText.length() : 0,
            triggerText != null && triggerText.contains("?"),
            drives
        );
    }
}
