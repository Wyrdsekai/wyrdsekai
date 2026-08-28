package org.wyrdsekai.app.rendering

import androidx.compose.runtime.Composable
import org.wyrdsekai.app.protocol.ContentBlock

/**
 * Interface for rendering zone-type-specific content blocks.
 * Implement this for each zone type (e.g., CodeZaiku, Homekit).
 */
interface ContentBlockRenderer {
    /** Returns true if this renderer can handle the given format. */
    fun canRender(format: String): Boolean

    /** Render the content block as a Composable. */
    @Composable
    fun Render(block: ContentBlock)
}

/**
 * Registry of content block renderers.
 * Clients register renderers at startup. Zone-type renderer packs
 * register when entering a zone that advertises their capabilities.
 *
 * Unknown formats fall back to prose text via [FallbackRenderer].
 */
class ContentBlockRegistry {
    private val renderers = mutableListOf<ContentBlockRenderer>()

    init {
        // Built-in fallback is always last
        renderers.add(FallbackRenderer)
    }

    fun register(renderer: ContentBlockRenderer) {
        // Insert before fallback
        renderers.add(renderers.size - 1, renderer)
    }

    fun findRenderer(format: String): ContentBlockRenderer {
        return renderers.first { it.canRender(format) }
    }

    fun canRenderRich(format: String): Boolean {
        // True if any non-fallback renderer handles this format
        return renderers.dropLast(1).any { it.canRender(format) }
    }

    companion object {
        /** Global content block registry. Zone renderer packs register here. */
        val global = ContentBlockRegistry()
    }
}
