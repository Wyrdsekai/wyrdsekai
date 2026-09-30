package org.wyrdsekai.app.engine.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.wyrdsekai.app.crypto.decodeZoneKey
import org.wyrdsekai.app.network.HomeLink
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * /P5 — parse a `wyrdphone://` connection invite.
 *
 * Minted by `wyrd phone invite` (relay /phone-invite endpoint):
 *   wyrdphone://host[:port]/<base64url-JSON>
 * payload `{ v, kind:"phone", relays:[{ws_url, nats_user, nats_password,
 * fp?, ca_fp?}], household_id, zone_id, minted_at, zk?, home_ca_fp?,
 * lan_https?, home_bus? }`.
 *
 * `relays` is an ORDERED failover list (one entry today). `fp`/`ca_fp`
 * appear only for self-signed relays and are pinned in the
 * HouseholdTrustStore; ACME relays carry no pin material (system trust applies).
 *
 * Since 0.5.0 ( W2/W3, D1): `zk` is the home's
 * public tunnel key (base64url, 32 bytes) the phone seals its tunnel and
 * requests to; `home_ca_fp` (lowercase hex SHA-256 of the household CA) and
 * `lan_https` (https://host:7443) let it connect directly on the home
 * network, and `home_bus` names the home's bus websocket as a phone reaches
 * it (wss://host:<bus port + 1>). An invite for the home network alone may
 * carry no relays. The relays are the only relay addresses the phone uses.
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
    /** The home's public tunnel key, as sent (base64url); null on invites from before 0.5.0. */
    val zk: String? = null,
    /** Lowercase hex SHA-256 of the household CA, no colons. */
    val homeCaFp: String? = null,
    /** The home's HTTPS address on its own network, https://host:7443. */
    val lanHttps: String? = null,
    /**
     * The home's bus websocket on its own network (wss://host:<bus port + 1>),
     * pinned to the household CA like [lanHttps]. Null on invites from before
     * the home named it, and for a plain ws:// one (a home in its plaintext
     * transition): the phone never uses the bus in the clear.
     */
    val homeBus: String? = null,
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
            val zk = json["zk"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            require(zk == null || decodeZoneKey(zk) != null) { "Invite payload is not valid (re-copy the full URL)" }
            val homeCaFp = json["home_ca_fp"]?.jsonPrimitive?.contentOrNull
                ?.replace(":", "")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            require(homeCaFp == null || HEX_SHA256.matches(homeCaFp)) { "Invite payload is not valid (re-copy the full URL)" }
            val lanHttps = json["lan_https"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            require(lanHttps == null || lanHttps.startsWith("https://", ignoreCase = true)) {
                "Invite payload is not valid (re-copy the full URL)"
            }
            val homeBus = HomeLink.usableHomeBus(json["home_bus"]?.jsonPrimitive?.contentOrNull)
            val relayArray = json["relays"]?.jsonArray
            require(!relayArray.isNullOrEmpty() || (lanHttps != null && homeCaFp != null)) { "Invite carries no relays" }

            val relays = relayArray.orEmpty().mapIndexed { i, el ->
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
                zk = zk,
                homeCaFp = homeCaFp,
                lanHttps = lanHttps?.trimEnd('/'),
                homeBus = homeBus,
            )
        }

        private val HEX_SHA256 = Regex("^[0-9a-f]{64}$")

        private fun String?.unspecifiedToNull(): String? =
            if (isNullOrEmpty() || this == "unspecified") null else this
    }
}
