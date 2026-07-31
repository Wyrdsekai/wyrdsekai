package org.wyrdsekai.app.protocol

import kotlinx.serialization.json.Json

/**
 * Configured JSON serializer matching the Jackson wire format.
 * - ignoreUnknownKeys: forward compatibility (new fields don't break old clients)
 * - encodeDefaults: ensure empty lists/maps appear on wire
 * - isLenient: accept minor JSON variations
 * - classDiscriminator: "type" matches Jackson @JsonTypeInfo
 */
val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    classDiscriminator = "type"
}

/** Serialize a C2S message to JSON string. */
fun C2SMessage.toJson(): String = WireJson.encodeToString(C2SMessage.serializer(), this)

/** Deserialize an S2C message from JSON string. */
fun parseS2CMessage(json: String): S2CMessage = WireJson.decodeFromString(S2CMessage.serializer(), json)
