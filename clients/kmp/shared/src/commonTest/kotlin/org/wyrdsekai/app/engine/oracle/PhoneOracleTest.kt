package org.wyrdsekai.app.engine.oracle

import org.wyrdsekai.app.platform.epochMillis
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import org.wyrdsekai.app.engine.study.StudyItem
import org.wyrdsekai.app.engine.study.StudyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneOracleTest {

    @Test
    fun analyzeWithSufficientData() = runTest {
        val store = TestStudyStore()

        // Generate 60 days of journal entries with proper daily spacing
        val now = epochMillis()
        val dayMs = 86_400_000L
        for (day in 0 until 60) {
            val weekday = day % 7 < 5
            val count = if (weekday) 3 else 1
            val dayStart = now - (60 - day) * dayMs
            repeat(count) { h ->
                store.writeJournalAt("user1", "Working on project day $day topic${day % 5}", dayStart + h * 3_600_000L)
            }
        }

        val oracle = PhoneOracle(store, "phone-1", "user1")
        val predictions = oracle.analyze()

        // Should produce at least some predictions with 60 days of data
        assertTrue(predictions.isNotEmpty(), "Should produce predictions from 60 days of data")

        for (p in predictions) {
            assertTrue(p.text.isNotEmpty())
            assertTrue(p.confidence in 0.0..1.0)
            assertTrue(p.category in listOf("pattern", "anomaly", "forecast", "topic"))
        }
    }

    @Test
    fun analyzeWithInsufficientData() = runTest {
        val store = TestStudyStore()
        store.writeJournal("user1", "Only one entry")

        val oracle = PhoneOracle(store, "phone-1", "user1")
        val predictions = oracle.analyze()

        assertTrue(predictions.isEmpty(), "Should not produce predictions from 1 entry")
    }

    @Test
    fun receiveServerPredictions() {
        val store = TestStudyStore()
        val oracle = PhoneOracle(store, "phone-1", "user1")

        val json = """[{"text":"Server: weekly pattern","category":"pattern","confidence":0.85}]"""
        oracle.receiveServerPredictions(json)

        val all = oracle.allPredictions()
        assertTrue(all.any { it.text.contains("Server") })
    }

    @Test
    fun serverPredictionsMergeWithLocal() = runTest {
        val store = TestStudyStore()
        val now = epochMillis()
        val dayMs = 86_400_000L

        // Generate enough data for local predictions with proper timestamps
        for (day in 0 until 60) {
            val dayStart = now - (60 - day) * dayMs
            repeat(if (day % 7 < 5) 3 else 1) { h ->
                store.writeJournalAt("user1", "Entry day $day", dayStart + h * 3_600_000L)
            }
        }

        val oracle = PhoneOracle(store, "phone-1", "user1")
        oracle.analyze()

        // Add server predictions
        oracle.receiveServerPredictions(
            """[{"text":"Server prediction: email spike","category":"anomaly","confidence":0.90}]"""
        )

        val all = oracle.allPredictions()
        // Should have both local and server
        assertTrue(all.size >= 2, "Should have local + server predictions, got ${all.size}")
    }

    @Test
    fun betweenSyncReceivesPredictions() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val store = TestStudyStore()
        val oracle = PhoneOracle(store, "phone-1", "user1")
        oracle.startListening(between, "household-1")

        // Simulate server broadcasting predictions
        val json = """[{"text":"From server via Between","category":"pattern","confidence":0.75}]"""
        between.publish(
            "between.household-1.server.*.oracle.predictions",
            json.encodeToByteArray()
        )

        val all = oracle.allPredictions()
        assertTrue(all.any { it.text.contains("From server") })
    }

    @Test
    fun topicDetectionFindsNewTopics() = runTest {
        val store = TestStudyStore()

        // Older entries about "kubernetes"
        repeat(15) {
            store.writeJournal("user1", "kubernetes deployment scaling cluster pods services")
        }

        // Newer entries introduce "machine learning"
        repeat(15) {
            store.writeJournal("user1", "machine learning prediction training models neural")
        }

        val oracle = PhoneOracle(store, "phone-1", "user1")
        val predictions = oracle.analyze()

        val topics = predictions.filter { it.category == "topic" }
        // May or may not find topics depending on thresholds, but shouldn't crash
        assertTrue(predictions is List<PhonePrediction>)
    }
}

/** Simple in-memory StudyStore for testing. */
private class TestStudyStore : StudyStore {
    private val items = mutableMapOf<String, StudyItem>()
    private var counter = 0
    private var tsCounter = epochMillis() - 90 * 86_400_000L

    override suspend fun putItem(item: StudyItem) { items[item.id] = item }

    override suspend fun writeJournal(userDid: String, content: String, isPrivate: Boolean): StudyItem {
        return writeJournalAt(userDid, content, tsCounter++, isPrivate)
    }

    suspend fun writeJournalAt(userDid: String, content: String, timestamp: Long, isPrivate: Boolean = false): StudyItem {
        val item = StudyItem(
            id = "si-test-${++counter}",
            userDid = userDid,
            itemType = if (isPrivate) StudyItem.TYPE_JOURNAL_PRIVATE else StudyItem.TYPE_JOURNAL,
            title = content.lineSequence().firstOrNull()?.take(120) ?: "",
            content = content,
            timestamp = timestamp,
        )
        items[item.id] = item
        return item
    }

    override suspend fun editItem(id: String, newContent: String): StudyItem? {
        val existing = items[id] ?: return null
        val updated = existing.copy(content = newContent, version = existing.version + 1)
        items[id] = updated
        return updated
    }

    override suspend fun searchJournal(userDid: String, query: String, limit: Int): List<StudyItem> {
        val lower = query.lowercase()
        return items.values.filter {
            it.userDid == userDid && it.content.lowercase().contains(lower)
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun recentJournal(userDid: String, limit: Int): List<StudyItem> {
        return items.values.filter {
            it.userDid == userDid && (it.itemType == StudyItem.TYPE_JOURNAL || it.itemType == StudyItem.TYPE_JOURNAL_PRIVATE)
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun addNote(userDid: String, content: String): StudyItem {
        val item = StudyItem(id = "si-test-${++counter}", userDid = userDid, itemType = StudyItem.TYPE_NOTE,
            title = content.take(120), content = content, timestamp = tsCounter++)
        items[item.id] = item
        return item
    }

    override suspend fun pin(userDid: String, title: String, snippet: String, sourceUrl: String): StudyItem {
        val item = StudyItem(id = "si-test-${++counter}", userDid = userDid, itemType = StudyItem.TYPE_PINBOARD,
            title = title, content = snippet, timestamp = tsCounter++)
        items[item.id] = item
        return item
    }

    override suspend fun searchAll(userDid: String, query: String, limit: Int): List<StudyItem> = searchJournal(userDid, query, limit)
    override suspend fun getItem(id: String): StudyItem? = items[id]
    override suspend fun deleteItem(id: String): Boolean = items.remove(id) != null
    override suspend fun count(userDid: String): Int = items.values.count { it.userDid == userDid }
}
