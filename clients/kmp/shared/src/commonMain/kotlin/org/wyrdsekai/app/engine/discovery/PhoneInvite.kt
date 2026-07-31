package org.wyrdsekai.app.engine.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * /P5 — parse a `wyrdphone://` connection invite.
 *
 * Minted by `wyrd phone invite` (relay /phone-invite endpoint):
 *   wyrdphone://host[:port]/<base64url-JSON>
 * payload `{ v, kind:"phone", relays:[{ws_url, nats_user, nats_password,
 * fp?, ca_fp?}], household_id, zone_id, minted_at }`.
 *
 * `relays` is an ORDERED failover list (one entry today). `fp`/`ca_fp`
 * appear only for self-signed relays and seed the HouseholdTrustStore
 * TOFU pin; ACME relays carry no pin material (system trust applies).
 *
 * Pure parsing only — callers persist into [SavedHouseholdConfig] and
 * the trust store. Throws [IllegalArgumentException] with a readable
 * message on malformed input; the connect screen surfaces it verbatim.
 */
data class PhoneInvite(
    val relays: List<Relay>,
    val householdId: String?,
    /** Zone hint — lets the client skip the wyrd.discover.zone round trip. */
    val zoneId: String?,
    val mintedAt: Long?,
) {
    data class Relay(
        val wsUrl: String,
        val natsUser: String,
        val natsPassword: String,
        /** Relay leaf-cert SHA-256 (colon-hex) — self-signed relays only. */
        val fp: String?,
        /** Household CA SHA-256 (colon-hex) — self-signed relays only. */
        val caFp: String?,
    )

    companion object {
        private const val SCHEME = "wyrdphone://"

        fun isPhoneInviteUrl(text: String): Boolean =
            text.trim().lowercase().startsWith(SCHEME)

        @OptIn(ExperimentalEncodingApi::class)
        fun parse(url: String): PhoneInvite {
            val trimmed = url.trim()
            require(isPhoneInviteUrl(trimmed)) { "Not a wyrdphone:// invite URL" }
            val rest = trimmed.substring(SCHEME.length)
            val slash = rest.indexOf('/')
            require(slash > 0 && slash < rest.length - 1) {
                "Invite URL is missing its payload"
            }
            val json = try {
                val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(rest.substring(slash + 1))
                Json.parseToJsonElement(decoded.decodeToString()).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invite payload is not valid (re-copy the full URL)", e)
            }

            val kind = json["kind"]?.jsonPrimitive?.content
            require(kind == "phone") { "Not a phone invite (kind=${kind ?: "missing"})" }
            val relayArray = json["relays"]?.jsonArray
            require(!relayArray.isNullOrEmpty()) { "Invite carries no relays" }

            val relays = relayArray.mapIndexed { i, el ->
                val o = el.jsonObject
                val wsUrl = o["ws_url"]?.jsonPrimitive?.content
                val user = o["nats_user"]?.jsonPrimitive?.content
                val password = o["nats_password"]?.jsonPrimitive?.content
                require(!wsUrl.isNullOrEmpty() && !user.isNullOrEmpty()
                        && !password.isNullOrEmpty()) {
                    "Relay entry ${i + 1} is incomplete"
                }
                Relay(
                    wsUrl = wsUrl,
                    natsUser = user,
                    natsPassword = password,
                    fp = o["fp"]?.jsonPrimitive?.content,
                    caFp = o["ca_fp"]?.jsonPrimitive?.content,
                )
            }
            return PhoneInvite(
                relays = relays,
                householdId = json["household_id"]?.jsonPrimitive?.content.unspecifiedToNull(),
                zoneId = json["zone_id"]?.jsonPrimitive?.content.unspecifiedToNull(),
                mintedAt = json["minted_at"]?.jsonPrimitive?.longOrNull,
            )
        }

        private fun String?.unspecifiedToNull(): String? =
            if (isNullOrEmpty() || this == "unspecified") null else this
    }
}
