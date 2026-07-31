package org.wyrdsekai.app.engine.oracle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.study.StudyStore
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Lightweight Oracle for phone — runs pattern detection, anomaly detection,
 * and simple forecasting on local Study data.
 *
 * Also receives richer predictions from the server Oracle via Between.
 * Both are surfaced in the Study room.
 *
 * This is a thin wrapper — when oracle-core-kt becomes a proper Maven dependency,
 * this delegates to it instead of reimplementing.
 */
class PhoneOracle(
    private val store: StudyStore,
    private val deviceId: String,
    private val userDid: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedPredictions = mutableListOf<PhonePrediction>()
    private var serverPredictions = mutableListOf<PhonePrediction>()

    /**
     * Run local analysis on Study data. Call during phone Forge sleep.
     * Returns predictions from local data only.
     */
    suspend fun analyze(): List<PhonePrediction> {
        val predictions = mutableListOf<PhonePrediction>()

        // Get recent journal entries
        val entries = store.recentJournal(userDid, limit = 200)
        if (entries.size < 14) return predictions  // not enough data

        // Extract daily event counts
        val dayMs = 86_400_000L
        val timestamps = mutableListOf<Long>()
        val counts = mutableListOf<Double>()
        if (entries.isNotEmpty()) {
            val start = entries.last().timestamp  // oldest
            val end = entries.first().timestamp    // newest
            var current = start
            while (current <= end) {
                val windowEnd = current + dayMs
                val count = entries.count { it.timestamp in current until windowEnd }
                timestamps.add(current)
                counts.add(count.toDouble())
                current = windowEnd
            }
        }

        if (counts.size >= 14) {
            // Pattern: detect weekly cycle via autocorrelation
            predictions.addAll(detectPeriodicity(counts))

            // Trend detection
            predictions.addAll(detectTrend(counts))

            // Anomaly: z-score on recent vs baseline
            predictions.addAll(detectAnomalies(timestamps, counts))

            // Simple forecast
            predictions.addAll(forecast(counts))
        }

        // Topic evolution: keyword frequency in recent vs older entries
        predictions.addAll(detectTopicShifts(entries))

        cachedPredictions.clear()
        cachedPredictions.addAll(predictions)
        return predictions
    }

    /** Get all predictions (local + server). */
    fun allPredictions(): List<PhonePrediction> {
        return (cachedPredictions + serverPredictions)
            .sortedByDescending { it.confidence }
            .distinctBy { it.text.take(40) }
    }

    /** Receive predictions from server Oracle via Between. */
    fun receiveServerPredictions(predictionsJson: String) {
        try {
            val parsed = json.decodeFromString<List<PhonePrediction>>(predictionsJson)
            serverPredictions.clear()
            serverPredictions.addAll(parsed)
        } catch (_: Exception) {}
    }

    /** Start listening for server predictions via Between. */
    fun startListening(between: BetweenClient, householdId: String) {
        val subject = "between.$householdId.*.*.oracle.predictions"
        between.subscribe(subject) { _, data ->
            receiveServerPredictions(data.decodeToString())
        }
    }

    // ── Local analysis algorithms ────────────────────────────────────

    private fun detectPeriodicity(values: List<Double>): List<PhonePrediction> {
        val std = std(values)
        if (std == 0.0) return emptyList()
        val predictions = mutableListOf<PhonePrediction>()

        for (lag in listOf(7, 14, 30)) {
            if (lag >= values.size / 2) continue
            val acf = autocorrelation(values, lag)
            if (acf > 0.3) {
                val period = when (lag) {
                    7 -> "weekly"
                    14 -> "biweekly"
                    30 -> "monthly"
                    else -> "$lag-day"
                }
                predictions.add(PhonePrediction(
                    text = "Your activity has a $period pattern (r=${(acf * 100).toInt() / 100.0})",
                    category = "pattern",
                    confidence = min(acf * 0.8 + 0.2, 0.95),
                ))
            }
        }
        return predictions
    }

    private fun detectTrend(values: List<Double>): List<PhonePrediction> {
        val recent = values.takeLast(14)
        if (recent.size < 7) return emptyList()
        val x = recent.indices.map { it.toDouble() }
        val slope = linearSlope(x, recent)
        val r2 = rSquared(recent, slope)
        if (abs(r2) < 0.3) return emptyList()

        val direction = if (slope > 0) "increasing" else "declining"
        return listOf(PhonePrediction(
            text = "Activity is $direction over the last 2 weeks",
            category = "pattern",
            confidence = min(abs(r2) * 0.7 + 0.3, 0.90),
        ))
    }

    private fun detectAnomalies(timestamps: List<Long>, values: List<Double>): List<PhonePrediction> {
        if (values.size < 14) return emptyList()
        val baseline = values.dropLast(3)
        val recent = values.takeLast(3)
        val mean = mean(baseline)
        val std = std(baseline)
        if (std == 0.0) return emptyList()

        return recent.mapNotNull { v ->
            val z = (v - mean) / std
            if (abs(z) >= 2.5) {
                val direction = if (z > 0) "spike" else "drop"
                PhonePrediction(
                    text = "Unusual $direction: ${v.toInt()} events (baseline: ${mean.toInt()} ± ${std.toInt()})",
                    category = "anomaly",
                    confidence = min(0.6 + abs(z) / 8.0, 0.95),
                )
            } else null
        }
    }

    private fun forecast(values: List<Double>): List<PhonePrediction> {
        if (values.size < 14) return emptyList()
        val slope = linearSlope(values.indices.map { it.toDouble() }, values)
        val direction = when {
            slope > 0.1 -> "increasing"
            slope < -0.1 -> "declining"
            else -> "stable"
        }
        return listOf(PhonePrediction(
            text = "Activity forecast: $direction over next week",
            category = "forecast",
            confidence = 0.55,
        ))
    }

    private fun detectTopicShifts(entries: List<org.wyrdsekai.app.engine.study.StudyItem>): List<PhonePrediction> {
        if (entries.size < 20) return emptyList()

        val half = entries.size / 2
        val recentText = entries.take(half).joinToString(" ") { it.content }
        val olderText = entries.drop(half).joinToString(" ") { it.content }

        val recentWords = extractKeywords(recentText)
        val olderWords = extractKeywords(olderText)

        val predictions = mutableListOf<PhonePrediction>()
        // Find words that appeared in recent but not older
        for (word in recentWords.keys) {
            val recentFreq = recentWords[word] ?: 0
            val olderFreq = olderWords[word] ?: 0
            if (recentFreq >= 3 && olderFreq == 0) {
                predictions.add(PhonePrediction(
                    text = "New topic: '$word' (${recentFreq} mentions recently, not seen before)",
                    category = "topic",
                    confidence = min(0.5 + recentFreq * 0.05, 0.85),
                ))
            }
        }
        return predictions.take(3)
    }

    // ── Math utilities ───────────────────────────────────────────────

    private fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    private fun std(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    private fun autocorrelation(values: List<Double>, lag: Int): Double {
        val m = mean(values)
        val s = std(values)
        if (s == 0.0 || lag >= values.size) return 0.0
        var sum = 0.0
        for (i in 0 until values.size - lag) {
            sum += (values[i] - m) * (values[i + lag] - m)
        }
        return sum / ((values.size - lag) * s * s)
    }

    private fun linearSlope(x: List<Double>, y: List<Double>): Double {
        val xm = mean(x)
        val ym = mean(y)
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            num += (x[i] - xm) * (y[i] - ym)
            den += (x[i] - xm) * (x[i] - xm)
        }
        return if (den != 0.0) num / den else 0.0
    }

    private fun rSquared(y: List<Double>, slope: Double): Double {
        val ym = mean(y)
        val intercept = ym - slope * mean(y.indices.map { it.toDouble() })
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in y.indices) {
            ssTot += (y[i] - ym) * (y[i] - ym)
            ssRes += (y[i] - (slope * i + intercept)) * (y[i] - (slope * i + intercept))
        }
        return if (ssTot > 0) 1.0 - ssRes / ssTot else 0.0
    }

    private val stopwords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "have", "has",
        "had", "do", "does", "did", "will", "would", "shall", "should", "may", "might",
        "can", "could", "this", "that", "these", "those", "and", "or", "but", "not",
        "my", "your", "his", "her", "its", "our", "their", "for", "with", "from",
        "about", "into", "through", "during", "before", "after", "above", "below",
    )

    private fun extractKeywords(text: String): Map<String, Int> {
        return Regex("[a-zA-Z]{3,}").findAll(text.lowercase())
            .map { it.value }
            .filter { it !in stopwords }
            .groupingBy { it }
            .eachCount()
    }
}

@Serializable
data class PhonePrediction(
    val text: String,
    val category: String,   // pattern, anomaly, forecast, topic, recommendation, anticipation
    val confidence: Double,
    val textKey: String = "",
    val textParams: Map<String, String> = emptyMap(),
    val actionable: Boolean = false,
)
