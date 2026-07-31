package org.wyrdsekai.app.engine.room

import kotlin.time.Clock
import org.wyrdsekai.app.engine.event.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomMemoryPolicyTest {

    private fun makeSaid(text: String) = WorldEvent.Said(
        roomId = "test",
        timestamp = Clock.System.now(),
        entityId = "player-1",
        entityName = "Alice",
        text = text,
    )

    @Test
    fun defaultPolicyHasCorrectSizes() {
        val policy = RoomMemoryPolicy.default()
        // Default hot=10, warm=20 — verify by filling up to boundary
        // Add 10 events: all should be in hot
        repeat(10) { policy.add(makeSaid("msg-$it")) }
        assertEquals(10, policy.hotEvents().size)
        assertEquals(0, policy.warmEvents().size)

        // 11th event should push one to warm
        policy.add(makeSaid("msg-10"))
        assertEquals(10, policy.hotEvents().size)
        assertEquals(1, policy.warmEvents().size)
    }

    @Test
    fun addToHotBuffer() {
        val policy = RoomMemoryPolicy.default()
        val event = makeSaid("Hello")
        policy.add(event)
        assertEquals(1, policy.hotEvents().size)
        assertEquals("Hello", policy.hotEvents().first().text)
    }

    @Test
    fun hotOverflowsToWarm() {
        val policy = RoomMemoryPolicy(hotSize = 3, warmSize = 5)

        repeat(5) { policy.add(makeSaid("msg-$it")) }

        // Hot should have the last 3
        assertEquals(3, policy.hotEvents().size)
        assertEquals("msg-2", policy.hotEvents()[0].text)
        assertEquals("msg-3", policy.hotEvents()[1].text)
        assertEquals("msg-4", policy.hotEvents()[2].text)

        // Warm should have the first 2 that overflowed
        assertEquals(2, policy.warmEvents().size)
        assertEquals("msg-0", policy.warmEvents()[0].text)
        assertEquals("msg-1", policy.warmEvents()[1].text)
    }

    @Test
    fun getHotEventsReturnsCorrectCount() {
        val policy = RoomMemoryPolicy.default()
        policy.add(makeSaid("one"))
        policy.add(makeSaid("two"))
        policy.add(makeSaid("three"))
        assertEquals(3, policy.hotEvents().size)
    }

    @Test
    fun clearResetsBuffers() {
        val policy = RoomMemoryPolicy(hotSize = 2, warmSize = 2)
        repeat(4) { policy.add(makeSaid("msg-$it")) }

        assertTrue(policy.hotEvents().isNotEmpty())
        assertTrue(policy.warmEvents().isNotEmpty())

        policy.clear()

        assertTrue(policy.hotEvents().isEmpty())
        assertTrue(policy.warmEvents().isEmpty())
        assertTrue(policy.allEvents().isEmpty())
    }
}
