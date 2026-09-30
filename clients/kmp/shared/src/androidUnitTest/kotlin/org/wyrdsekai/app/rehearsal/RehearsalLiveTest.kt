package org.wyrdsekai.app.rehearsal

import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wyrdsekai.app.crypto.SealedRequest
import org.wyrdsekai.app.crypto.decodeZoneKey
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.engine.discovery.PhoneInvite
import org.wyrdsekai.app.engine.transit.RelayTunnelServerConnection
import org.wyrdsekai.app.hermod.ConsentMint
import org.wyrdsekai.app.i18n.currentUiStrings
import org.wyrdsekai.app.network.AuthClient
import org.wyrdsekai.app.network.HouseholdTrustStore
import org.wyrdsekai.app.network.InviteSecurity
import org.wyrdsekai.app.network.NatsServerClient
import org.wyrdsekai.app.network.PairingClient
import org.wyrdsekai.app.network.homeBaseUrl
import org.wyrdsekai.app.network.homeBusUrl
import org.wyrdsekai.app.network.parseWsHostPort
import org.wyrdsekai.app.network.pinRelayFromInviteFingerprints
import org.wyrdsekai.app.network.relayLegUrl
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import org.wyrdsekai.app.state.TokenStore
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The phone app's OWN connection code against a live rehearsal relay + home
 * ( W2/W3). Gated: runs only when KMP_REHEARSAL_ENV
 * names the rehearsal's env.json; KMP_REHEARSAL_INVITE may name a file with a
 * fresh `wyrd phone invite` URL (one that carries `home_bus`), else the
 * env.json invite is used.
 *
 * Every step uses the app's classes: the invite parser, the invite pinning and
 * the per-address trust store, the jnats request client with sealed requests,
 * the NATS websocket client with the sealed tunnel, the pairing doors and the
 * token store the app saves into. Nothing secret is printed.
 */
class RehearsalLiveTest {

    private val results = mutableListOf<Pair<Boolean, String>>()

    private fun record(ok: Boolean, what: String) {
        results += ok to what
        println((if (ok) "PASS " else "FAIL ") + what)
    }

    private inline fun <T> step(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        record(false, "$what — ${e::class.simpleName}: ${e.message}")
        null
    }

    private fun sha256Hex(c: X509Certificate?): String? =
        c?.let { MessageDigest.getInstance("SHA-256").digest(it.encoded).joinToString("") { b -> "%02x".format(b) } }

    private fun fp(s: String?): String? = s?.replace(":", "")?.lowercase()

    @Test
    fun thePhonesOwnCodeWorksAgainstTheRehearsal() = runBlocking {
        val envPath = System.getenv("KMP_REHEARSAL_ENV")
        if (envPath.isNullOrBlank()) {
            println("SKIP: set KMP_REHEARSAL_ENV to the rehearsal env.json")
            return@runBlocking
        }
        val env = Json.parseToJsonElement(File(envPath).readText()).jsonObject
        fun path(vararg keys: String): String? {
            var cur: JsonObject? = env
            for (k in keys.dropLast(1)) cur = cur?.get(k)?.jsonObject
            return cur?.get(keys.last())?.jsonPrimitive?.contentOrNull
        }
        val inviteRaw = System.getenv("KMP_REHEARSAL_INVITE")?.takeIf { it.isNotBlank() }
            ?.let { File(it).readText().trim() } ?: path("phone_invite", "raw")!!
        val user = path("people", "steward", "username")!!
        val password = path("people", "steward", "password")!!
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // A token store as the app keeps it (in memory here instead of the Keystore).
        val prefs = MemoryPrefs()
        TokenStore.initForTests(ContextWrapper(null), prefs)
        val store = TokenStore()

        // ── 1. The invite ───────────────────────────────────────────────
        val invite = PhoneInvite.parse(inviteRaw)
        val relay = invite.relays.first()
        val zone = invite.zoneId!!
        val zk = decodeZoneKey(invite.zk)!!
        record(relay.wsUrl == path("relay", "phone_ws_url"), "invite parsed: relay ${relay.wsUrl}, zone $zone, zk present")
        record(invite.homeBus == path("home", "home_bus_wss_url"),
            "invite names the home bus apart from the relay: home_bus=${invite.homeBus}, lan_https=${invite.lanHttps}")
        // What the Welcome/QR path saves from an invite.
        InviteSecurity.remember(invite, store)
        store.saveNatsUrl(relay.wsUrl)
        store.saveRelayUrl(relay.wsUrl)
        store.saveNatsUser(relay.natsUser)
        store.saveNatsPassword(relay.natsPassword)
        store.saveZoneId(zone)

        // ── 2. Pins: relay CA and home CA on one host, kept apart ───────
        val (relayHost, relayPort) = parseWsHostPort(relay.wsUrl)!!
        val (lanHost, lanPort) = parseWsHostPort(invite.lanHttps!!)!!
        val (busHost, busPort) = parseWsHostPort(store.homeBusUrl()!!)!!
        step("pin the relay from the invite fingerprints") {
            record(pinRelayFromInviteFingerprints(relayHost, relayPort, listOfNotNull(relay.caFp, relay.fp)),
                "relay pinned from the invite fingerprints at $relayHost:$relayPort")
        }
        step("pin the home from home_ca_fp") {
            record(InviteSecurity.pinHome(invite.lanHttps, invite.homeCaFp, invite.homeBus),
                "home CA pinned for its HTTPS $lanHost:$lanPort and its bus $busHost:$busPort")
        }
        record(sha256Hex(HouseholdTrustStore.getExact(relayHost, relayPort)) == fp(relay.caFp),
            "the relay's pin is still the relay CA after pinning the home (same host ${relayHost == lanHost})")
        record(sha256Hex(HouseholdTrustStore.getExact(lanHost, lanPort)) == invite.homeCaFp &&
            sha256Hex(HouseholdTrustStore.getExact(busHost, busPort)) == invite.homeCaFp,
            "the home's HTTPS and bus pins are the home CA")

        // ── 3. Relay: sealed mcp.login through the phone door, pinned ───
        val relayClient = NatsServerClient(relay.wsUrl, zone, relay.natsUser, relay.natsPassword, zk)
        val auth = step("sealed mcp.login over the relay") { relayClient.login(user, password) }
        record(auth != null && auth.token.isNotEmpty(), "sealed mcp.login as $user over ${relay.wsUrl} returned a session (userId ${auth?.userId})")
        store.saveAuthToken(auth?.token ?: "")

        // Knocks: on its own zone (sealed, recorded by the home), and on another zone
        // (the relay now carries `wyrd.zone.*.directory.knock`; no home serves that
        // zone here, so the answer is "no responders", said plainly and at once).
        step("knock on the phone's own zone") {
            val answer = relayClient.requestAccess(zone, "kmp live test")
            record(answer.sent && !answer.requestId.isNullOrBlank(), "a sealed knock on zone $zone was recorded (request ${answer.requestId})")
        }
        step("knock on another zone") {
            val t0 = System.nanoTime()
            val answer = relayClient.requestAccess("some-other-zone", "kmp live test")
            val ms = (System.nanoTime() - t0) / 1_000_000
            val strings = currentUiStrings()
            record(answer.message != strings.secKnockRefusedByRelay,
                "the relay carried the knock to another zone (not refused as a permissions violation)")
            record(!answer.sent && answer.message == strings.secKnockNoAnswer && ms < 3_000,
                "no home answers that zone: told plainly in ${ms} ms (\"${answer.message}\")")
        }

        // ── 4. Relay: the sealed tunnel, room_state, look ───────────────
        step("sealed tunnel over the relay") {
            val bc = NatsBetweenClient(scope)
            bc.setCredentials(relay.natsUser, relay.natsPassword)
            bc.connect(relay.wsUrl)
            val q = LinkedBlockingQueue<S2CMessage>()
            val conn = RelayTunnelServerConnection(bc, zone, auth!!.token, zk)
            conn.onMessage { q.add(it) }
            conn.open()
            fun nextRoom(seconds: Long): S2CMessage.RoomState? {
                val until = System.currentTimeMillis() + seconds * 1000
                val seen = mutableListOf<String>()
                while (System.currentTimeMillis() < until) {
                    val m = q.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    seen += m::class.simpleName ?: "?"
                    if (m is S2CMessage.Error) println("  tunnel error frame: ${m}")
                    if (m is S2CMessage.RoomState) return m
                }
                println("  frames seen: $seen")
                return null
            }
            val first = nextRoom(15)
            record(first != null, "sealed tunnel opened and delivered room_state (${first?.room?.name})")
            if (first != null) {
                conn.send(C2SMessage.Look(id = "kmp-live-look-1", roomId = first.room.roomId))
                val again = nextRoom(10)
                record(again != null && again.room.roomId == first.room.roomId, "`look` through the sealed tunnel answered room_state (${again?.room?.name})")
            }
            conn.close()
            bc.disconnect()
        }

        // ── 5. Pairing: the home names its bus, the relay stays the invite's ─
        step("pair over the relay (pair.device)") {
            val creds = relayClient.pairDevice(auth!!.token, ConsentMint.freshLabel(), "phone")!!
            record(creds.natsUrl == invite.homeBus, "relay pair.device reply natsUrl is the home bus (${creds.natsUrl})")
            // What the phone keeps after this door (the RemoteMint path).
            ConsentMint.save(store, creds)
            record(store.relayLegUrl() == relay.wsUrl && store.loadNatsUrl() == relay.wsUrl,
                "after saving the pairing reply the relay is still the invite's (${store.relayLegUrl()})")
            record(store.homeBusUrl() == invite.homeBus && store.loadHomeNatsUser() == creds.natsUser,
                "the bus is kept apart: ${store.homeBusUrl()} as ${creds.natsUser}")
        }
        step("pair over HTTPS (the consent door, POST /api/pair/device)") {
            // Clear the relay door's device so the consent mint runs its first door again.
            prefs.edit().remove("wyrd_pairing_token").remove("wyrd_home_bus_url").apply()
            record(store.homeBaseUrl() == invite.lanHttps, "the home's HTTP base is the invite's lan_https (${store.homeBaseUrl()})")
            val direct = PairingClient.pairSelf(invite.lanHttps, auth!!.token, ConsentMint.freshLabel())!!
            record(direct.relayUrl == null && direct.relayToken == null && direct.natsUrl == invite.homeBus,
                "HTTPS pairing reply: natsUrl=${direct.natsUrl}, relayUrl=${direct.relayUrl}, relayToken=${if (direct.relayToken == null) "none" else "present"}")
            ConsentMint.save(store, direct)
            record(store.relayLegUrl() == relay.wsUrl && store.homeBusUrl() == invite.homeBus && !store.loadPairingToken().isNullOrBlank(),
                "saved: relay=${store.relayLegUrl()}, bus=${store.homeBusUrl()}, device token present")
        }

        // ── 6. The home bus: pinned to home_ca_fp, as this phone's own login ─
        val busUrl = store.homeBusUrl()!!
        val busUser = store.loadHomeNatsUser()!!
        val busPass = store.loadHomeNatsPassword()!!
        step("sealed mcp.login on the home bus (jnats client)") {
            // First attempt: the home answers a pairing only once its bus has taken the new login.
            val busClient = NatsServerClient(busUrl, zone, busUser, busPass, zk)
            try {
                val a = busClient.login(user, password)
                record(a.token.isNotEmpty(), "home bus $busUrl (pinned to home_ca_fp, login $busUser): sealed mcp.login ok on the first attempt")
            } finally {
                busClient.disconnect()
            }
        }
        step("the app's bus client on the home bus") {
            val bc = NatsBetweenClient(scope)
            bc.setCredentials(busUser, busPass)
            bc.connect(busUrl)
            val subject = "wyrd.zone.$zone.mcp.login"
            val sealed = SealedRequest.seal(zk, subject, """{"username":${Json.encodeToString(JsonPrimitive(user))},"password":${Json.encodeToString(JsonPrimitive(password))}}""", System.currentTimeMillis())
            val reply = bc.request(subject, sealed.wire, 10_000)
            val opened = reply?.let { Json.parseToJsonElement(sealed.openReply(it)).jsonObject }
            record(opened?.get("ok")?.jsonPrimitive?.contentOrNull == "true",
                "NatsBetweenClient on the home bus: sealed request with inbox _INBOX.$busUser answered ok")
            // Study sync, addressed to this phone only: claim a newer clock and the
            // home answers with a frame addressed to this phone's login.
            val directed = LinkedBlockingQueue<String>()
            bc.subscribe("between.$zone.*.$busUser.study.sync") { s, d -> directed.add("$s ${d.decodeToString().take(60)}") }
            delay(300)
            val state = """{"type":"study_state","deviceId":"$busUser","userDid":"${auth!!.userId}","token":"${auth.token}","itemCount":1,"latestModified":1,"clockSummary":{"$busUser":1}}"""
            bc.publish("between.$zone.$busUser.*.study.state", state.encodeToByteArray())
            val got = directed.poll(8, TimeUnit.SECONDS)
            record(got != null, "Study sync on the home bus: the home answered on a subject addressed to $busUser (${got?.substringBefore(' ')})")
            bc.disconnect()
        }

        // ── 7. HTTPS login on lan_https, pinned to home_ca_fp ───────────
        step("HTTPS login on lan_https") {
            val http = AuthClient(invite.lanHttps)
            val login = http.login(user, password).getOrThrow()
            val me = http.me(login.token).getOrThrow()
            http.close()
            record(me.username == user, "HTTPS login on ${invite.lanHttps} (pinned to home_ca_fp) → /api/auth/me = ${me.username}")
        }

        // ── 8. The pins are what decides: the relay under the home's CA is refused ─
        step("a wrong pin is refused on a live handshake") {
            val relayPin = HouseholdTrustStore.getExact(relayHost, relayPort)!!
            HouseholdTrustStore.put(null, relayHost, relayPort, HouseholdTrustStore.getExact(lanHost, lanPort)!!)
            val refused = try {
                NatsServerClient(relay.wsUrl, zone, relay.natsUser, relay.natsPassword, zk).connect()
                false
            } catch (e: Exception) {
                true
            } finally {
                HouseholdTrustStore.put(null, relayHost, relayPort, relayPin)
            }
            record(refused, "with the home CA pinned for the relay's address, the relay connection is refused")
        }

        runCatching { relayClient.disconnect() }
        scope.cancel()
        println(if (results.all { it.first }) "ALL PASS (${results.size} checks)" else "${results.count { !it.first }} FAILURE(S)")
        assertTrue(results.all { it.first }, results.filter { !it.first }.joinToString("\n") { it.second })
    }

    /** SharedPreferences in memory: the token store's backing in a JVM test. */
    private class MemoryPrefs : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { puts[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) map.clear()
                for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
                return true
            }
            override fun apply() { commit() }
        }
    }
}
