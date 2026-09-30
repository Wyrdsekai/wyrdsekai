package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone that pairs while the app runs joins its home's bus then, not at the
 * next start; one away from home keeps trying; a new login replaces the old.
 */
class HomeBusLinkTest {

    private val t1 = HomeBusLink.Target("wss://192.0.2.5:27223", "phone-1", "pw1")
    private val t2 = HomeBusLink.Target("wss://192.0.2.5:27223", "phone-2", "pw2")

    @Test
    fun aPairingMidSessionJoinsTheBusThen() = runTest {
        var target: HomeBusLink.Target? = null
        var notPaired = 0
        val attached = mutableListOf<String>()
        val link = HomeBusLink(
            scope = this,
            target = { target },
            open = { InMemoryBetweenClient().apply { connect(it.url) } },
            attach = { _, t -> attached += t.user },
            onNotPaired = { notPaired++ },
        )
        link.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, notPaired)
        assertTrue(attached.isEmpty())

        // The consent toggle's pairing saved a bus login; the pairing signal calls start().
        target = t1
        link.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("phone-1"), attached)
        assertEquals(t1, link.attachedAs)

        // Another pairing signal with the same login changes nothing.
        link.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("phone-1"), attached)
        link.stop()
    }

    @Test
    fun awayFromHomeItKeepsTryingAndSaysSoOnce() = runTest {
        var reachable = false
        var waiting = 0
        val attached = mutableListOf<String>()
        val link = HomeBusLink(
            scope = this,
            target = { t1 },
            open = {
                if (!reachable) throw IllegalStateException("no route to host")
                InMemoryBetweenClient().apply { connect(it.url) }
            },
            attach = { _, t -> attached += t.user },
            onWaiting = { waiting++ },
            retryMs = 60_000,
        )
        link.start()
        testScheduler.advanceTimeBy(150_000)
        assertEquals(1, waiting)
        assertTrue(attached.isEmpty())
        reachable = true
        testScheduler.advanceTimeBy(61_000)
        assertEquals(listOf("phone-1"), attached)
        assertEquals(1, waiting)
        link.stop()
    }

    @Test
    fun aNewLoginReplacesTheOldLink() = runTest {
        var target = t1
        val clients = mutableListOf<InMemoryBetweenClient>()
        val link = HomeBusLink(
            scope = this,
            target = { target },
            open = { InMemoryBetweenClient().apply { connect(it.url); clients += this } },
            attach = { _, _ -> },
        )
        link.start()
        testScheduler.advanceUntilIdle()
        target = t2
        link.start()
        testScheduler.advanceUntilIdle()
        assertEquals(t2, link.attachedAs)
        assertFalse(clients[0].isConnected, "the old login's link is closed")
        assertTrue(clients[1].isConnected)
        link.stop()
        testScheduler.advanceUntilIdle()
        assertNull(link.attachedAs)
        assertFalse(clients[1].isConnected)
    }
}
