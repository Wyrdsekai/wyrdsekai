package org.wyrdsekai.app.ui

import androidx.compose.ui.Modifier

/**
 * Enable `testTagsAsResourceId` for the receiver. Android-only: sets the
 * Compose semantic property so Espresso/UIAutomator (and Maestro) can match
 * `testTag(...)` via `id:` selectors. On Desktop/iOS this is a no-op since
 * those platforms expose test tags through other channels.
 */
expect fun Modifier.enableTestTagsAsResourceId(): Modifier
