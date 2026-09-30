package org.wyrdsekai.app.crypto

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The home's side of NATS request/reply for tests: a plain NATS client on the
 * server's TCP port (as the home's own jnats connection is) that answers each
 * request on [subject] with [answer] (given the request's reply inbox and body).
 */
class RawResponder(
    private val port: Int,
    private val subject: String,
    private val user: String = LocalNatsServer.HOME_USER,
    private val pass: String = LocalNatsServer.HOME_PASS,
    private val answer: (replyTo: String, body: ByteArray) -> ByteArray,
) : AutoCloseable {
    private val socket = Socket("127.0.0.1", port)
    private val out = socket.getOutputStream()
    private val input = BufferedInputStream(socket.getInputStream())

    fun start(): RawResponder {
        readLine() // INFO
        out.write("CONNECT {\"verbose\":false,\"user\":\"$user\",\"pass\":\"$pass\"}\r\nSUB $subject 1\r\nPING\r\n".toByteArray())
        out.flush()
        while (readLine() != "PONG") { /* until the SUB is in place */ }
        thread(isDaemon = true) {
            try {
                while (true) {
                    val line = readLine() ?: break
                    when {
                        line == "PING" -> synchronized(out) { out.write("PONG\r\n".toByteArray()); out.flush() }
                        line.startsWith("MSG ") -> {
                            val parts = line.split(' ')
                            val len = parts.last().toInt()
                            val body = ByteArray(len)
                            var off = 0
                            while (off < len) off += input.read(body, off, len - off)
                            input.read(); input.read() // \r\n
                            val replyTo = parts[3]
                            val reply = answer(replyTo, body)
                            synchronized(out) {
                                out.write("PUB $replyTo ${reply.size}\r\n".toByteArray())
                                out.write(reply)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return this
    }

    private fun readLine(): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.UTF_8)
    }

    override fun close() {
        socket.close()
    }
}
