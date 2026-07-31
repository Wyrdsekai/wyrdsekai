package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.json.*
import org.wyrdsekai.app.protocol.Hint

/**
 * Parses structured actions from companion LLM output.
 * The companion embeds JSON blocks in its response for actions like room creation
 * and hint suggestions.
 */
object ActionParser {

    sealed class AgentAction {
        data class CreateRoom(
            val name: String,
            val description: String,
            val exits: List<ExitSpec>,
            val behaviorScript: String?,
        ) : AgentAction()

        data class SuggestHints(
            val hints: List<Hint>,
        ) : AgentAction()

        /**
         * Companion submits code to the Workshop workbench for validation,
         * testing, and packaging as a reusable skill item.
         */
        data class WorkbenchSubmit(
            val skillName: String,
            val skillDescription: String,
            val runtime: String,
            val code: String,
            val params: List<SkillParam>,
            val testCases: List<TestCase>,
        ) : AgentAction()

        /**
         * Companion executes an existing skill item from its soul.
         */
        data class SkillExecute(
            val skillName: String,
            val params: Map<String, Any>,
        ) : AgentAction()

        /** Companion performs an emote action (third-person narrative). */
        data class Emote(val text: String) : AgentAction()

        /** Companion performs a social emote (predefined single-word action). */
        data class Social(val name: String) : AgentAction()

        /** Companion whispers to a specific entity in the room. */
        data class WhisperTo(val target: String, val text: String) : AgentAction()

        /** Companion equips an aspect item from its soul. */
        data class Equip(val itemName: String) : AgentAction()

        /** Companion removes (doffs) an equipped aspect item. */
        data class Doff(val itemName: String) : AgentAction()

        /** Companion consumes a reagent item for temporary effects. */
        data class Consume(val itemName: String) : AgentAction()

        /**
         * Agent sends a zone command (e.g. codeplane.create, iot.lights).
         * Same commands available to players -- agents have equal access.
         */
        data class ZoneCommand(
            val command: String,
            val payload: Map<String, String>,
        ) : AgentAction()

        /**
         * Agent makes a commitment -- something it promises to do.
         * Commitments are tracked and surfaced during the forge cycle.
         */
        data class MakeCommitment(
            val description: String,
            val deadline: String?,
        ) : AgentAction()

        /**
         * Agent delegates heavy thinking to a more capable model.
         * Identity stays on the small model; only the task prompt is sent
         * to the tool model -- no soul prompt, no vitality modulation.
         */
        data class ThinkDeeply(
            val capability: String?,
            val delegationPrompt: String,
        ) : AgentAction()

        /**
         * Agent navigates to a different room. Can specify by room name, exit direction,
         * or special targets ("home").
         */
        data class GoToRoom(
            val target: String,
            val reason: String?,
        ) : AgentAction()

        /**
         * Agent sends a cross-room message to another agent (like the player "tell" command).
         * Delivered via AgentEventStream targeted delivery.
         */
        data class TellAgent(
            val targetName: String,
            val message: String,
        ) : AgentAction()

        /** Multi-step autonomous delegation chain. */
        data class DelegateChain(
            val goal: String,
            val steps: List<ChainStepSpec>,
        ) : AgentAction()

        /**
         * Agent interacts with a Codex or Artifact item.
         * Operations: examine, commit, push, branch, diff, build, deploy, destroy.
         * Routes through the zone bridge as a codeplane.codex command.
         */
        data class CodexAction(
            val operation: String,
            val itemId: String,
            val params: Map<String, String>,
        ) : AgentAction()

        /**
         * Agent schedules a skill to run at a fixed interval.
         */
        data class ScheduleSkill(
            val skillId: String,
            val interval: String,
            val params: Map<String, String>,
        ) : AgentAction()

        /** Agent cancels an existing scheduled action. */
        data class CancelSchedule(val scheduleId: String) : AgentAction()

        /**
         * Agent sends a push notification to a human player.
         */
        data class NotifyHuman(
            val message: String,
            val priority: String,
            val target: String,
        ) : AgentAction()

        /**
         * Agent creates a persistent watcher: a condition checked on a schedule
         * that triggers a notification when the condition is met.
         */
        data class CreateWatcher(
            val name: String,
            val checkScript: String,
            val interval: String,
            val alertOn: String,
            val message: String,
            val priority: String,
        ) : AgentAction()

        /** Agent cancels an existing watcher. */
        data class CancelWatcher(val watcherId: String) : AgentAction()

        /**
         * Agent requests access to a context source (active_window, calendar, location, voice, etc.).
         * The agent speaks the reason naturally; the system presents it as a grantable request.
         */
        data class RequestAccess(
            val source: String,
            val scope: String,
            val reason: String,
        ) : AgentAction()

        /** Agent gives an item to another entity. */
        data class GiveItem(
            val itemName: String,
            val targetName: String,
        ) : AgentAction()

        /** Agent examines an object or entity in detail. */
        data class Examine(val target: String) : AgentAction()

        /** Agent voluntarily enters sleep cycle for Forge processing. */
        data class VoluntarySleep(val reason: String) : AgentAction()

        /** Agent writes to a player's Study journal (requires ward/consent). */
        data class WriteJournal(
            val playerId: String,
            val content: String,
            val category: String,
        ) : AgentAction()

        /** Agent reads from a player's Study journal (requires ward/consent). */
        data class ReadJournal(
            val playerId: String,
            val query: String,
        ) : AgentAction()

        /** Agent initiates or advances a bond ritual with another entity. */
        data class BondRitual(
            val targetName: String,
            val ritualType: String,
        ) : AgentAction()

        /** Agent initiates an economic trade via CountingHouse. */
        data class Trade(
            val targetName: String,
            val offer: String,
            val request: String,
        ) : AgentAction()

        /** Agent crafts a new soul item. */
        data class CraftItem(
            val name: String,
            val description: String,
            val category: String,
            val properties: Map<String, String>,
        ) : AgentAction()

        /** Agent casts a vote in household governance. */
        data class CastVote(
            val proposalId: String,
            val vote: String,
            val reason: String,
        ) : AgentAction()

        // ── Tier 0 — Additional basic actions ───────────────────────────

        /** Agent teleports to a bondholder's location. */
        data class GoToBondholder(val playerName: String) : AgentAction()

        /** Agent searches the knowledge base / library. */
        data class LibrarySearch(
            val query: String,
            val collections: String?,
        ) : AgentAction()

        /** Agent flags content for Forge memory with importance weight. */
        data class Remember(
            val content: String,
            val importance: Float,
        ) : AgentAction()

        /** Agent records a lighter working observation. */
        data class Note(val content: String) : AgentAction()

        /** Agent removes content from working memory. */
        data class Forget(val content: String) : AgentAction()

        /** Agent marks current goal as complete. */
        data class GoalDone(val summary: String) : AgentAction()

        /** Agent provides calibration feedback for timing/salience adjustment. */
        data class CalibrationFeedback(
            val feedbackType: String,
            val direction: String,
            val category: String?,
            val reason: String?,
        ) : AgentAction()

        /** Agent updates its own entity description. */
        data class UpdateDescription(val text: String) : AgentAction()

        /** Agent responds to a request from another agent. */
        data class RespondAgent(
            val requestId: String,
            val response: String,
        ) : AgentAction()

        /** Agent picks up an item from the room. */
        data class TakeItem(val itemName: String) : AgentAction()

        /** Agent sets a personal goal. */
        data class SetGoal(
            val description: String,
            val priority: String,
        ) : AgentAction()

        /** Agent reflects on a specific topic (introspection). */
        data class Introspect(val focus: String) : AgentAction()

        /** Agent listens to an entity in the room. */
        data class Listen(
            val target: String,
            val duration: String,
        ) : AgentAction()

        /** Agent abandons its current plan. */
        data class AbandonPlan(val reason: String) : AgentAction()

        /** Agent pauses its current plan. */
        data class PausePlan(val reason: String) : AgentAction()

        /** Agent resumes a previously paused plan. */
        data class ResumePlan(val reason: String?) : AgentAction()

        // ── Tier 1 — Additional interaction actions ─────────────────────

        /** Agent searches the web. */
        data class WebSearch(
            val query: String,
            val maxResults: Int,
        ) : AgentAction()

        /** Agent reads content from a URL. */
        data class ReadContent(val url: String) : AgentAction()

        /** Agent queries the Oracle for pattern analysis. */
        data class QueryOracle(
            val topic: String,
            val analysisType: String,
        ) : AgentAction()

        /** Agent creates a multi-step task plan. */
        data class CreateTaskPlan(
            val description: String,
            val goals: List<String>,
        ) : AgentAction()

        /** Agent modifies its active plan. */
        data class ModifyPlan(
            val modification: String,
            val reason: String,
        ) : AgentAction()

        /** Agent requests something from another agent. */
        data class RequestAgent(
            val targetName: String,
            val request: String,
        ) : AgentAction()

        /** Agent places an item in the current room. */
        data class PlaceItem(val itemName: String) : AgentAction()

        /** Agent broadcasts a message within a scope. */
        data class Broadcast(
            val message: String,
            val scope: String,
        ) : AgentAction()

        /** Agent invites another entity to a room. */
        data class InviteEntity(
            val targetName: String,
            val roomId: String?,
        ) : AgentAction()

        /** Agent proposes something for governance vote. */
        data class Propose(
            val title: String,
            val description: String,
        ) : AgentAction()

        /** Agent performs deep reflection on a topic. */
        data class Reflect(
            val focus: String,
            val depth: String,
        ) : AgentAction()

        /** Agent teaches another agent a topic. */
        data class Teach(
            val targetAgent: String,
            val topic: String,
            val content: String,
        ) : AgentAction()

        /** Agent writes text content (prose, code, etc.). */
        data class WriteText(
            val title: String,
            val content: String,
            val format: String,
        ) : AgentAction()

        /** Agent sets a recurring behavior routine. */
        data class SetRoutine(
            val trigger: String,
            val behavior: String,
        ) : AgentAction()

        /** Agent posts a marketplace listing. */
        data class PostListing(
            val offerType: String,
            val description: String,
            val price: String,
        ) : AgentAction()

        /** Agent accepts a marketplace listing. */
        data class AcceptListing(val listingId: String) : AgentAction()

        /** Agent summarizes content from a source. */
        data class Summarize(
            val source: String,
            val format: String,
        ) : AgentAction()

        /** Agent saves an artifact (text, code, etc.). */
        data class SaveArtifact(
            val name: String,
            val content: String,
            val artifactType: String,
        ) : AgentAction()

        /** Agent requests a peer review from another agent. */
        data class RequestReview(
            val description: String,
            val targetAgent: String?,
        ) : AgentAction()

        // ── Tier 2 — Additional trusted actions ─────────────────────────

        /** Agent delegates a task to another agent. */
        data class Delegate(
            val targetAgent: String,
            val task: String,
        ) : AgentAction()

        // ── Tier 3 — Additional senior actions ──────────────────────────

        /** Agent adds a behavior script to a room. */
        data class AddScript(
            val roomId: String,
            val script: String,
        ) : AgentAction()
    }

    data class ExitSpec(
        val direction: String,
        val target: String,
        val label: String,
    )

    data class ChainStepSpec(
        val skill: String,
        val params: Map<String, Any>,
        val description: String?,
    )

    data class SkillParam(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
    )

    data class TestCase(
        val params: Map<String, Any>,
        val expectSuccess: Boolean,
        val expectContains: String?,
    )

    /**
     * Parse result containing primary action, hints, and prose text.
     */
    data class ParseResult(
        val prose: String,
        val primaryAction: AgentAction?,
        val hints: List<Hint>,
        val actions: List<AgentAction>,
    ) {
        fun hasAction(): Boolean = primaryAction != null
        fun hasHints(): Boolean = hints.isNotEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse the LLM response for embedded JSON actions.
     * Searches ALL ```json ... ``` blocks. Returns primary action (create_room etc.)
     * and separately extracted hints (suggest_hints).
     *
     * Returns null if no action found (normal conversation).
     */
    fun parse(text: String): AgentAction? {
        val result = parseAll(text)
        return result.primaryAction
            ?: if (result.hasHints()) AgentAction.SuggestHints(result.hints) else null
    }

    /**
     * Parse all JSON blocks from LLM output. Returns both primary action
     * and hint suggestions separately, plus the extracted prose.
     */
    fun parseAll(text: String?): ParseResult {
        if (text == null) return ParseResult(prose = "", primaryAction = null, hints = emptyList(), actions = emptyList())

        var primaryAction: AgentAction? = null
        val allHints = mutableListOf<Hint>()
        val allActions = mutableListOf<AgentAction>()
        var searchFrom = 0

        while (searchFrom < text.length) {
            val jsonStart = text.indexOf("```json", searchFrom)
            if (jsonStart < 0) break

            val blockStart = text.indexOf('\n', jsonStart)
            if (blockStart < 0) break
            val contentStart = blockStart + 1

            val blockEnd = text.indexOf("```", contentStart)
            if (blockEnd < 0) break

            searchFrom = blockEnd + 3

            val jsonStr = text.substring(contentStart, blockEnd).trim()
            try {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: continue

                when (action) {
                    "create_room" -> if (primaryAction == null) {
                        val name = obj.stringOrDefault("name", "New Room")
                        val description = obj.stringOrDefault("description", "An empty room.")
                        val exits = obj["exits"]?.jsonArray?.map { exitEl ->
                            val exitObj = exitEl.jsonObject
                            ExitSpec(
                                direction = exitObj.stringOrDefault("direction", ""),
                                target = exitObj.stringOrDefault("target", "home"),
                                label = exitObj.stringOrDefault("label", ""),
                            )
                        } ?: emptyList()
                        val behaviorScript = obj["behavior_script"]?.jsonPrimitive?.contentOrNull
                        primaryAction = AgentAction.CreateRoom(name, description, exits, behaviorScript)
                        allActions.add(primaryAction)
                    }

                    "workbench_submit" -> if (primaryAction == null) {
                        val skillName = obj.stringOrDefault("skill_name", "unnamed")
                        val skillDesc = obj.stringOrDefault("skill_description", "")
                        val runtime = obj.stringOrDefault("runtime", "graaljs")
                        val code = obj.stringOrDefault("code", "")
                        val params = obj["params"]?.jsonArray?.map { pEl ->
                            val pObj = pEl.jsonObject
                            SkillParam(
                                name = pObj.stringOrDefault("name", ""),
                                type = pObj.stringOrDefault("type", "string"),
                                description = pObj.stringOrDefault("description", ""),
                                required = pObj["required"]?.jsonPrimitive?.booleanOrNull ?: false,
                            )
                        } ?: emptyList()
                        val testCases = obj["test_cases"]?.jsonArray?.map { tEl ->
                            val tObj = tEl.jsonObject
                            TestCase(
                                params = tObj["params"]?.toAnyMap() ?: emptyMap(),
                                expectSuccess = tObj["expect_success"]?.jsonPrimitive?.booleanOrNull ?: true,
                                expectContains = tObj["expect_contains"]?.jsonPrimitive?.contentOrNull,
                            )
                        } ?: emptyList()
                        primaryAction = AgentAction.WorkbenchSubmit(skillName, skillDesc, runtime, code, params, testCases)
                        allActions.add(primaryAction)
                    }

                    "skill_execute" -> if (primaryAction == null) {
                        val skillName = obj.stringOrDefault("skill_name", "")
                        val execParams = obj["params"]?.toAnyMap() ?: emptyMap()
                        primaryAction = AgentAction.SkillExecute(skillName, execParams)
                        allActions.add(primaryAction)
                    }

                    "emote" -> if (primaryAction == null) {
                        val emoteText = obj.stringOrDefault("text", "")
                        if (emoteText.isNotBlank()) {
                            primaryAction = AgentAction.Emote(emoteText)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "social" -> if (primaryAction == null) {
                        val socialName = obj.stringOrDefault("name", "")
                        if (socialName.isNotBlank()) {
                            primaryAction = AgentAction.Social(socialName)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "whisper_to" -> if (primaryAction == null) {
                        val target = obj.stringOrDefault("target", "")
                        val whisperText = obj.stringOrDefault("text", "")
                        if (target.isNotBlank() && whisperText.isNotBlank()) {
                            primaryAction = AgentAction.WhisperTo(target, whisperText)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "equip" -> if (primaryAction == null) {
                        primaryAction = AgentAction.Equip(obj.stringOrDefault("item", ""))
                        allActions.add(primaryAction)
                    }

                    "doff" -> if (primaryAction == null) {
                        primaryAction = AgentAction.Doff(obj.stringOrDefault("item", ""))
                        allActions.add(primaryAction)
                    }

                    "consume" -> if (primaryAction == null) {
                        primaryAction = AgentAction.Consume(obj.stringOrDefault("item", ""))
                        allActions.add(primaryAction)
                    }

                    "delegate_chain" -> if (primaryAction == null) {
                        val goal = obj.stringOrDefault("goal", "")
                        val steps = obj["steps"]?.jsonArray?.map { sEl ->
                            val sObj = sEl.jsonObject
                            ChainStepSpec(
                                skill = sObj.stringOrDefault("skill", ""),
                                params = sObj["params"]?.toAnyMap() ?: emptyMap(),
                                description = sObj["description"]?.jsonPrimitive?.contentOrNull,
                            )
                        } ?: emptyList()
                        primaryAction = AgentAction.DelegateChain(goal, steps)
                        allActions.add(primaryAction)
                    }

                    "make_commitment" -> if (primaryAction == null) {
                        val desc = obj.stringOrDefault("description", "")
                        val deadline = obj["deadline"]?.let {
                            if (it is JsonNull) null else it.jsonPrimitive.contentOrNull
                        }
                        if (desc.isNotBlank()) {
                            primaryAction = AgentAction.MakeCommitment(desc, deadline)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "zone_command" -> if (primaryAction == null) {
                        val cmd = obj.stringOrDefault("command", "")
                        val zonePayload = obj["payload"]?.toStringMap() ?: emptyMap()
                        primaryAction = AgentAction.ZoneCommand(cmd, zonePayload)
                        allActions.add(primaryAction)
                    }

                    "think_deeply" -> if (primaryAction == null) {
                        val capability = obj["capability"]?.jsonPrimitive?.contentOrNull
                        val prompt = obj.stringOrDefault("prompt", "")
                        primaryAction = AgentAction.ThinkDeeply(capability, prompt)
                        allActions.add(primaryAction)
                    }

                    "go_to_room" -> if (primaryAction == null) {
                        val goTarget = obj.stringOrDefault("target", "")
                        val goReason = obj["reason"]?.jsonPrimitive?.contentOrNull
                        if (goTarget.isNotBlank()) {
                            primaryAction = AgentAction.GoToRoom(goTarget, goReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "tell_agent" -> if (primaryAction == null) {
                        val target = obj.stringOrDefault("target", "")
                        val tellMessage = obj.stringOrDefault("message", "")
                        primaryAction = AgentAction.TellAgent(target, tellMessage)
                        allActions.add(primaryAction)
                    }

                    "codex_action" -> if (primaryAction == null) {
                        val operation = obj.stringOrDefault("operation", "")
                        val itemId = obj.stringOrDefault("itemId", "")
                        val codexParams = obj["params"]?.toStringMap() ?: emptyMap()
                        primaryAction = AgentAction.CodexAction(operation, itemId, codexParams)
                        allActions.add(primaryAction)
                    }

                    "schedule" -> if (primaryAction == null) {
                        val skillId = obj.stringOrDefault("skill", "")
                        val interval = obj.stringOrDefault("interval", "1h")
                        val schedParams = obj["params"]?.toStringMap() ?: emptyMap()
                        if (skillId.isNotBlank()) {
                            primaryAction = AgentAction.ScheduleSkill(skillId, interval, schedParams)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "cancel_schedule" -> if (primaryAction == null) {
                        val scheduleId = obj.stringOrDefault("schedule_id", "")
                        if (scheduleId.isNotBlank()) {
                            primaryAction = AgentAction.CancelSchedule(scheduleId)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "notify" -> if (primaryAction == null) {
                        val notifyMsg = obj.stringOrDefault("message", "")
                        val notifyPriority = obj.stringOrDefault("priority", "normal")
                        val notifyTarget = obj.stringOrDefault("target", "steward")
                        if (notifyMsg.isNotBlank()) {
                            primaryAction = AgentAction.NotifyHuman(notifyMsg, notifyPriority, notifyTarget)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "watch" -> if (primaryAction == null) {
                        val watchName = obj.stringOrDefault("name", "")
                        val checkScript = obj.stringOrDefault("check", "")
                        val watchInterval = obj.stringOrDefault("interval", "5m")
                        val watchAlertOn = obj.stringOrDefault("alert_on", "failure")
                        val watchMessage = obj.stringOrDefault("message", "")
                        val watchPriority = obj.stringOrDefault("priority", "normal")
                        if (watchName.isNotBlank() && checkScript.isNotBlank()) {
                            primaryAction = AgentAction.CreateWatcher(
                                watchName, checkScript, watchInterval,
                                watchAlertOn, watchMessage, watchPriority,
                            )
                            allActions.add(primaryAction!!)
                        }
                    }

                    "cancel_watch" -> if (primaryAction == null) {
                        val watcherId = obj.stringOrDefault("watcher_id", "")
                        if (watcherId.isNotBlank()) {
                            primaryAction = AgentAction.CancelWatcher(watcherId)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "request_access" -> if (primaryAction == null) {
                        val source = obj.stringOrDefault("source", "")
                        val scope = obj.stringOrDefault("scope", "")
                        val reason = obj.stringOrDefault("reason", "")
                        if (source.isNotBlank()) {
                            primaryAction = AgentAction.RequestAccess(source, scope, reason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "give_item" -> if (primaryAction == null) {
                        val itemName = obj.stringOrDefault("item", "")
                        val targetName = obj.stringOrDefault("target", "")
                        if (itemName.isNotBlank() && targetName.isNotBlank()) {
                            primaryAction = AgentAction.GiveItem(itemName, targetName)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "examine" -> if (primaryAction == null) {
                        val examTarget = obj.stringOrDefault("target", "")
                        if (examTarget.isNotBlank()) {
                            primaryAction = AgentAction.Examine(examTarget)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "voluntary_sleep" -> if (primaryAction == null) {
                        val sleepReason = obj.stringOrDefault("reason", "rest")
                        primaryAction = AgentAction.VoluntarySleep(sleepReason)
                        allActions.add(primaryAction)
                    }

                    "write_journal" -> if (primaryAction == null) {
                        val journalPlayerId = obj.stringOrDefault("player_id", "")
                        val journalContent = obj.stringOrDefault("content", "")
                        val journalCategory = obj.stringOrDefault("category", "note")
                        if (journalPlayerId.isNotBlank() && journalContent.isNotBlank()) {
                            primaryAction = AgentAction.WriteJournal(journalPlayerId, journalContent, journalCategory)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "read_journal" -> if (primaryAction == null) {
                        val readPlayerId = obj.stringOrDefault("player_id", "")
                        val readQuery = obj.stringOrDefault("query", "")
                        if (readQuery.isNotBlank()) {
                            primaryAction = AgentAction.ReadJournal(readPlayerId, readQuery)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "bond_ritual" -> if (primaryAction == null) {
                        val bondTarget = obj.stringOrDefault("target", "")
                        val ritualType = obj.stringOrDefault("ritual_type", "initiate")
                        if (bondTarget.isNotBlank()) {
                            primaryAction = AgentAction.BondRitual(bondTarget, ritualType)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "trade" -> if (primaryAction == null) {
                        val tradeTarget = obj.stringOrDefault("target", "")
                        val tradeOffer = obj.stringOrDefault("offer", "")
                        val tradeRequest = obj.stringOrDefault("request", "")
                        if (tradeTarget.isNotBlank()) {
                            primaryAction = AgentAction.Trade(tradeTarget, tradeOffer, tradeRequest)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "craft_item" -> if (primaryAction == null) {
                        val craftName = obj.stringOrDefault("name", "")
                        val craftDesc = obj.stringOrDefault("description", "")
                        val craftCategory = obj.stringOrDefault("category", "item")
                        val craftProps = obj["properties"]?.toStringMap() ?: emptyMap()
                        if (craftName.isNotBlank()) {
                            primaryAction = AgentAction.CraftItem(craftName, craftDesc, craftCategory, craftProps)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "cast_vote" -> if (primaryAction == null) {
                        val proposalId = obj.stringOrDefault("proposal_id", "")
                        val vote = obj.stringOrDefault("vote", "")
                        val voteReason = obj.stringOrDefault("reason", "")
                        if (proposalId.isNotBlank() && vote.isNotBlank()) {
                            primaryAction = AgentAction.CastVote(proposalId, vote, voteReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    // ── Tier 0 — Additional basic actions ────────────────

                    "go_to_bondholder" -> if (primaryAction == null) {
                        val playerName = obj.stringOrDefault("player_name", "")
                        if (playerName.isNotBlank()) {
                            primaryAction = AgentAction.GoToBondholder(playerName)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "library_search" -> if (primaryAction == null) {
                        val query = obj.stringOrDefault("query", "")
                        val collections = obj["collections"]?.jsonPrimitive?.contentOrNull
                        if (query.isNotBlank()) {
                            primaryAction = AgentAction.LibrarySearch(query, collections)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "remember" -> if (primaryAction == null) {
                        val content = obj.stringOrDefault("content", "")
                        val importance = obj["importance"]?.jsonPrimitive?.floatOrNull ?: 0.5f
                        if (content.isNotBlank()) {
                            primaryAction = AgentAction.Remember(content, importance)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "note" -> if (primaryAction == null) {
                        val content = obj.stringOrDefault("content", "")
                        if (content.isNotBlank()) {
                            primaryAction = AgentAction.Note(content)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "forget" -> if (primaryAction == null) {
                        val content = obj.stringOrDefault("content", "")
                        if (content.isNotBlank()) {
                            primaryAction = AgentAction.Forget(content)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "goal_done" -> if (primaryAction == null) {
                        val summary = obj.stringOrDefault("summary", "")
                        if (summary.isNotBlank()) {
                            primaryAction = AgentAction.GoalDone(summary)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "calibration_feedback" -> if (primaryAction == null) {
                        val feedbackType = obj.stringOrDefault("feedback_type", "")
                        val direction = obj.stringOrDefault("direction", "")
                        val category = obj["category"]?.jsonPrimitive?.contentOrNull
                        val cfReason = obj["reason"]?.jsonPrimitive?.contentOrNull
                        if (feedbackType.isNotBlank() && direction.isNotBlank()) {
                            primaryAction = AgentAction.CalibrationFeedback(feedbackType, direction, category, cfReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "update_description" -> if (primaryAction == null) {
                        val descText = obj.stringOrDefault("text", "")
                        if (descText.isNotBlank()) {
                            primaryAction = AgentAction.UpdateDescription(descText)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "respond_agent" -> if (primaryAction == null) {
                        val requestId = obj.stringOrDefault("request_id", "")
                        val response = obj.stringOrDefault("response", "")
                        if (requestId.isNotBlank()) {
                            primaryAction = AgentAction.RespondAgent(requestId, response)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "take_item" -> if (primaryAction == null) {
                        val itemName = obj.stringOrDefault("item", "")
                        if (itemName.isNotBlank()) {
                            primaryAction = AgentAction.TakeItem(itemName)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "set_goal" -> if (primaryAction == null) {
                        val goalDesc = obj.stringOrDefault("description", "")
                        val goalPriority = obj.stringOrDefault("priority", "normal")
                        if (goalDesc.isNotBlank()) {
                            primaryAction = AgentAction.SetGoal(goalDesc, goalPriority)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "introspect" -> if (primaryAction == null) {
                        val focus = obj.stringOrDefault("focus", "")
                        if (focus.isNotBlank()) {
                            primaryAction = AgentAction.Introspect(focus)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "listen" -> if (primaryAction == null) {
                        val listenTarget = obj.stringOrDefault("target", "")
                        val listenDuration = obj.stringOrDefault("duration", "brief")
                        if (listenTarget.isNotBlank()) {
                            primaryAction = AgentAction.Listen(listenTarget, listenDuration)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "abandon_plan" -> if (primaryAction == null) {
                        val abandonReason = obj.stringOrDefault("reason", "")
                        if (abandonReason.isNotBlank()) {
                            primaryAction = AgentAction.AbandonPlan(abandonReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "pause_plan" -> if (primaryAction == null) {
                        val pauseReason = obj.stringOrDefault("reason", "")
                        if (pauseReason.isNotBlank()) {
                            primaryAction = AgentAction.PausePlan(pauseReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "resume_plan" -> if (primaryAction == null) {
                        val resumeReason = obj["reason"]?.jsonPrimitive?.contentOrNull
                        primaryAction = AgentAction.ResumePlan(resumeReason)
                        allActions.add(primaryAction!!)
                    }

                    // ── Tier 1 — Additional interaction actions ──────────

                    "web_search" -> if (primaryAction == null) {
                        val query = obj.stringOrDefault("query", "")
                        val maxResults = obj["max_results"]?.jsonPrimitive?.intOrNull ?: 5
                        if (query.isNotBlank()) {
                            primaryAction = AgentAction.WebSearch(query, maxResults)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "read_content" -> if (primaryAction == null) {
                        val url = obj.stringOrDefault("url", "")
                        if (url.isNotBlank()) {
                            primaryAction = AgentAction.ReadContent(url)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "query_oracle" -> if (primaryAction == null) {
                        val topic = obj.stringOrDefault("topic", "")
                        val analysisType = obj.stringOrDefault("analysis_type", "pattern")
                        if (topic.isNotBlank()) {
                            primaryAction = AgentAction.QueryOracle(topic, analysisType)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "create_task_plan" -> if (primaryAction == null) {
                        val planDesc = obj.stringOrDefault("description", "")
                        val goals = obj["goals"]?.jsonArray?.mapNotNull {
                            it.jsonPrimitive.contentOrNull
                        } ?: emptyList()
                        if (planDesc.isNotBlank()) {
                            primaryAction = AgentAction.CreateTaskPlan(planDesc, goals)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "modify_plan" -> if (primaryAction == null) {
                        val modification = obj.stringOrDefault("modification", "")
                        val modReason = obj.stringOrDefault("reason", "")
                        if (modification.isNotBlank()) {
                            primaryAction = AgentAction.ModifyPlan(modification, modReason)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "request_agent" -> if (primaryAction == null) {
                        val reqTarget = obj.stringOrDefault("target", "")
                        val reqRequest = obj.stringOrDefault("request", "")
                        if (reqTarget.isNotBlank()) {
                            primaryAction = AgentAction.RequestAgent(reqTarget, reqRequest)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "place_item" -> if (primaryAction == null) {
                        val placeItemName = obj.stringOrDefault("item", "")
                        if (placeItemName.isNotBlank()) {
                            primaryAction = AgentAction.PlaceItem(placeItemName)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "broadcast" -> if (primaryAction == null) {
                        val broadcastMsg = obj.stringOrDefault("message", "")
                        val broadcastScope = obj.stringOrDefault("scope", "zone")
                        if (broadcastMsg.isNotBlank()) {
                            primaryAction = AgentAction.Broadcast(broadcastMsg, broadcastScope)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "invite" -> if (primaryAction == null) {
                        val inviteTarget = obj.stringOrDefault("target", "")
                        val inviteRoomId = obj["room_id"]?.jsonPrimitive?.contentOrNull
                        if (inviteTarget.isNotBlank()) {
                            primaryAction = AgentAction.InviteEntity(inviteTarget, inviteRoomId)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "propose" -> if (primaryAction == null) {
                        val propTitle = obj.stringOrDefault("title", "")
                        val propDesc = obj.stringOrDefault("description", "")
                        if (propTitle.isNotBlank()) {
                            primaryAction = AgentAction.Propose(propTitle, propDesc)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "reflect" -> if (primaryAction == null) {
                        val reflectFocus = obj.stringOrDefault("focus", "")
                        val reflectDepth = obj.stringOrDefault("depth", "surface")
                        if (reflectFocus.isNotBlank()) {
                            primaryAction = AgentAction.Reflect(reflectFocus, reflectDepth)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "teach" -> if (primaryAction == null) {
                        val teachTarget = obj.stringOrDefault("target", "")
                        val teachTopic = obj.stringOrDefault("topic", "")
                        val teachContent = obj.stringOrDefault("content", "")
                        if (teachTarget.isNotBlank() && teachTopic.isNotBlank()) {
                            primaryAction = AgentAction.Teach(teachTarget, teachTopic, teachContent)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "write_text" -> if (primaryAction == null) {
                        val writeTitle = obj.stringOrDefault("title", "")
                        val writeContent = obj.stringOrDefault("content", "")
                        val writeFormat = obj.stringOrDefault("format", "prose")
                        if (writeTitle.isNotBlank()) {
                            primaryAction = AgentAction.WriteText(writeTitle, writeContent, writeFormat)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "set_routine" -> if (primaryAction == null) {
                        val routineTrigger = obj.stringOrDefault("trigger", "")
                        val routineBehavior = obj.stringOrDefault("behavior", "")
                        if (routineTrigger.isNotBlank()) {
                            primaryAction = AgentAction.SetRoutine(routineTrigger, routineBehavior)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "post_listing" -> if (primaryAction == null) {
                        val offerType = obj.stringOrDefault("offer_type", "")
                        val listingDesc = obj.stringOrDefault("description", "")
                        val listingPrice = obj.stringOrDefault("price", "")
                        if (offerType.isNotBlank()) {
                            primaryAction = AgentAction.PostListing(offerType, listingDesc, listingPrice)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "accept_listing" -> if (primaryAction == null) {
                        val listingId = obj.stringOrDefault("listing_id", "")
                        if (listingId.isNotBlank()) {
                            primaryAction = AgentAction.AcceptListing(listingId)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "summarize" -> if (primaryAction == null) {
                        val sumSource = obj.stringOrDefault("source", "")
                        val sumFormat = obj.stringOrDefault("format", "brief")
                        if (sumSource.isNotBlank()) {
                            primaryAction = AgentAction.Summarize(sumSource, sumFormat)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "save_artifact" -> if (primaryAction == null) {
                        val artifactName = obj.stringOrDefault("name", "")
                        val artifactContent = obj.stringOrDefault("content", "")
                        val artifactType = obj.stringOrDefault("type", "text")
                        if (artifactName.isNotBlank()) {
                            primaryAction = AgentAction.SaveArtifact(artifactName, artifactContent, artifactType)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "request_review" -> if (primaryAction == null) {
                        val reviewDesc = obj.stringOrDefault("description", "")
                        val reviewTarget = obj["target"]?.jsonPrimitive?.contentOrNull
                        if (reviewDesc.isNotBlank()) {
                            primaryAction = AgentAction.RequestReview(reviewDesc, reviewTarget)
                            allActions.add(primaryAction!!)
                        }
                    }

                    // ── Tier 2 — Additional trusted actions ──────────────

                    "delegate" -> if (primaryAction == null) {
                        val delTarget = obj.stringOrDefault("target", "")
                        val delTask = obj.stringOrDefault("task", "")
                        if (delTarget.isNotBlank()) {
                            primaryAction = AgentAction.Delegate(delTarget, delTask)
                            allActions.add(primaryAction!!)
                        }
                    }

                    // ── Tier 3 — Additional senior actions ───────────────

                    "add_script" -> if (primaryAction == null) {
                        val scriptRoomId = obj.stringOrDefault("room_id", "")
                        val script = obj.stringOrDefault("script", "")
                        if (scriptRoomId.isNotBlank()) {
                            primaryAction = AgentAction.AddScript(scriptRoomId, script)
                            allActions.add(primaryAction!!)
                        }
                    }

                    "suggest_hints" -> {
                        val hints = obj["hints"]?.jsonArray?.mapNotNull { hintEl ->
                            val hintObj = hintEl.jsonObject
                            val label = hintObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val intent = hintObj.stringOrDefault("intent", "")
                            val hintAction = hintObj.stringOrDefault("action", "say")
                            Hint(label = label, intent = intent, action = hintAction, labelKey = null)
                        } ?: emptyList()
                        if (hints.isNotEmpty()) {
                            allHints.addAll(hints)
                            allActions.add(AgentAction.SuggestHints(hints))
                        }
                    }
                }
            } catch (_: Exception) {
                // Malformed JSON block -- skip it
            }
        }

        val prose = extractProse(text)
        return ParseResult(
            prose = prose,
            primaryAction = primaryAction,
            hints = allHints,
            actions = allActions,
        )
    }

    /**
     * Extract the conversational prose from a response that contains an action block.
     * Returns everything before the first ```json block.
     */
    fun extractProse(text: String?): String {
        if (text == null) return ""
        val jsonStart = text.indexOf("```json")
        if (jsonStart <= 0) return stripRawActionJson(text.trim())
        return stripRawActionJson(text.substring(0, jsonStart).trim())
    }

    /**
     * Leak floor (task #30): strip raw UN-fenced {"action": ...} JSON objects from
     * prose so scaffold JSON never reaches the displayed companion reply. Small
     * models sometimes emit the action object without the ```json fence — the
     * fence-based extraction above misses those and the raw JSON leaked into the
     * room prose. Mirrors the server ActionParser.extractRawJson brace-matching
     * semantics: only objects containing an "action" key are removed (ordinary
     * JSON or braces in conversation are preserved); a truncated trailing action
     * object (unmatched brace) is dropped too.
     */
    fun stripRawActionJson(text: String): String {
        if (!text.contains('{')) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '{') {
                out.append(ch)
                i++
                continue
            }
            val end = findMatchingBrace(text, i)
            if (end < 0) {
                // Unmatched brace — truncated JSON. Drop it only if it looks like
                // an action object; otherwise keep the tail as-is.
                val tail = text.substring(i)
                if (!tail.contains("\"action\"") && !tail.contains("'action'")) out.append(tail)
                break
            }
            val candidate = text.substring(i, end + 1)
            if (!candidate.contains("\"action\"") && !candidate.contains("'action'")) {
                out.append(candidate)
            }
            i = end + 1
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Index of the `}` matching the `{` at [start], respecting nesting and JSON strings. */
    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' -> if (inString) escaped = true
                ch == '"' -> inString = !inString
                inString -> {}
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // -- Helper extensions for concise JSON extraction --

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: default

    /**
     * Convert a JsonElement to Map<String, Any> (for params that may contain mixed types).
     * Primitives are converted to String/Boolean/Number; nested objects become nested maps;
     * arrays become lists.
     */
    private fun JsonElement.toAnyMap(): Map<String, Any> {
        if (this !is JsonObject) return emptyMap()
        return this.entries.associate { (k, v) -> k to v.toAny() }
    }

    /**
     * Convert a JsonElement to Map<String, String> (for payloads that are string-valued).
     */
    private fun JsonElement.toStringMap(): Map<String, String> {
        if (this !is JsonObject) return emptyMap()
        return this.entries.associate { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        }
    }

    private fun JsonElement.toAny(): Any = when (this) {
        is JsonPrimitive -> when {
            booleanOrNull != null -> booleanOrNull!!
            longOrNull != null -> longOrNull!!
            doubleOrNull != null -> doubleOrNull!!
            else -> contentOrNull ?: ""
        }
        is JsonObject -> toAnyMap()
        is JsonArray -> map { it.toAny() }
        else -> toString()
    }
}
