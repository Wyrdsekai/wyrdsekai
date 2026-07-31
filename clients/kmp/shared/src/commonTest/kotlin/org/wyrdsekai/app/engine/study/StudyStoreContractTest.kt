package org.wyrdsekai.app.engine.study

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for StudyStore — verifies the interface contract
 * against InMemoryStudyStore. Any correct implementation (SQLite, AsyncStorage)
 * should pass these same tests.
 */
class StudyStoreContractTest {

    private fun createStore(): StudyStore = InMemoryStudyStore()

    @Test
    fun `write and retrieve journal entry`() = runTest {
        val store = createStore()
        val item = store.writeJournal("user1", "Had a productive day")
        assertNotNull(item.id)
        assertEquals("user1", item.userDid)
        assertEquals("journal", item.itemType)
        assertEquals("Had a productive day", item.content)
        assertEquals(1, item.version)

        val retrieved = store.getItem(item.id)
        assertNotNull(retrieved)
        assertEquals(item.id, retrieved.id)
        assertEquals(item.content, retrieved.content)
    }

    @Test
    fun `write private journal entry`() = runTest {
        val store = createStore()
        val item = store.writeJournal("user1", "Secret thoughts", isPrivate = true)
        assertEquals("journal_private", item.itemType)
    }

    @Test
    fun `recent journal returns newest first`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "First entry")
        store.writeJournal("user1", "Second entry")
        store.writeJournal("user1", "Third entry")

        val recent = store.recentJournal("user1", limit = 2)
        assertEquals(2, recent.size)
        assertEquals("Third entry", recent[0].content)
        assertEquals("Second entry", recent[1].content)
    }

    @Test
    fun `search journal finds matching entries`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "Weather was sunny today")
        store.writeJournal("user1", "Rainy day, stayed inside")
        store.writeJournal("user1", "Sunny again, went for a walk")

        val results = store.searchJournal("user1", "sunny")
        assertEquals(2, results.size)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "Meeting with ALICE about project")

        val results = store.searchJournal("user1", "alice")
        assertEquals(1, results.size)
    }

    @Test
    fun `edit item increments version`() = runTest {
        val store = createStore()
        val item = store.writeJournal("user1", "Draft entry")
        assertEquals(1, item.version)

        val edited = store.editItem(item.id, "Revised entry")
        assertNotNull(edited)
        assertEquals(2, edited.version)
        assertEquals("Revised entry", edited.content)
    }

    @Test
    fun `edit nonexistent item returns null`() = runTest {
        val store = createStore()
        val result = store.editItem("nonexistent-id", "content")
        assertNull(result)
    }

    @Test
    fun `add note`() = runTest {
        val store = createStore()
        val note = store.addNote("user1", "Remember to buy milk")
        assertEquals("note", note.itemType)
        assertEquals("Remember to buy milk", note.content)
    }

    @Test
    fun `pin reference`() = runTest {
        val store = createStore()
        val pin = store.pin("user1", "Interesting Article", "Summary text", "https://example.com")
        assertEquals("pinboard", pin.itemType)
        assertEquals("Interesting Article", pin.title)
        assertTrue(pin.content.contains("Summary text"))
        assertTrue(pin.content.contains("https://example.com"))
    }

    @Test
    fun `search all crosses item types`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "Journal about cooking pasta")
        store.addNote("user1", "Pasta recipe: boil 8 minutes")

        val results = store.searchAll("user1", "pasta")
        assertEquals(2, results.size)
    }

    @Test
    fun `delete item`() = runTest {
        val store = createStore()
        val item = store.writeJournal("user1", "To be deleted")
        assertTrue(store.deleteItem(item.id))
        assertNull(store.getItem(item.id))
    }

    @Test
    fun `delete nonexistent returns false`() = runTest {
        val store = createStore()
        assertEquals(false, store.deleteItem("nonexistent"))
    }

    @Test
    fun `count returns items for user`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "Entry 1")
        store.writeJournal("user1", "Entry 2")
        store.addNote("user1", "Note 1")
        store.writeJournal("user2", "Other user entry")

        assertEquals(3, store.count("user1"))
        assertEquals(1, store.count("user2"))
    }

    @Test
    fun `user isolation - cannot see other users entries`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "User 1 private thought")
        store.writeJournal("user2", "User 2 private thought")

        val user1Journal = store.recentJournal("user1")
        assertEquals(1, user1Journal.size)
        assertEquals("User 1 private thought", user1Journal[0].content)

        val user2Journal = store.recentJournal("user2")
        assertEquals(1, user2Journal.size)
        assertEquals("User 2 private thought", user2Journal[0].content)
    }

    @Test
    fun `title extracted from first line`() = runTest {
        val store = createStore()
        val item = store.writeJournal("user1", "First line title\nBody text here\nMore body")
        assertEquals("First line title", item.title)
    }

    @Test
    fun `recent includes both shared and private`() = runTest {
        val store = createStore()
        store.writeJournal("user1", "Shared entry")
        store.writeJournal("user1", "Private entry", isPrivate = true)

        val recent = store.recentJournal("user1")
        assertEquals(2, recent.size)
    }
}
