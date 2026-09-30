package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.wyrdsekai.app.crypto.LocalNatsServer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * connect() reports the link up only once the server has taken the login
 * (PING answered by PONG). A refused login (a phone's bus account the home has
 * not loaded yet, or one it revoked) fails the connect instead of leaving a
 * client that believes it is connected on a link the server closed.
 * Real nats-server on loopback; skipped without one.
 */
class NatsBetweenClientLoginTest {

    @Test
    fun aRefusedLoginFailsTheConnect() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val wrong = NatsBetweenClient(scope).apply { setCredentials(LocalNatsServer.PHONE_USER, "not-the-password") }
            val e = assertFailsWith<IllegalStateException> { wrong.connect(server.wsUrl) }
            assertTrue(e.message!!.contains("Authorization Violation"), e.message)
            assertFalse(wrong.isConnected)

            val right = NatsBetweenClient(scope).apply { setCredentials(LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS) }
            right.connect(server.wsUrl)
            assertTrue(right.isConnected)
            right.disconnect()
        } finally {
            scope.cancel()
            server.close()
        }
    }
}
