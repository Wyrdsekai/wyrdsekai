package org.wyrdsekai.app.hermod

import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.LocalInferenceProvider
import org.wyrdsekai.app.platform.epochMillis
import kotlin.time.Instant

/**
 * What this phone does when the mesh knocks. Admission is OURS — the
 * envelope obliges nothing; anything we won't or can't run is declined
 * with the honest reason, and the zone's router simply tries the next
 * candidate. Policy (consent, charging, idleness) is decided by the
 * caller and handed in per knock; this class owns the task checks.
 *
 * v1 executes bare `inference.chat` (params: model?, system?, prompt)
 * against the LOCAL model only. Domain-scoped tasks decline until grant
 * verification ships on-device; full ChatRequest rides decline because
 * their tools cannot execute here.
 */
class HermodEngine(
    private val local: LocalInferenceProvider,
    private val nowMillis: () -> Long = { epochMillis() },
) {

    suspend fun answer(knock: HermodMessage.Knock, eligible: Boolean): HermodMessage.Answer =
        HermodMessage.Answer(knock.knockId, admit(knock.envelope, eligible))

    private suspend fun admit(e: EnvelopeDto, eligible: Boolean): AnswerBody {
        if (!eligible) return AnswerBody.declined("device not available (consent/charging)")
        if (e.taskType != TASK_TYPE_CHAT) {
            return AnswerBody.declined("task type not supported on this device: ${e.taskType}")
        }
        if (e.dataDomain != "none") {
            return AnswerBody.declined("no grant verification on this device yet")
        }
        if (expired(e.expiresAt)) return AnswerBody.declined("envelope expired")
        if (local.state.value != "running") return AnswerBody.declined("no local model loaded")
        val prompt = e.params["prompt"]
            ?: return AnswerBody.declined("no prompt in envelope")

        val messages = buildList {
            e.params["system"]?.takeIf { it.isNotBlank() }?.let { add(ChatMessage("system", it)) }
            add(ChatMessage("user", prompt))
        }
        return try {
            val response = local.completeLocal(
                messages,
                CompletionOptions(
                    maxTokens = e.tokenBudget.coerceIn(16, 2048).toInt(),
                    temperature = 0.7,
                ),
            )
            AnswerBody.ok(response.content)
        } catch (ex: Exception) {
            AnswerBody.fail("${ex::class.simpleName}: ${ex.message}")
        }
    }

    private fun expired(expiresAt: String): Boolean = try {
        expiresAt.isNotBlank() && Instant.parse(expiresAt).toEpochMilliseconds() < nowMillis()
    } catch (e: Exception) {
        false // an unreadable expiry is the zone's bug, not a reason to refuse
    }

    companion object {
        const val TASK_TYPE_CHAT = "inference.chat"
    }
}
