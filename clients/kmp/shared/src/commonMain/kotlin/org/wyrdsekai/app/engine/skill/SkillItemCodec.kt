package org.wyrdsekai.app.engine.skill

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.wyrdsekai.app.engine.item.PhoneSoulItem

/**
 * Codec for the skill item JSON format stored in PhoneSoulItem.text.
 *
 * A skill PhoneSoulItem's text field contains a JSON document describing
 * a companion-created capability (code, params, tests, metadata).
 * Port of core/skill/SkillItemCodec.java for the KMP phone client.
 */
object SkillItemCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Decoded skill definition from a PhoneSoulItem's text field.
     */
    @Serializable
    data class SkillDefinition(
        val version: Int = 1,
        val runtime: String? = null,
        val code: String? = null,
        val params: List<Param> = emptyList(),
        val description: String? = null,
        val testCases: List<TestCase> = emptyList(),
        val dependencies: List<String> = emptyList(),
        val usageCount: Int = 0,
        val lastUsed: Long? = null, // epoch millis (KMP-safe, no java.time.Instant)
    ) {
        /** Create with incremented usage count and updated timestamp. */
        fun withUsage(nowEpochMs: Long): SkillDefinition = copy(
            usageCount = usageCount + 1,
            lastUsed = nowEpochMs,
        )
    }

    @Serializable
    data class Param(
        val name: String,
        val type: String = "string",
        val description: String? = null,
        val required: Boolean = false,
    )

    @Serializable
    data class TestCase(
        val params: JsonObject? = null,
        val expectSuccess: Boolean = true,
        val expectContains: String? = null,
    )

    /** Decode a PhoneSoulItem's text field into a SkillDefinition. */
    fun decode(item: PhoneSoulItem): SkillDefinition? {
        if (item.text.isBlank() || item.category != "skill") return null
        return decode(item.text)
    }

    /** Decode a JSON string into a SkillDefinition. */
    fun decode(jsonString: String): SkillDefinition? {
        if (jsonString.isBlank()) return null
        return try {
            json.decodeFromString<SkillDefinition>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    /** Encode a SkillDefinition to JSON string (for PhoneSoulItem.text). */
    fun encode(def: SkillDefinition): String =
        json.encodeToString(def)

    /** Create a new SkillDefinition for initial storage. */
    fun create(
        runtime: String,
        code: String,
        params: List<Param>,
        description: String,
        testCases: List<TestCase> = emptyList(),
        dependencies: List<String> = emptyList(),
    ): SkillDefinition = SkillDefinition(
        version = 1,
        runtime = runtime,
        code = code,
        params = params,
        description = description,
        testCases = testCases,
        dependencies = dependencies,
        usageCount = 0,
        lastUsed = null,
    )

    /** Build a PhoneSoulItem for a validated skill. */
    fun toSoulItem(
        skillName: String,
        def: SkillDefinition,
        creatorDid: String,
        significance: Double = 0.6,
    ): PhoneSoulItem {
        val text = encode(def)
        val tags = mutableListOf(skillName)
        if (!def.description.isNullOrBlank()) {
            for (word in def.description.lowercase().split("\\s+".toRegex())) {
                if (word.length > 3 && tags.size < 10) tags.add(word)
            }
        }
        return PhoneSoulItem.create(
            category = "skill",
            label = skillName,
            text = text,
            creatorDid = creatorDid,
            significance = significance,
            tags = tags,
        )
    }
}
