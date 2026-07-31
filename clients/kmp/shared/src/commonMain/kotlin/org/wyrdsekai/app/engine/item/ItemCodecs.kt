package org.wyrdsekai.app.engine.item

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Simplified SoulItem for the phone client.
 *
 * The server's SoulItem includes embedding, signature, and Instant timestamps.
 * PhoneSoulItem strips those to the minimum needed for equipment, prompt context,
 * and FamilyLocker sync (hash is the content-address).
 */
@Serializable
data class PhoneSoulItem(
    val hash: String,
    val category: String,
    val label: String,
    val text: String,
    val creatorDid: String,
    val created: Long, // epoch millis
    val significance: Double,
    val tags: List<String> = emptyList(),
) {
    companion object {
        /**
         * Compute a content-address hash for item text.
         * Uses Kotlin's hashCode for phone (lightweight, no JCE dependency in KMP common).
         * Format: "ph-" prefix + unsigned hex to distinguish from server SHA-256 hashes.
         */
        fun computeHash(text: String): String {
            // Use a simple but well-distributed hash for KMP common code.
            // Two rounds of FNV-1a-like mixing to reduce collisions vs raw hashCode.
            var h = 0x811c9dc5L
            for (ch in text) {
                h = h xor ch.code.toLong()
                h = (h * 0x01000193L) and 0xFFFFFFFFL
            }
            return "ph-${h.toString(16).padStart(8, '0')}"
        }

        /**
         * Create a PhoneSoulItem with auto-computed hash.
         */
        fun create(
            category: String,
            label: String,
            text: String,
            creatorDid: String,
            significance: Double,
            tags: List<String> = emptyList(),
        ): PhoneSoulItem = PhoneSoulItem(
            hash = computeHash(text),
            category = category,
            label = label,
            text = text,
            creatorDid = creatorDid,
            created = currentTimeMillis(),
            significance = significance,
            tags = tags,
        )
    }
}

// --- Aspect Codec ---

/**
 * Decoded aspect definition from a PhoneSoulItem's text field.
 * Mirrors server's AspectItemCodec.AspectDefinition.
 */
@Serializable
data class AspectDefinition(
    val version: Int = 1,
    val promptOverlay: String? = null,
    val vitalityShifts: Map<String, Double> = emptyMap(),
    val selfDescription: String? = null,
    val slotHint: String = "garment",
    val tokenEstimate: Int = 20,
) {
    /** Whether this aspect injects prompt text. */
    fun hasPromptOverlay(): Boolean =
        !promptOverlay.isNullOrBlank()

    /** Whether this aspect modifies vitality baselines. */
    fun hasVitalityShifts(): Boolean =
        vitalityShifts.isNotEmpty()
}

/**
 * Codec for aspect item JSON stored in PhoneSoulItem.text.
 */
object AspectItemCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Decode a PhoneSoulItem's text field into an AspectDefinition. */
    fun decode(item: PhoneSoulItem): AspectDefinition? {
        if (item.text.isBlank() || item.category != "aspect") return null
        return decode(item.text)
    }

    /** Decode a JSON string into an AspectDefinition. */
    fun decode(jsonString: String): AspectDefinition? {
        if (jsonString.isBlank()) return null
        return try {
            json.decodeFromString<AspectDefinition>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    /** Encode an AspectDefinition to JSON string (for PhoneSoulItem.text). */
    fun encode(def: AspectDefinition): String =
        json.encodeToString(def)

    /** Create a new AspectDefinition. */
    fun create(
        promptOverlay: String?,
        vitalityShifts: Map<String, Double>,
        selfDescription: String?,
        slotHint: String,
        tokenEstimate: Int,
    ): AspectDefinition = AspectDefinition(
        version = 1,
        promptOverlay = promptOverlay,
        vitalityShifts = vitalityShifts,
        selfDescription = selfDescription,
        slotHint = slotHint,
        tokenEstimate = tokenEstimate,
    )

    /** Build a PhoneSoulItem for a validated aspect. */
    fun toSoulItem(
        name: String,
        def: AspectDefinition,
        creatorDid: String,
        significance: Double,
    ): PhoneSoulItem {
        val text = encode(def)
        val tags = mutableListOf(name.lowercase().replace(' ', '-'))
        if (def.slotHint.isNotBlank()) tags.add(def.slotHint)
        if (!def.selfDescription.isNullOrBlank()) {
            for (word in def.selfDescription.lowercase().split("\\s+".toRegex())) {
                if (word.length > 3 && tags.size < 8) tags.add(word)
            }
        }
        return PhoneSoulItem.create(
            category = "aspect",
            label = name,
            text = text,
            creatorDid = creatorDid,
            significance = significance,
            tags = tags,
        )
    }
}

// --- Reagent Codec ---

/**
 * Decoded reagent definition from a PhoneSoulItem's text field.
 * Mirrors server's ReagentItemCodec.ReagentDefinition.
 */
@Serializable
data class ReagentDefinition(
    val version: Int = 1,
    val vitalityEffects: Map<String, Double> = emptyMap(),
    val durationTicks: Int = 300,
    val promptOverlay: String? = null,
    val consumable: Boolean = true,
    val tokenEstimate: Int = 10,
) {
    /** Whether this reagent injects prompt text while active. */
    fun hasPromptOverlay(): Boolean =
        !promptOverlay.isNullOrBlank()

    /** Clamped duration (respects MAX_DURATION). */
    fun effectiveDuration(): Int =
        minOf(durationTicks, MAX_DURATION)

    companion object {
        /** Maximum allowed duration: 1800 ticks (~30 minutes). */
        const val MAX_DURATION: Int = 1800
    }
}

/**
 * Codec for reagent item JSON stored in PhoneSoulItem.text.
 */
object ReagentItemCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Decode a PhoneSoulItem's text field into a ReagentDefinition. */
    fun decode(item: PhoneSoulItem): ReagentDefinition? {
        if (item.text.isBlank() || item.category != "reagent") return null
        return decode(item.text)
    }

    /** Decode a JSON string into a ReagentDefinition. */
    fun decode(jsonString: String): ReagentDefinition? {
        if (jsonString.isBlank()) return null
        return try {
            json.decodeFromString<ReagentDefinition>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    /** Encode a ReagentDefinition to JSON string (for PhoneSoulItem.text). */
    fun encode(def: ReagentDefinition): String =
        json.encodeToString(def)

    /** Create a new ReagentDefinition. */
    fun create(
        vitalityEffects: Map<String, Double>,
        durationTicks: Int,
        promptOverlay: String?,
        consumable: Boolean,
        tokenEstimate: Int,
    ): ReagentDefinition = ReagentDefinition(
        version = 1,
        vitalityEffects = vitalityEffects,
        durationTicks = minOf(durationTicks, ReagentDefinition.MAX_DURATION),
        promptOverlay = promptOverlay,
        consumable = consumable,
        tokenEstimate = tokenEstimate,
    )

    /** Build a PhoneSoulItem for a reagent. */
    fun toSoulItem(
        name: String,
        def: ReagentDefinition,
        creatorDid: String,
        significance: Double,
    ): PhoneSoulItem {
        val text = encode(def)
        val tags = mutableListOf(
            name.lowercase().replace(' ', '-'),
            "reagent",
        )
        if (def.consumable) tags.add("consumable")
        return PhoneSoulItem.create(
            category = "reagent",
            label = name,
            text = text,
            creatorDid = creatorDid,
            significance = significance,
            tags = tags,
        )
    }
}

// --- Time utility ---

internal fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
