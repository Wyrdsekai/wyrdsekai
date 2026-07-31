package org.wyrdsekai.app.state

import org.wyrdsekai.app.protocol.*

/** A single entry in the prose stream. */
data class ProseEntry(
    val speaker: String,
    val text: String,
    val priority: PriorityLevel,
    val isAiGenerated: Boolean = false,
    val hints: List<Hint> = emptyList(),
    val blocks: List<ContentBlock> = emptyList(),
)

/** Token stream accumulator for a single source. */
data class TokenStreamBuffer(
    val source: String,
    val tokens: StringBuilder = StringBuilder(),
    val context: String? = null,
)

/** Saved connection for the connect screen. */
data class SavedConnection(
    val name: String,
    val serverUrl: String,
    val username: String,
    val token: String? = null,
)
