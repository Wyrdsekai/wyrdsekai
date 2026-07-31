package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Standalone-mode conformance probes (KMP). Mirrors
 * {@code clients/rn/__tests__/engine/standalone-conformance.test.ts}.
 *
 * <p>Exercises {@link PhoneNode} directly to verify
 * hold when the phone runs
 * standalone (no paired server). Pre-fix these verbs fell into the
 * "Huh?" fallback in {@code LocalRoomScreen} — release blocker for
 * cloud-API-key users.</p>
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeStandaloneConformanceTest {

    private fun makeNode(scope: kotlinx.coroutines.CoroutineScope) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = null,
    )

    // §2.2 — examine

    @Test
    fun examineSelfReturnsPlayerName() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.examine("me")
        assertNotNull(result)
        assertEquals(node.playerName, result.name)
    }

    @Test
    fun examineUnknownReturnsNull() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.examine("zzzbobcatfloop")
        assertNull(result)
    }

    @Test
    fun examineEmptyReturnsNull() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        assertNull(node.examine(""))
        assertNull(node.examine("   "))
    }

    @Test
    fun examineResolvesRoomObjectByName() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val room = node.currentRoom() ?: return@runTest
        val firstObject = room.state.value.objects.values.firstOrNull() ?: return@runTest
        val result = node.examine(firstObject.name)
        assertNotNull(result)
        assertEquals(firstObject.name, result.name)
    }

    // §7.4 — rename

    @Test
    fun renameMeUpdatesPlayerName() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.rename("Alice")
        assertTrue(result is PhoneNode.RenameResult.Ok)
        assertEquals("Alice", (result as PhoneNode.RenameResult.Ok).newName)
        assertEquals("Alice", node.playerName)
    }

    @Test
    fun renameMeReflectsInSubsequentExamineMe() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        node.rename("Bob")
        val result = node.examine("me")
        assertNotNull(result)
        assertEquals("Bob", result.name)
    }

    @Test
    fun renameMeEmptyRejected() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.rename("")
        assertTrue(result is PhoneNode.RenameResult.Rejected)
        assertTrue((result as PhoneNode.RenameResult.Rejected).message.lowercase().contains("usage"))
    }

    @Test
    fun renameMeTooLongRejected() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.rename("a".repeat(41))
        assertTrue(result is PhoneNode.RenameResult.Rejected)
    }

    @Test
    fun renameMeControlCharsRejected() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        val result = node.rename("Alice\nBob")
        assertTrue(result is PhoneNode.RenameResult.Rejected)
    }

    @Test
    fun defaultPlayerNameIsSet() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        assertFalse(node.playerName.isBlank())
    }

    // §4 — drop

    @Test
    fun dropDispatchesWithoutThrowing() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(200)
        // No assertion on inventory state — we just exercise the path so the
        // test fails if PhoneNode.drop is removed or throws.
        node.drop("player-local", "compass")
        advanceTimeBy(100)
    }
}
