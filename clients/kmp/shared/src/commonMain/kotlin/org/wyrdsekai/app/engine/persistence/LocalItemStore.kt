package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.item.PhoneSoulItem
import org.wyrdsekai.app.platform.AppFiles

/**
 * Local item store for the phone client.
 *
 * Replaces the server's FamilyLocker on the phone side. Items are stored as
 * individual JSON files keyed by content-address hash, loaded lazily into
 * memory, and written through on store.
 */
interface LocalItemStore {
    /** Store an item (upserts by hash). */
    suspend fun store(item: PhoneSoulItem)

    /** Get an item by its content-address hash, or null. */
    suspend fun get(hash: String): PhoneSoulItem?

    /** Get all items matching a category (e.g. "memory", "aspect", "reagent"). */
    suspend fun byCategory(category: String): List<PhoneSoulItem>

    /** Get the first item matching a label, or null. */
    suspend fun byLabel(label: String): PhoneSoulItem?

    /** Get all stored items. */
    suspend fun all(): List<PhoneSoulItem>

    /** Remove an item by hash. */
    suspend fun remove(hash: String)
}

/**
 * File-backed implementation of [LocalItemStore].
 *
 * Stores items as JSON files in a `soul-items/` subdirectory under [dataDir].
 * Each file is named `{hash}.json`. Uses atomic writes via temp file + rename
 * (same pattern as [AndroidEventJournal][org.wyrdsekai.app.engine.persistence.AndroidEventJournal]).
 *
 * All items are loaded into memory on first access (lazy). Writes go through
 * to both the in-memory cache and the filesystem. This is fine for phone scale
 * (hundreds of items, not millions).
 *
 * @param dataDir Root data directory for the phone node (e.g. Android's filesDir).
 */
class FileBackedItemStore(dataDir: String) : LocalItemStore {

    private val itemDir = "$dataDir/soul-items"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /**
     * In-memory cache of all items, keyed by hash.
     * Populated lazily on first access via [ensureLoaded].
     */
    private var cache: MutableMap<String, PhoneSoulItem>? = null

    init {
        AppFiles.mkdirs(itemDir)
    }

    // --- Public API ---

    override suspend fun store(item: PhoneSoulItem) {
        val items = ensureLoaded()
        items[item.hash] = item
        writeItem(item)
    }

    override suspend fun get(hash: String): PhoneSoulItem? {
        return ensureLoaded()[hash]
    }

    override suspend fun byCategory(category: String): List<PhoneSoulItem> {
        return ensureLoaded().values.filter { it.category == category }
    }

    override suspend fun byLabel(label: String): PhoneSoulItem? {
        return ensureLoaded().values.firstOrNull { it.label == label }
    }

    override suspend fun all(): List<PhoneSoulItem> {
        return ensureLoaded().values.toList()
    }

    override suspend fun remove(hash: String) {
        val items = ensureLoaded()
        items.remove(hash)
        AppFiles.delete("$itemDir/$hash.json")
    }

    // --- Internal ---

    /**
     * Lazy-load all items from disk into the in-memory cache.
     * Only reads from disk on the first call; subsequent calls return the cache.
     */
    private fun ensureLoaded(): MutableMap<String, PhoneSoulItem> {
        cache?.let { return it }

        val loaded = mutableMapOf<String, PhoneSoulItem>()
        for (name in AppFiles.listFileNames(itemDir)) {
            if (!name.endsWith(".json") || name.endsWith(".tmp")) continue
            try {
                val text = AppFiles.readText("$itemDir/$name") ?: continue
                val item = json.decodeFromString<PhoneSoulItem>(text)
                loaded[item.hash] = item
            } catch (_: Exception) {
                // Corrupted file — skip it. Could log in production.
            }
        }

        cache = loaded
        return loaded
    }

    /**
     * Write a single item to disk. Atomic: write to temp file, then rename.
     */
    private fun writeItem(item: PhoneSoulItem) {
        AppFiles.writeTextAtomic("$itemDir/${item.hash}.json", json.encodeToString(item))
    }
}
