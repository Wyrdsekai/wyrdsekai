package org.wyrdsekai.app.hermod

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The phone's half of the zone⇄phone hermod leg. Wire parity is pinned
 * against the Java side's DoorWire/PhoneDoorWire (server module,
 * ThePhoneWireSpeaksOneTongueTest) — the SAME literal vectors are
 * asserted in HermodWireVectorsTest here. Instants ride as ISO-8601
 * strings, signatures as base64 strings (this device does not verify
 * them in v1; the zone's gate does).
 */
@Serializable
data class GrantDto(
    val grantId: String,
    val householdId: String,
    val dataDomain: String,
    val grantedToDeviceClass: String,
    val issuedAt: String = "",
    val expiresAt: String = "",
    val policyVersion: String = "",
    val authoritySignature: String? = null,
)

@Serializable
data class EnvelopeDto(
    val envelopeId: String,
    val householdId: String = "",
    val originDeviceId: String = "",
    val taskType: String,
    val dataDomain: String = "none",
    val capabilityClass: String = "",
    val params: Map<String, String> = emptyMap(),
    val tokenBudget: Long = 0,
    val issuedAt: String = "",
    val expiresAt: String = "",
    val grant: GrantDto? = null,
    val originSignature: String? = null,
)

/** completed=true carries the result; completed=false carries the decline reason. */
@Serializable
data class AnswerBody(
    val completed: Boolean,
    val ok: Boolean,
    val output: String? = null,
    val error: String? = null,
    val declineReason: String? = null,
) {
    companion object {
        fun ok(output: String) = AnswerBody(completed = true, ok = true, output = output)
        fun fail(error: String) = AnswerBody(completed = true, ok = false, error = error)
        fun declined(reason: String) =
            AnswerBody(completed = false, ok = false, declineReason = reason)
    }
}

@Serializable
sealed class HermodMessage {

    /** zone→phone on connect: the identity the zone stamps for this device. */
    @Serializable
    @SerialName("hello")
    data class Hello(val deviceId: String? = null, val householdId: String? = null) : HermodMessage()

    /** zone→phone: an errand at the door. */
    @Serializable
    @SerialName("knock")
    data class Knock(val knockId: String, val envelope: EnvelopeDto) : HermodMessage()

    /** phone→zone: the answer to one knock. */
    @Serializable
    @SerialName("answer")
    data class Answer(val knockId: String, val answer: AnswerBody) : HermodMessage()

    /** phone→zone: battery-truth advertisement — no identity fields BY DESIGN. */
    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        val capabilityClass: String,
        val models: List<String> = emptyList(),
        val residentDataDomains: List<String> = emptyList(),
        val charging: Boolean,
        val idle: Boolean,
        val loadFactor: Double = 0.0,
    ) : HermodMessage()
}

/**
 * explicitNulls=false keeps absent AnswerBody fields off the wire
 * (Jackson NON_NULL parity); the "type" discriminator matches
 * PhoneDoorWire's framing exactly.
 */
val HermodJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    classDiscriminator = "type"
}

fun encodeHermod(message: HermodMessage): String =
    HermodJson.encodeToString(HermodMessage.serializer(), message)

/** Null for unreadable or unknown-type messages — never throws. */
fun decodeHermod(text: String): HermodMessage? = try {
    HermodJson.decodeFromString(HermodMessage.serializer(), text)
} catch (e: Exception) {
    null
}
