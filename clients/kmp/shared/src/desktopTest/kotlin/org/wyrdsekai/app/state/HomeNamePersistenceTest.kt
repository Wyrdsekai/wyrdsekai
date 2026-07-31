package org.wyrdsekai.app.state

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for home name persistence via the desktop TokenStore.
 *
 * Uses Java Preferences API under the hood. Each test clears the
 * relevant preference key to avoid cross-test contamination.
 */
class HomeNamePersistenceTest {

    private val store = TokenStore()

    @AfterTest
    fun tearDown() {
        // Clean up the home name preference to avoid polluting other tests.
        // We access the prefs directly since clear() intentionally does NOT
        // clear user preferences like home name.
        val prefs = Preferences.userNodeForPackage(TokenStore::class.java)
        prefs.remove("wyrd_home_name")
        prefs.flush()
    }

    @Test
    fun saveAndLoadHomeName() {
        store.saveHomeName("My Den")
        val loaded = store.loadHomeName()
        assertEquals("My Den", loaded)
    }

    @Test
    fun loadHomeNameDefaultNull() {
        // Fresh state (tearDown ensures cleanup), loadHomeName should return null
        val loaded = store.loadHomeName()
        assertNull(loaded, "loadHomeName() on fresh store should return null")
    }

    @Test
    fun clearDoesNotClearHomeName() {
        // Home name is a user preference, not a credential.
        // clear() only removes token, server URL, and username.
        store.saveHomeName("My Den")
        store.clear()
        val loaded = store.loadHomeName()
        assertEquals("My Den", loaded,
            "clear() should NOT remove home name (it's a user preference, not a credential)")
    }

    @Test
    fun overwriteHomeName() {
        store.saveHomeName("First Name")
        store.saveHomeName("Second Name")
        val loaded = store.loadHomeName()
        assertEquals("Second Name", loaded,
            "Saving a new home name should overwrite the old one")
    }

    @Test
    fun unicodeHomeName() {
        store.saveHomeName("\u304a\u3046\u3061") // "ouchi" in Japanese
        val loaded = store.loadHomeName()
        assertEquals("\u304a\u3046\u3061", loaded,
            "Unicode home names should persist correctly")
    }

    @Test
    fun emptyStringHomeName() {
        store.saveHomeName("")
        val loaded = store.loadHomeName()
        assertEquals("", loaded,
            "Empty string home name should persist as empty string, not null")
    }
}
