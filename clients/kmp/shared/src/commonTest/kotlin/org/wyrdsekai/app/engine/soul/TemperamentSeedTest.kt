package org.wyrdsekai.app.engine.soul

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TemperamentSeed parity — the KMP port must match the Java ground truth exactly.
 *
 * The cases below are GENERATED FROM THE JAVA implementation
 * (core/.../soul/TemperamentSeed.java + VoiceProfile.fromTemperament) — the same
 * fixture drives the RN twin's test (clients/rn/__tests__/engine/TemperamentSeed.test.ts,
 * fixtures/temperament-parity.json). If Java changes, regenerate BOTH. A mismatch
 * here means a phone-born particular differs in kind from a server-born one —
 * the exact clone/drift bug this port closes (variance work 2026-07-17).
 */
class TemperamentSeedTest {

    private data class Case(
        val name: String, val axes: DoubleArray,
        val cadence: String, val habit: String, val warmth: String,
        val nearestPreset: String, val nearestDistance: Double, val viable: Boolean,
    )

    private val fixture = listOf(
        Case("scholar", doubleArrayOf(0.30, 0.85, 0.45, 0.70, 0.45, 0.45),
            "measured and exact; prefer precision to comfort",
            "name the specific thing before reacting to it; cite what you actually know",
            "earnest but reserved — depth over effusiveness",
            "scholar", 0.000000, true),
        Case("guardian", doubleArrayOf(0.45, 0.45, 0.85, 0.55, 0.35, 0.55),
            "plain and steady",
            "notice what's off and say it plainly; warn before you reassure",
            "protective rather than effusive",
            "guardian", 0.000000, true),
        Case("artisan", doubleArrayOf(0.40, 0.65, 0.45, 0.85, 0.40, 0.50),
            "concrete and tactile",
            "speak in materials, tools, and making; show rather than declare",
            "earnest but reserved — depth over effusiveness",
            "artisan", 0.000000, true),
        Case("diplomat", doubleArrayOf(0.90, 0.50, 0.45, 0.45, 0.45, 0.75),
            "warm and flowing",
            "name what the other seems to feel; reach for common ground",
            "high and openly relational",
            "diplomat", 0.000000, true),
        Case("explorer", doubleArrayOf(0.35, 0.75, 0.40, 0.35, 0.90, 0.45),
            "quick and vivid",
            "point outward, toward the next thing; resist settling too soon",
            "earnest but reserved — depth over effusiveness",
            "explorer", 0.000000, true),
        Case("steward", doubleArrayOf(0.55, 0.45, 0.65, 0.60, 0.30, 0.80),
            "calm and unhurried",
            "tend the thread; keep what matters from slipping; organize gently",
            "steady and quietly caring",
            "steward", 0.000000, true),
        Case("neutral", doubleArrayOf(0.50, 0.50, 0.50, 0.50, 0.50, 0.50),
            "even and grounded",
            "name what the other seems to feel; reach for common ground",
            "steady and quietly caring",
            "neutral", 0.000000, false),
        Case("cur-dominates-soc", doubleArrayOf(0.75, 0.88, 0.30, 0.40, 0.35, 0.45),
            "measured and exact; prefer precision to comfort",
            "name the specific thing before reacting to it; cite what you actually know",
            "high and openly relational",
            "neutral", 0.530943, true),
        Case("soc-dominates-cur", doubleArrayOf(0.88, 0.75, 0.30, 0.40, 0.35, 0.45),
            "warm and flowing",
            "name what the other seems to feel; reach for common ground",
            "high and openly relational",
            "diplomat", 0.433474, true),
        Case("warm-restless-guard", doubleArrayOf(0.40, 0.40, 0.30, 0.40, 0.60, 0.85),
            "even and grounded",
            "tend the thread; keep what matters from slipping; organize gently",
            "earnest but reserved — depth over effusiveness",
            "neutral", 0.450000, true),
        Case("warm-calm", doubleArrayOf(0.40, 0.40, 0.30, 0.40, 0.30, 0.85),
            "calm and unhurried",
            "tend the thread; keep what matters from slipping; organize gently",
            "earnest but reserved — depth over effusiveness",
            "steward", 0.435890, true),
        Case("mid-default", doubleArrayOf(0.60, 0.65, 0.35, 0.55, 0.45, 0.60),
            "even and grounded",
            "name the specific thing before reacting to it; cite what you actually know",
            "steady and quietly caring",
            "neutral", 0.264575, true),
        Case("flat-notviable", doubleArrayOf(0.55, 0.45, 0.52, 0.48, 0.55, 0.50),
            "even and grounded",
            "name what the other seems to feel; reach for common ground",
            "steady and quietly caring",
            "neutral", 0.091104, false),
        Case("vig-low-soc", doubleArrayOf(0.30, 0.40, 0.80, 0.55, 0.40, 0.50),
            "plain and steady",
            "notice what's off and say it plainly; warn before you reassure",
            "protective rather than effusive",
            "guardian", 0.180278, true),
        Case("ind-only", doubleArrayOf(0.45, 0.50, 0.40, 0.82, 0.40, 0.55),
            "concrete and tactile",
            "speak in materials, tools, and making; show rather than declare",
            "earnest but reserved — depth over effusiveness",
            "artisan", 0.175784, true),
        Case("double-high-res-wins", doubleArrayOf(0.60, 0.55, 0.40, 0.50, 0.86, 0.84),
            "quick and vivid",
            "point outward, toward the next thing; resist settling too soon",
            "steady and quietly caring",
            "neutral", 0.517397, true),
    )

    @Test
    fun javaParityFixture() {
        for (c in fixture) {
            val s = TemperamentSeed(c.axes)
            val reg = voiceRegister(s)
            assertEquals(c.cadence, reg.cadence, "cadence for ${c.name}")
            assertEquals(c.habit, reg.habit, "habit for ${c.name}")
            assertEquals(c.warmth, reg.warmth, "warmth for ${c.name}")
            val (preset, dist) = s.nearestPreset()
            assertEquals(c.nearestPreset, preset, "nearest preset for ${c.name}")
            assertTrue(abs(dist - c.nearestDistance) < 1e-5, "nearest distance for ${c.name}")
            assertEquals(c.viable, s.isViable(), "viability for ${c.name}")
        }
    }

    @Test
    fun samplingStaysInBoundsAndViable() {
        repeat(500) {
            val s = TemperamentSeed.random()
            for (v in s.toArray()) {
                assertTrue(v >= 0.10 - 1e-9 && v <= 0.90 + 1e-9, "axis in [0.10,0.90]")
            }
            assertTrue(s.isViable(), "every born particular is viable")
        }
    }

    @Test
    fun twoBirthsAreDistinctParticulars() {
        // Identical seeds would mean the RNG regressed to a constant — the clone bug.
        assertNotEquals(
            TemperamentSeed.random().toArray().toList(),
            TemperamentSeed.random().toArray().toList(),
        )
    }

    @Test
    fun presetsLabelAsThemselves() {
        for ((name, p) in TemperamentSeed.PRESETS) {
            assertTrue(p.isViable(), "preset $name viable")
            assertEquals(name, p.nearestPreset().first)
        }
    }

    @Test
    fun serializationRoundTripPreservesTheParticular() {
        val s = TemperamentSeed.random()
        val back = TemperamentSeed.deserialize(s.serialize())
        assertTrue(back != null)
        val a = s.toArray(); val b = back!!.toArray()
        for (i in a.indices) assertTrue(abs(a[i] - b[i]) < 1e-5)
    }

    @Test
    fun garbageDeserializesToNullNotACorruptParticular() {
        assertNull(TemperamentSeed.deserialize(null))
        assertNull(TemperamentSeed.deserialize(""))
        assertNull(TemperamentSeed.deserialize("not json"))
        assertNull(TemperamentSeed.deserialize("[1,2]"))
    }
}
