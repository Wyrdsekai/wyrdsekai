package org.wyrdsekai.app.engine.between

/**
 * Splits the NATS protocol stream into operations, by BYTES. A MSG payload is
 * taken by the byte length NATS declares, so a payload with multi-byte UTF-8
 * characters, or a binary sealed frame holding "\r\n", never bleeds into the
 * next line. Chunks may split anywhere; incomplete input waits for the next feed.
 */
internal class NatsReadBuffer {
    sealed interface Op {
        class Msg(val subject: String, val sid: Int, val payload: ByteArray) : Op
        data object Ping : Op
        data object Pong : Op
        data class Err(val line: String) : Op
    }

    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<Op> {
        buf += chunk
        val ops = mutableListOf<Op>()
        var pos = 0
        while (true) {
            val eol = indexOfCrlf(pos)
            if (eol < 0) break
            val line = buf.decodeToString(pos, eol)
            if (line.startsWith("MSG ")) {
                // MSG <subject> <sid> [reply-to] <#bytes>
                val parts = line.split(' ').filter { it.isNotEmpty() }
                val sid = parts.getOrNull(2)?.toIntOrNull()
                val len = parts.lastOrNull()?.toIntOrNull()
                if (parts.size < 4 || sid == null || len == null || len < 0) {
                    pos = eol + 2
                    continue
                }
                val start = eol + 2
                if (buf.size < start + len + 2) break
                ops += Op.Msg(parts[1], sid, buf.copyOfRange(start, start + len))
                pos = start + len + 2
                continue
            }
            when {
                line == "PING" -> ops += Op.Ping
                line == "PONG" -> ops += Op.Pong
                line.startsWith("-ERR") -> ops += Op.Err(line)
                // INFO (cluster changes), +OK and anything unknown: skipped.
            }
            pos = eol + 2
        }
        buf = buf.copyOfRange(pos, buf.size)
        return ops
    }

    private fun indexOfCrlf(from: Int): Int {
        var i = from
        while (i + 1 < buf.size) {
            if (buf[i] == CR && buf[i + 1] == LF) return i
            i++
        }
        return -1
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}
