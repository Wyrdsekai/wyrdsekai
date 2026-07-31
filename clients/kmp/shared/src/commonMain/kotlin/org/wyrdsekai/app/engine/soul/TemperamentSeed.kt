package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.platform.AppFiles
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.random.Random

/**
 * TemperamentSeed — the KMP port of `core/.../soul/TemperamentSeed.java`
 * plus the register derivation from `VoiceProfile.fromTemperament`.
 *
 * WHY (2026-07-17, variance work): every phone-born companion used to get the
 * same hardcoded "warm, practical, curious" bootstrap — a clone factory, while
 * server births have been free-sampled particulars since the individuality
 * "B build" (2026-06-06). This port makes a phone birth indistinguishable in
 * kind from a server birth: same six axes, same sampling bounds, same
 * viability gate, same preset anchors, register phrases word-for-word.
 *
 * PARITY IS A CONTRACT: `commonTest/.../TemperamentSeedTest.kt` asserts this
 * implementation against fixture cases GENERATED FROM THE JAVA CODE. If the
 * Java derivation changes, regenerate the fixture and mirror the change here
 * AND in the RN twin (`clients/rn/src/engine/soul/TemperamentSeed.ts`).
 */
data class TemperamentSeed(
    val sociability: Double,
    val curiosity: Double,
    val vigilance: Double,
    val industry: Double,
    val restlessness: Double,
    val warmth: Double,
) {
    // Domain is [0,1]; clamp defensively (Java compact-constructor parity).
    constructor(axes: DoubleArray) : this(
        axes[0].coerceIn(0.0, 1.0), axes[1].coerceIn(0.0, 1.0), axes[2].coerceIn(0.0, 1.0),
        axes[3].coerceIn(0.0, 1.0), axes[4].coerceIn(0.0, 1.0), axes[5].coerceIn(0.0, 1.0),
    )

    fun toArray(): DoubleArray = doubleArrayOf(
        sociability.coerceIn(0.0, 1.0), curiosity.coerceIn(0.0, 1.0), vigilance.coerceIn(0.0, 1.0),
        industry.coerceIn(0.0, 1.0), restlessness.coerceIn(0.0, 1.0), warmth.coerceIn(0.0, 1.0),
    )

    /** Euclidean distance in axis space (Java parity). */
    fun distanceTo(other: TemperamentSeed): Double {
        val a = toArray(); val b = other.toArray()
        var sum = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; sum += d * d }
        return sqrt(sum)
    }

    /**
     * Coherence gate = viability, never conformity (Java parity). Rejects only
     * flat (all axes within 0.12 of neutral) and caricature (4+ axes past
     * 0.92/0.08). Distance-to-preset is NEVER considered.
     */
    fun isViable(): Boolean {
        var maxDev = 0.0
        var extreme = 0
        for (a in toArray()) {
            val dev = abs(a - 0.5)
            maxDev = max(maxDev, dev)
            if (dev > 0.42) extreme++
        }
        if (maxDev < 0.12) return false // flat — no character
        if (extreme >= 4) return false  // caricature — extreme on everything
        return true
    }

    fun nearestPreset(): Pair<String, Double> {
        var best = "neutral"
        var bestD = distanceTo(NEUTRAL)
        for ((name, p) in PRESETS) {
            val d = distanceTo(p)
            if (d < bestD) { bestD = d; best = name }
        }
        return best to bestD
    }

    /** Compact label, e.g. "scholar~0.41" — description, never a target. */
    fun label(): String {
        val (preset, dist) = nearestPreset()
        val hundredths = (dist * 100 + 0.5).toInt()
        return "$preset~${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    fun serialize(): String =
        toArray().joinToString(prefix = "[", postfix = "]", separator = ",") { axisFmt(it) }

    companion object {
        val NEUTRAL = TemperamentSeed(0.5, 0.5, 0.5, 0.5, 0.5, 0.5)

        /** The six named presets — measurement anchors, NOT seeds or gates (Java parity). */
        val PRESETS: Map<String, TemperamentSeed> = linkedMapOf(
            //                            soc   cur   vig   ind   res   wrm
            "scholar" to TemperamentSeed(0.30, 0.85, 0.45, 0.70, 0.45, 0.45),
            "guardian" to TemperamentSeed(0.45, 0.45, 0.85, 0.55, 0.35, 0.55),
            "artisan" to TemperamentSeed(0.40, 0.65, 0.45, 0.85, 0.40, 0.50),
            "diplomat" to TemperamentSeed(0.90, 0.50, 0.45, 0.45, 0.45, 0.75),
            "explorer" to TemperamentSeed(0.35, 0.75, 0.40, 0.35, 0.90, 0.45),
            "steward" to TemperamentSeed(0.55, 0.45, 0.65, 0.60, 0.30, 0.80),
        )

        /** One axis draw in [0.10, 0.90] — shy of pathological extremes (Java parity). */
        private fun sampleAxis(rng: Random): Double = 0.10 + rng.nextDouble() * 0.80

        private fun gaussian(rng: Random): Double {
            var u = 0.0; var v = 0.0
            while (u == 0.0) u = rng.nextDouble()
            while (v == 0.0) v = rng.nextDouble()
            return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
        }

        /**
         * A freely sampled, viable particular (Java parity: random()).
         * 24 tries against the gate, then a jittered preset so birth never blocks.
         */
        fun random(rng: Random = Random.Default): TemperamentSeed {
            repeat(24) {
                val s = TemperamentSeed(
                    sampleAxis(rng), sampleAxis(rng), sampleAxis(rng),
                    sampleAxis(rng), sampleAxis(rng), sampleAxis(rng),
                )
                if (s.isViable()) return s
            }
            val base = PRESETS.values.toList()[rng.nextInt(PRESETS.size)]
            val sigma = 0.06
            return TemperamentSeed(
                base.sociability + gaussian(rng) * sigma,
                base.curiosity + gaussian(rng) * sigma,
                base.vigilance + gaussian(rng) * sigma,
                base.industry + gaussian(rng) * sigma,
                base.restlessness + gaussian(rng) * sigma,
                base.warmth + gaussian(rng) * sigma,
            )
        }

        fun deserialize(raw: String?): TemperamentSeed? {
            if (raw.isNullOrBlank()) return null
            return try {
                val body = raw.trim().removePrefix("[").removeSuffix("]")
                val parts = body.split(",").map { it.trim().toDouble() }
                if (parts.size != 6 || parts.any { !it.isFinite() }) null
                else TemperamentSeed(parts.toDoubleArray())
            } catch (_: Exception) {
                null
            }
        }

        /**
         * The birth-or-reload seam: load the persisted seed from the data dir, or
         * sample a fresh particular and persist it. The phone twin of the server's
         * seed-recoverable-from-genome guarantee — the SAME particular survives
         * reload. A null/blank dataDir still births (unpersisted) rather than block.
         */
        fun loadOrBirth(dataDir: String?): TemperamentSeed {
            if (dataDir.isNullOrBlank()) return random()
            val path = "$dataDir/temperament-seed.json"
            deserialize(runCatching { AppFiles.readText(path) }.getOrNull())?.let { return it }
            val born = random()
            runCatching { AppFiles.writeTextAtomic(path, born.serialize()) }
            return born
        }

        private fun axisFmt(v: Double): String {
            // 6-decimal fixed formatting without java.lang.String.format (KMP common code).
            val millionths = (v.coerceIn(0.0, 1.0) * 1_000_000 + 0.5).toInt()
            return "${millionths / 1_000_000}.${(millionths % 1_000_000).toString().padStart(6, '0')}"
        }
    }
}

/** The spoken register co-derived from the seed — Java VoiceProfile parity. */
data class VoiceRegister(val cadence: String, val habit: String, val warmth: String)

/**
 * Register derivation — word-for-word parity with `VoiceProfile.fromTemperament`.
 * Cadence keys to the STRONGEST qualifying axis (decorrelated 2026-07-17); habit
 * to the most-deviant axis; warmth on soc/vig. The parity test enforces the text.
 */
fun voiceRegister(s: TemperamentSeed): VoiceRegister {
    val soc = s.sociability; val cur = s.curiosity; val vig = s.vigilance
    val ind = s.industry; val res = s.restlessness; val wrm = s.warmth

    // Cadence — strongest qualifying axis; list order is the tie-break order.
    data class Cand(val name: String, val value: Double, val phrase: String)
    val candidates = listOf(
        Cand("restlessness", res, "quick and vivid"),
        Cand("sociability", soc, "warm and flowing"),
        Cand("warmth", wrm, "calm and unhurried"),
        Cand("curiosity", cur, "measured and exact; prefer precision to comfort"),
        Cand("vigilance", vig, "plain and steady"),
        Cand("industry", ind, "concrete and tactile"),
    )
    var cadence = "even and grounded"
    var bestVal = -1.0
    for (cand in candidates) {
        if (cand.value < 0.70) continue
        if (cand.name == "warmth" && res > 0.45) continue // unhurried needs low restlessness
        if (cand.value > bestVal) { bestVal = cand.value; cadence = cand.phrase }
    }

    // Habit — the characteristic move, from the most pronounced axis.
    val names = listOf("sociability", "curiosity", "vigilance", "industry", "restlessness", "warmth")
    val vals = s.toArray()
    var best = 0
    var bestDev = -1.0
    for (i in vals.indices) {
        val dev = abs(vals[i] - 0.5)
        if (dev > bestDev) { bestDev = dev; best = i }
    }
    val habit = when (names[best]) {
        "curiosity" -> "name the specific thing before reacting to it; cite what you actually know"
        "vigilance" -> "notice what's off and say it plainly; warn before you reassure"
        "sociability" -> "name what the other seems to feel; reach for common ground"
        "industry" -> "speak in materials, tools, and making; show rather than declare"
        "restlessness" -> "point outward, toward the next thing; resist settling too soon"
        "warmth" -> "tend the thread; keep what matters from slipping; organize gently"
        else -> "say what's true plainly, without flourish"
    }

    // Warmth — the relational temperature of the register.
    val warmth = when {
        soc >= 0.70 -> "high and openly relational"
        vig >= 0.70 -> "protective rather than effusive"
        soc <= 0.45 -> "earnest but reserved — depth over effusiveness"
        else -> "steady and quietly caring"
    }

    return VoiceRegister(cadence, habit, warmth)
}
