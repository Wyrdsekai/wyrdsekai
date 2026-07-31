package org.wyrdsekai.app.engine.study

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.content.Context
import java.util.UUID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SQLite + FTS5 implementation of [StudyStore] for Android.
 *
 * Schema:
 * - `study_items` — main table with all fields
 * - `study_fts` — FTS5 virtual table for full-text search (title + content)
 * - Triggers keep FTS in sync with the main table on insert/update/delete
 *
 * Uses `unicode61` tokenizer for reasonable CJK support. At phone scale
 * (hundreds to low thousands of entries), this is efficient and reliable.
 *
 * Thread-safe: SQLiteOpenHelper handles locking. All public methods are
 * suspend but don't do IO suspension — they run on the caller's dispatcher.
 * Wrap in Dispatchers.IO at the call site if needed.
 */
class SqliteStudyStore(
    private val dataDir: String,
) : StudyStore {

    private val db: SQLiteDatabase by lazy { openOrCreate() }

    // Vector-clock slot key for local writes. Set to the
    // Between node id when sync is wired; 'local' until then.
    private var deviceId: String = "local"

    // Whether the framework SQLite supports FTS5 (set during createSchema).
    // False → ftsSearch degrades to a LIKE scan (fine at phone scale).
    private var ftsAvailable: Boolean = true

    private val clockJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val clockSerializer = MapSerializer(String.serializer(), Long.serializer())
    private fun clockToJson(m: Map<String, Long>): String =
        clockJson.encodeToString(clockSerializer, m)
    private fun parseClock(s: String?): Map<String, Long> =
        if (s.isNullOrBlank()) emptyMap()
        else try { clockJson.decodeFromString(clockSerializer, s) } catch (_: Exception) { emptyMap() }

    /** Set the vector-clock slot key (the Between node id) for local writes. */
    override fun setDeviceId(id: String) {
        if (id.isNotBlank()) deviceId = id
    }

    private fun openOrCreate(): SQLiteDatabase {
        val dbFile = java.io.File(dataDir, "study.db")
        dbFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createSchema(database)
        return database
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS study_items (
                _rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                id TEXT UNIQUE NOT NULL,
                user_did TEXT NOT NULL,
                item_type TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL,
                collection TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_study_user_type
            ON study_items(user_did, item_type)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_study_user_ts
            ON study_items(user_did, timestamp DESC)
        """.trimIndent())

        // FTS5 virtual table — external content mode synced via triggers.
        // The FRAMEWORK SQLite on some devices/emulator images is built WITHOUT
        // the fts5 module ("no such module: fts5") — and because the db is opened
        // lazily, a throw here used to poison EVERY store call (journal writes,
        // study sync, counts). FTS is an optimization: degrade to LIKE search
        // instead of dying.
        ftsAvailable = try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS study_fts USING fts5(
                    title, content,
                    content='study_items',
                    content_rowid='_rowid',
                    tokenize='unicode61'
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS study_ai AFTER INSERT ON study_items BEGIN
                    INSERT INTO study_fts(rowid, title, content)
                    VALUES (new._rowid, new.title, new.content);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS study_ad AFTER DELETE ON study_items BEGIN
                    INSERT INTO study_fts(study_fts, rowid, title, content)
                    VALUES ('delete', old._rowid, old.title, old.content);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS study_au AFTER UPDATE ON study_items BEGIN
                    INSERT INTO study_fts(study_fts, rowid, title, content)
                    VALUES ('delete', old._rowid, old.title, old.content);
                    INSERT INTO study_fts(rowid, title, content)
                    VALUES (new._rowid, new.title, new.content);
                END
            """.trimIndent())
            true
        } catch (e: android.database.sqlite.SQLiteException) {
            android.util.Log.w("SqliteStudyStore",
                "FTS5 unavailable (${e.message}) — falling back to LIKE search")
            false
        }

        ensureSyncColumns(db)
    }

    /**
     * Add the CRDT sync columns to an existing study_items table.
     * CREATE TABLE IF NOT EXISTS never adds columns, so upgrade in place — guarded by
     * PRAGMA table_info so it's a no-op once present.
     */
    private fun ensureSyncColumns(db: SQLiteDatabase) {
        val have = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(study_items)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) have.add(c.getString(nameIdx))
        }
        if (!have.contains("vector_clock")) {
            db.execSQL("ALTER TABLE study_items ADD COLUMN vector_clock TEXT")
        }
        if (!have.contains("last_modified_by")) {
            db.execSQL("ALTER TABLE study_items ADD COLUMN last_modified_by TEXT")
        }
        if (!have.contains("deleted")) {
            db.execSQL("ALTER TABLE study_items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ── StudyStore implementation ────────────────────────────────────────

    override suspend fun writeJournal(userDid: String, content: String, isPrivate: Boolean): StudyItem {
        val item = StudyItem(
            id = "si-${UUID.randomUUID()}",
            userDid = userDid,
            itemType = if (isPrivate) StudyItem.TYPE_JOURNAL_PRIVATE else StudyItem.TYPE_JOURNAL,
            title = content.lineSequence().firstOrNull()?.take(120) ?: "",
            content = content,
            timestamp = System.currentTimeMillis(),
        ).tick(deviceId)
        insertItem(item)
        return item
    }

    override suspend fun editItem(id: String, newContent: String): StudyItem? {
        val existing = getItem(id) ?: return null
        // Tick THIS device's clock slot so the edit propagates as strictly newer.
        val updated = existing.copy(
            content = newContent,
            title = newContent.lineSequence().firstOrNull()?.take(120) ?: existing.title,
            version = existing.version + 1,
            timestamp = System.currentTimeMillis(),
        ).tick(deviceId)
        val cv = ContentValues().apply {
            put("content", updated.content)
            put("title", updated.title)
            put("version", updated.version)
            put("timestamp", updated.timestamp)
            put("vector_clock", clockToJson(updated.vectorClock))
            put("last_modified_by", updated.lastModifiedBy)
        }
        db.update("study_items", cv, "id = ?", arrayOf(id))
        return updated
    }

    override suspend fun searchJournal(userDid: String, query: String, limit: Int): List<StudyItem> {
        return ftsSearch(userDid, query, limit, journalOnly = true)
    }

    override suspend fun recentJournal(userDid: String, limit: Int): List<StudyItem> {
        val cursor = db.rawQuery(
            """SELECT id, user_did, item_type, title, content, collection, timestamp, version,
                      vector_clock, last_modified_by, deleted
               FROM study_items
               WHERE user_did = ? AND item_type IN (?, ?)
               ORDER BY timestamp DESC LIMIT ?""",
            arrayOf(userDid, StudyItem.TYPE_JOURNAL, StudyItem.TYPE_JOURNAL_PRIVATE, limit.toString()),
        )
        return cursor.use { readItems(it) }
    }

    override suspend fun addNote(userDid: String, content: String): StudyItem {
        val item = StudyItem(
            id = "si-${UUID.randomUUID()}",
            userDid = userDid,
            itemType = StudyItem.TYPE_NOTE,
            title = content.lineSequence().firstOrNull()?.take(120) ?: "",
            content = content,
            timestamp = System.currentTimeMillis(),
        ).tick(deviceId)
        insertItem(item)
        return item
    }

    override suspend fun pin(userDid: String, title: String, snippet: String, sourceUrl: String): StudyItem {
        val item = StudyItem(
            id = "si-${UUID.randomUUID()}",
            userDid = userDid,
            itemType = StudyItem.TYPE_PINBOARD,
            title = title,
            content = if (sourceUrl.isNotEmpty()) "$snippet\n\nSource: $sourceUrl" else snippet,
            timestamp = System.currentTimeMillis(),
        ).tick(deviceId)
        insertItem(item)
        return item
    }

    override suspend fun searchAll(userDid: String, query: String, limit: Int): List<StudyItem> {
        return ftsSearch(userDid, query, limit, journalOnly = false)
    }

    override suspend fun getItem(id: String): StudyItem? {
        val cursor = db.rawQuery(
            """SELECT id, user_did, item_type, title, content, collection, timestamp, version,
                      vector_clock, last_modified_by, deleted
               FROM study_items WHERE id = ?""",
            arrayOf(id),
        )
        return cursor.use { readItems(it).firstOrNull() }
    }

    override suspend fun deleteItem(id: String): Boolean {
        return db.delete("study_items", "id = ?", arrayOf(id)) > 0
    }

    override suspend fun putItem(item: StudyItem) {
        // #5 (2026-07-19) — upsert verbatim (CONFLICT_REPLACE) so a synced-in
        // item is actually persisted. NOTE: the on-disk schema persists the core
        // columns only; the vector-clock/deleted sync columns are a separate
        // pre-existing gap, but the item's CONTENT is no longer dropped.
        insertItem(item)
    }

    override suspend fun rekeyUserDid(fromUserDid: String, toUserDid: String): Int {
        if (fromUserDid.isBlank() || fromUserDid == toUserDid) return 0
        val cv = ContentValues().apply { put("user_did", toUserDid) }
        return db.update("study_items", cv, "user_did = ?", arrayOf(fromUserDid))
    }

    override suspend fun count(userDid: String): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM study_items WHERE user_did = ?",
            arrayOf(userDid),
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun insertItem(item: StudyItem) {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("user_did", item.userDid)
            put("item_type", item.itemType)
            put("title", item.title)
            put("content", item.content)
            put("collection", item.collection)
            put("timestamp", item.timestamp)
            put("version", item.version)
            // CRDT sync columns — persisted verbatim (a synced-in item keeps its own
            // clock; a local write ticked its slot before calling insertItem).
            put("vector_clock", clockToJson(item.vectorClock))
            put("last_modified_by", item.lastModifiedBy)
            put("deleted", if (item.deleted) 1 else 0)
        }
        db.insertWithOnConflict("study_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * FTS5 search joining back to main table for user filtering.
     * Uses MATCH for full-text queries; falls back to LIKE for short queries.
     */
    private fun ftsSearch(userDid: String, query: String, limit: Int, journalOnly: Boolean): List<StudyItem> {
        val sanitized = query.replace("\"", "").trim()
        if (sanitized.isEmpty()) {
            return if (journalOnly) emptyList()
            else emptyList() // or recentAll
        }

        val typeFilter = if (journalOnly) {
            "AND s.item_type IN ('${StudyItem.TYPE_JOURNAL}', '${StudyItem.TYPE_JOURNAL_PRIVATE}')"
        } else ""

        // No FTS5 on this device → LIKE scan over title+content (phone scale).
        if (!ftsAvailable) {
            val like = "%$sanitized%"
            val cursor = db.rawQuery(
                """SELECT s.id, s.user_did, s.item_type, s.title, s.content, s.collection, s.timestamp, s.version,
                          s.vector_clock, s.last_modified_by, s.deleted
                   FROM study_items s
                   WHERE (s.title LIKE ? OR s.content LIKE ?) AND s.user_did = ? $typeFilter
                   ORDER BY s.timestamp DESC LIMIT ?""",
                arrayOf(like, like, userDid, limit.toString()),
            )
            return cursor.use { readItems(it) }
        }

        // FTS5 MATCH query — wrap in quotes for phrase-like matching
        val cursor = db.rawQuery(
            """SELECT s.id, s.user_did, s.item_type, s.title, s.content, s.collection, s.timestamp, s.version,
                      s.vector_clock, s.last_modified_by, s.deleted
               FROM study_items s
               INNER JOIN study_fts f ON s._rowid = f.rowid
               WHERE study_fts MATCH ? AND s.user_did = ? $typeFilter
               ORDER BY rank LIMIT ?""",
            arrayOf(sanitized, userDid, limit.toString()),
        )
        return cursor.use { readItems(it) }
    }

    private fun readItems(cursor: android.database.Cursor): List<StudyItem> {
        val items = mutableListOf<StudyItem>()
        while (cursor.moveToNext()) {
            val vc = if (cursor.columnCount > 8 && !cursor.isNull(8)) cursor.getString(8) else null
            val lmb = if (cursor.columnCount > 9 && !cursor.isNull(9)) cursor.getString(9) else ""
            val del = cursor.columnCount > 10 && cursor.getInt(10) != 0
            items.add(
                StudyItem(
                    id = cursor.getString(0),
                    userDid = cursor.getString(1),
                    itemType = cursor.getString(2),
                    title = cursor.getString(3),
                    content = cursor.getString(4),
                    collection = cursor.getString(5),
                    timestamp = cursor.getLong(6),
                    version = cursor.getInt(7),
                    vectorClock = parseClock(vc),
                    lastModifiedBy = lmb,
                    deleted = del,
                )
            )
        }
        return items
    }

    /** Close the database. Call on shutdown. */
    fun close() {
        if (db.isOpen) db.close()
    }
}
