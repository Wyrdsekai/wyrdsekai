package org.wyrdsekai.app.engine.study

import kotlin.test.Test
import kotlin.test.assertEquals

class VectorClockTest {

    @Test
    fun `equal clocks`() {
        val a = mapOf("d1" to 3L, "d2" to 5L)
        val b = mapOf("d1" to 3L, "d2" to 5L)
        assertEquals(VectorClock.Relation.EQUAL, VectorClock.compare(a, b))
    }

    @Test
    fun `a dominates b`() {
        val a = mapOf("d1" to 4L, "d2" to 5L)
        val b = mapOf("d1" to 3L, "d2" to 5L)
        assertEquals(VectorClock.Relation.DOMINATES, VectorClock.compare(a, b))
    }

    @Test
    fun `b dominates a`() {
        val a = mapOf("d1" to 3L, "d2" to 5L)
        val b = mapOf("d1" to 3L, "d2" to 6L)
        assertEquals(VectorClock.Relation.DOMINATED, VectorClock.compare(a, b))
    }

    @Test
    fun `concurrent - both have higher slots`() {
        val a = mapOf("d1" to 4L, "d2" to 5L)
        val b = mapOf("d1" to 3L, "d2" to 6L)
        assertEquals(VectorClock.Relation.CONCURRENT, VectorClock.compare(a, b))
    }

    @Test
    fun `missing key treated as zero`() {
        val a = mapOf("d1" to 3L)
        val b = mapOf("d1" to 3L, "d2" to 1L)
        assertEquals(VectorClock.Relation.DOMINATED, VectorClock.compare(a, b))
    }

    @Test
    fun `a has extra key - dominates`() {
        val a = mapOf("d1" to 3L, "d2" to 1L)
        val b = mapOf("d1" to 3L)
        assertEquals(VectorClock.Relation.DOMINATES, VectorClock.compare(a, b))
    }

    @Test
    fun `empty clocks are equal`() {
        assertEquals(VectorClock.Relation.EQUAL, VectorClock.compare(emptyMap(), emptyMap()))
    }

    @Test
    fun `merge takes max of each slot`() {
        val a = mapOf("d1" to 4L, "d2" to 2L)
        val b = mapOf("d1" to 3L, "d2" to 5L, "d3" to 1L)
        val merged = VectorClock.merge(a, b)
        assertEquals(mapOf("d1" to 4L, "d2" to 5L, "d3" to 1L), merged)
    }

    @Test
    fun `tick increments device slot`() {
        val item = StudyItem(
            id = "test", userDid = "u1", itemType = "journal",
            content = "hello", timestamp = 1000L,
            vectorClock = mapOf("phone" to 2L),
        )
        val ticked = item.tick("phone")
        assertEquals(3L, ticked.vectorClock["phone"])
        assertEquals("phone", ticked.lastModifiedBy)
    }

    @Test
    fun `tick adds new device`() {
        val item = StudyItem(
            id = "test", userDid = "u1", itemType = "journal",
            content = "hello", timestamp = 1000L,
            vectorClock = mapOf("phone" to 2L),
        )
        val ticked = item.tick("desktop")
        assertEquals(2L, ticked.vectorClock["phone"])
        assertEquals(1L, ticked.vectorClock["desktop"])
    }
}
