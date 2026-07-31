package org.wyrdsekai.app.engine.study

/**
 * Local Study persistence — the phone-side equivalent of the server's StudyService.
 *
 * Provides journal, notes, pinboard, and full-text search over the player's
 * personal Study items. Implementations use platform-specific storage
 * (SQLite + FTS5 on Android, file-backed on desktop).
 *
 * All methods are suspend for coroutine compatibility. The userDid parameter
 * exists for future multi-user/sync scenarios; on phone, it's typically the
 * companion's owner DID.
 */
interface StudyStore {

    /** Write a journal entry (shared or private). Returns the created item. */
    suspend fun writeJournal(userDid: String, content: String, isPrivate: Boolean = false): StudyItem

    /** Edit an existing item's content. Increments version. Returns updated item, or null if not found. */
    suspend fun editItem(id: String, newContent: String): StudyItem?

    /** Full-text search over journal entries for a user. */
    suspend fun searchJournal(userDid: String, query: String, limit: Int = 20): List<StudyItem>

    /** Most recent journal entries for a user, ordered newest-first. */
    suspend fun recentJournal(userDid: String, limit: Int = 20): List<StudyItem>

    /** Add a quick note. */
    suspend fun addNote(userDid: String, content: String): StudyItem

    /** Pin a reference (e.g. Library bookmark). */
    suspend fun pin(userDid: String, title: String, snippet: String, sourceUrl: String = ""): StudyItem

    /** Full-text search across all item types for a user. */
    suspend fun searchAll(userDid: String, query: String, limit: Int = 20): List<StudyItem>

    /** Get a single item by ID, or null. */
    suspend fun getItem(id: String): StudyItem?

    /**
     * #5 (2026-07-19 OSS hardening) — upsert a full item verbatim. Used by the
     * sync layer to store an incoming item this device does not yet have.
     * Without it, [StudySyncLayer.mergeIncoming] counted a new remote item as
     * "merged" but never persisted it, silently dropping synced Study/journal
     * entries (data loss). Replaces the whole row (id is the key).
     */
    suspend fun putItem(item: StudyItem)

    /** Delete an item by ID. Returns true if it existed and was deleted. */
    suspend fun deleteItem(id: String): Boolean

    /** Count of all items for a user. */
    suspend fun count(userDid: String): Int

    /** Set the vector-clock slot key (Between node id) for local writes, if the
     *  implementation persists clocks. Default no-op for stores that don't. */
    fun setDeviceId(id: String) {}

    /** Re-key all items owned by [fromUserDid] to [toUserDid] — used once on first
     *  home-zone login so pre-account local notes follow the user to their account
     * Returns rows moved. Default no-op. */
    suspend fun rekeyUserDid(fromUserDid: String, toUserDid: String): Int = 0
}
