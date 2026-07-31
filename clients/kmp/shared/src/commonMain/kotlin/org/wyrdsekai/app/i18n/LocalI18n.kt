package org.wyrdsekai.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

/**
 * Composition-local accessor for [UiStrings].
 * Default is English; override via [ProvideUiStrings].
 */
val LocalUiStrings = compositionLocalOf { uiStringsFor("en") }

/**
 * Provides the [UiStrings] for [locale] to the composition subtree.
 */
@Composable
fun ProvideUiStrings(locale: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiStrings provides uiStringsFor(locale)) {
        content()
    }
}
