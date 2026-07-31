package org.wyrdsekai.app.rendering

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.protocol.ContentBlock

/**
 * Fallback renderer — handles any format by rendering the fallback prose text.
 * This is always the last renderer in the registry.
 */
object FallbackRenderer : ContentBlockRenderer {
    override fun canRender(format: String): Boolean = true

    @Composable
    override fun Render(block: ContentBlock) {
        if (block.fallback.isNotEmpty()) {
            Text(
                text = block.fallback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
        }
    }
}
