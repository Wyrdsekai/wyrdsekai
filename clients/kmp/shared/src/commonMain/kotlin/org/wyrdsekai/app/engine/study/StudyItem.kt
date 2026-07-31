package org.wyrdsekai.app.engine.study

import kotlinx.serialization.Serializable

/**
 * A single item in the player's Study — journal entry, note, pin, or document.
 *
 * Mirrors the server-side Study item schema (WyrdLuceneStore fields) in a
 * serializable form suitable for SQLite storage, Between sync, and UI display.
 */
@Serializable
data class StudyItem(
    /** Unique identifier (e.g. "si-{uuid}"). */
    val id: String,
    /** Owner's DID. */
    val userDid: String,
    /** Item type: journal, journal_private, note, pinboard, document, voice_memo. */
    val itemType: String,
    /** Title or first line of content. */
    val title: String = "",
    /** Full text content. */
    val content: String,
    /** Sub-collection name (e.g. "taxes-2025"). Empty string = default. */
    val collection: String = "",
    /** Creation/last-modified timestamp (epoch millis). */
    val timestamp: Long,
    /** Edit version number. Increments on each edit; old versions archived. */
    val version: Int = 1,

    // ── Sync fields ──────────────────────────────────────────────────
    /** Vector clock: {deviceId → version}. Each device increments its own slot on write. */
    val vectorClock: Map<String, Long> = emptyMap(),
    /** Device ID that last modified this item. */
    val lastModifiedBy: String = "",
    /** Non-empty = unresolved conflict. Contains the competing versions. */
    val conflictVersions: List<StudyItem> = emptyList(),
    /** Soft-delete tombstone. True = deleted, replicated to peers then purged. */
    val deleted: Boolean = false,
) {
    /** Increment this device's slot in the vector clock. */
    fun tick(deviceId: String): StudyItem {
        val clock = vectorClock.toMutableMap()
        clock[deviceId] = (clock[deviceId] ?: 0L) + 1
        return copy(vectorClock = clock, lastModifiedBy = deviceId)
    }

    companion object {
        const val TYPE_JOURNAL = "journal"
        const val TYPE_JOURNAL_PRIVATE = "journal_private"
        const val TYPE_NOTE = "note"
        const val TYPE_PINBOARD = "pinboard"
        const val TYPE_DOCUMENT = "document"
        const val TYPE_VOICE_MEMO = "voice_memo"
    }
}
