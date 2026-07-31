package org.wyrdsekai.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Exit(
    val direction: String,
    val targetRoom: String,
    val label: String,
)

@Serializable
data class Entity(
    val id: String,
    val name: String,
    val type: String,
    val description: String,
    // Match the wire (server Entity carries these too). Model them so kotlinx
    // never has to skip unknown keys on a populated room_state. (2026-07-24)
    val did: String? = null,
    val aliases: List<String> = emptyList(),
    val posture: String? = null,
)

@Serializable
data class RoomObject(
    val id: String,
    val name: String,
    val description: String,
    val takeable: Boolean,
    // Match the wire (server RoomObject has 8 fields). These were absent, so a
    // steward Study's room_state — objects carrying a `state` MAP and `aliases`
    // (e.g. the leather chair's embodiment state) — forced kotlinx to SKIP those
    // unknown nested structures, and that skip mis-tracked the brace depth on the
    // big frame: the whole room_state was rejected ("Expected EOF") and dropped,
    // so a steward saw the GENERIC Study on the phone, not their real furnishings
    // (roster ledger, ward keyring, treasury…). Modelling the fields removes the
    // skip entirely. All defaulted for forward-compat. (2026-07-24)
    val visible: Boolean = true,
    val cloneable: Boolean = false,
    val aliases: List<String> = emptyList(),
    val state: Map<String, String> = emptyMap(),
)

@Serializable
data class Hint(
    val label: String,
    val intent: String,
    val action: String,
    val labelKey: String? = null,
)

@Serializable
data class RoomSnapshot(
    val roomId: String,
    val name: String,
    val description: String,
    val zone: String,
    val exits: List<Exit> = emptyList(),
    val entities: List<Entity> = emptyList(),
    val objects: List<RoomObject> = emptyList(),
    val hints: List<Hint> = emptyList(),
    // Appended (not inserted) so existing positional constructors keep working;
    // JSON decode matches by name, so wire position is irrelevant. (2026-07-24)
    val aliases: List<String> = emptyList(),
)

@Serializable
data class Structured(
    val name: String? = null,
    val description: String? = null,
    val exits: List<Exit>? = null,
    val entities: List<Entity>? = null,
    val objects: List<RoomObject>? = null,
    val hints: List<Hint>? = null,
    val properties: Map<String, String>? = null,
    val zone: String? = null,
)

@Serializable
data class ContentBlock(
    val format: String,
    val data: JsonElement = JsonNull,
    val fallback: String = "",
)

// ── Topology (§N1) ──

@Serializable
data class TopologySnapshot(
    val centerRoomId: String,
    val nodes: List<MapNode> = emptyList(),
    val edges: List<MapEdge> = emptyList(),
)

@Serializable
data class MapNode(
    val roomId: String,
    val name: String? = null,
    // zone may be absent/null on the wire for some rooms — keep nullable so a
    // single null never breaks the whole MapData decode (which is caught and
    // silently dropped, so `map` in a real topology room rendered NOTHING). 2026-07-24
    val zone: String? = null,
    val current: Boolean = false,
    val visited: Boolean = false,
    val hopsFromCenter: Int = 0,
)

@Serializable
data class MapEdge(
    val fromRoomId: String,
    val toRoomId: String,
    // direction/label ride from Exit, whose label is frequently null (most
    // exits carry no human label). A null here previously threw during
    // parseS2CMessage → the whole map frame was dropped, so `map` looked broken
    // on the phone for any room with exits. Keep both nullable. 2026-07-24
    val direction: String? = null,
    val label: String? = null,
    val hasReturn: Boolean = true,
)
