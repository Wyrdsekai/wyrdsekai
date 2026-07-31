package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [OfflineQueue] — the persistence-backed queue for complex inference
 * requests that can't be processed when the household is unreachable.
 *
 * Uses a temp directory for file-backed persistence. Placed in desktopTest
 * because OfflineQueue uses java.io.File directly.
 */
class OfflineQueueTest {

    private lateinit var tmpDir: File

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-oq-test-${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun createQueue(): OfflineQueue = OfflineQueue(tmpDir.absolutePath)

    // ── Test 1: enqueue_and_pending ──

    @Test
    fun enqueue_and_pending() = runTest {
        val queue = createQueue()
        queue.enqueue("Tell me about quantum physics", "Alice", "nexus")
        queue.enqueue("Explain string theory", "Alice", "nexus")
        queue.enqueue("What is dark matter?", "Bob", "lab")

        val pending = queue.pending()
        assertEquals(3, pending.size, "Should have 3 pending items")
        assertEquals("Tell me about quantum physics", pending[0].triggerText)
        assertEquals("Explain string theory", pending[1].triggerText)
        assertEquals("What is dark matter?", pending[2].triggerText)
    }

    // ── Test 2: complete_removes_item ──

    @Test
    fun complete_removes_item() = runTest {
        val queue = createQueue()
        queue.enqueue("First question", "Alice", "nexus")
        queue.enqueue("Second question", "Bob", "nexus")

        val items = queue.pending()
        assertEquals(2, items.size)

        // Complete the first item
        queue.complete(items[0].triggerId)

        val remaining = queue.pending()
        assertEquals(1, remaining.size, "Should have 1 item after completing first")
        assertEquals("Second question", remaining[0].triggerText)
    }

    // ── Test 3: clear_removes_all ──

    @Test
    fun clear_removes_all() = runTest {
        val queue = createQueue()
        queue.enqueue("Question 1", "Alice", "nexus")
        queue.enqueue("Question 2", "Bob", "nexus")
        queue.enqueue("Question 3", "Charlie", "nexus")

        queue.clear()

        val pending = queue.pending()
        assertTrue(pending.isEmpty(), "Pending should be empty after clear()")
    }

    // ── Test 4: size_reflects_count ──

    @Test
    fun size_reflects_count() = runTest {
        val queue = createQueue()
        queue.enqueue("Q1", "Alice", "nexus")
        queue.enqueue("Q2", "Bob", "nexus")

        assertEquals(2, queue.size(), "Size should be 2 after 2 enqueues")

        val items = queue.pending()
        queue.complete(items[0].triggerId)

        assertEquals(1, queue.size(), "Size should be 1 after completing one item")
    }

    // ── Test 5: max_50_items ──

    @Test
    fun max_50_items() = runTest {
        val queue = createQueue()
        for (i in 1..55) {
            queue.enqueue("Question $i", "User", "nexus")
        }

        assertEquals(50, queue.size(), "Queue should cap at 50 items")

        // The oldest 5 should have been dropped — first item should be "Question 6"
        val pending = queue.pending()
        assertEquals("Question 6", pending[0].triggerText,
            "Oldest items should be dropped when exceeding cap")
        assertEquals("Question 55", pending[49].triggerText,
            "Newest item should be last")
    }

    // ── Test 6: persists_across_instances ──

    @Test
    fun persists_across_instances() = runTest {
        val queueA = createQueue()
        queueA.enqueue("Remember this", "Alice", "nexus")
        queueA.enqueue("And this too", "Bob", "lab")

        // Create a new queue instance pointing to the same directory
        val queueB = OfflineQueue(tmpDir.absolutePath)
        val pending = queueB.pending()
        assertEquals(2, pending.size, "New instance should load persisted items")
        assertEquals("Remember this", pending[0].triggerText)
        assertEquals("And this too", pending[1].triggerText)
    }

    // ── Test 7: empty_queue_pending_returns_empty ──

    @Test
    fun empty_queue_pending_returns_empty() = runTest {
        val queue = createQueue()
        val pending = queue.pending()
        assertTrue(pending.isEmpty(), "Pending on empty queue should return empty list")
    }

    // ── Test 8: complete_nonexistent_id_is_noop ──

    @Test
    fun complete_nonexistent_id_is_noop() = runTest {
        val queue = createQueue()
        queue.enqueue("Only item", "Alice", "nexus")

        // Complete a non-existent ID — should not crash or remove anything
        queue.complete("bogus-id-that-does-not-exist")

        assertEquals(1, queue.size(), "Completing non-existent ID should not remove any items")
        assertEquals("Only item", queue.pending()[0].triggerText)
    }

    // ── Edge cases ──

    @Test
    fun enqueue_preserves_entity_name_and_room_id() = runTest {
        val queue = createQueue()
        queue.enqueue("What is life?", "Aristotle", "agora")

        val item = queue.pending()[0]
        assertEquals("Aristotle", item.triggerEntityName)
        assertEquals("agora", item.roomId)
    }

    @Test
    fun clear_persists() = runTest {
        val queue = createQueue()
        queue.enqueue("Temp item", "User", "nexus")
        queue.clear()

        // New instance should see empty queue
        val fresh = OfflineQueue(tmpDir.absolutePath)
        assertTrue(fresh.pending().isEmpty(),
            "Clear should persist — new instance sees empty queue")
    }

    @Test
    fun trigger_ids_are_unique() = runTest {
        val queue = createQueue()
        queue.enqueue("Q1", "User", "nexus")
        queue.enqueue("Q2", "User", "nexus")

        val pending = queue.pending()
        assertTrue(pending[0].triggerId != pending[1].triggerId,
            "Trigger IDs should be unique: ${pending[0].triggerId} vs ${pending[1].triggerId}")
    }
}
