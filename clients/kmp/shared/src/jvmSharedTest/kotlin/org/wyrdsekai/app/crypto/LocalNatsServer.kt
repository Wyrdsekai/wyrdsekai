package org.wyrdsekai.app.crypto

import kotlin.io.path.createTempDirectory
import java.net.InetAddress
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * A nats-server on loopback shaped like a relay after D4: a `home` user with
 * full rights, and a phone user `phone1` that may subscribe only its own
 * `_INBOX.phone1.>` (and the tunnel subjects), so a reply inbox anywhere else
 * is refused by the server. Needs a nats-server binary: WYRDSEKAI_TEST_NATS_SERVER
 * or `nats-server` on PATH; [startOrNull] returns null (the test skips) without one.
 */
class LocalNatsServer private constructor(private val process: Process, val port: Int, val wsPort: Int, private val dir: File) : AutoCloseable {
    val wsUrl: String get() = "ws://127.0.0.1:$wsPort"

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        dir.deleteRecursively()
    }

    companion object {
        const val PHONE_USER = "phone1"
        const val PHONE_PASS = "phone-pass"
        const val HOME_USER = "home"
        const val HOME_PASS = "home-pass"

        private fun binary(): File? {
            System.getenv("WYRDSEKAI_TEST_NATS_SERVER")?.let { File(it) }?.takeIf { it.canExecute() }?.let { return it }
            return System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "nats-server") }.firstOrNull { it.canExecute() }
        }

        private fun freePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        /** [phonePublish]: what the phone user may publish (a relay's grant). */
        fun startOrNull(phonePublish: List<String> = listOf("wyrd.>", "_INBOX.$PHONE_USER.>")): LocalNatsServer? {
            val bin = binary() ?: return null
            val port = freePort()
            val wsPort = freePort()
            val dir = createTempDirectory("wyrd-nats-test").toFile()
            val conf = File(dir, "nats.conf")
            conf.writeText(
                """
                listen: 127.0.0.1:$port
                websocket { listen: "127.0.0.1:$wsPort", no_tls: true }
                authorization {
                  users = [
                    { user: "$HOME_USER", password: "$HOME_PASS" }
                    { user: "$PHONE_USER", password: "$PHONE_PASS", permissions: {
                        publish: { allow: [${phonePublish.joinToString(", ") { "\"$it\"" }}] }
                        subscribe: { allow: ["_INBOX.$PHONE_USER.>", "wyrd.tunnel.>"] }
                    } }
                  ]
                }
                """.trimIndent()
            )
            val p = ProcessBuilder(bin.absolutePath, "-c", conf.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "nats.log"))
                .start()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val up = runCatching { Socket("127.0.0.1", wsPort).close(); true }.getOrDefault(false)
                if (up) return LocalNatsServer(p, port, wsPort, dir)
                Thread.sleep(100)
            }
            p.destroyForcibly()
            dir.deleteRecursively()
            return null
        }
    }
}
