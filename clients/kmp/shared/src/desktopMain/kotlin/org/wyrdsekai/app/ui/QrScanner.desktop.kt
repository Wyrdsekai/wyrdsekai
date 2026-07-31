package org.wyrdsekai.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

actual val qrScanningSupported: Boolean = false

@Composable
actual fun QrScannerPane(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    // No camera scanning on desktop — paste the invite URL instead.
    LaunchedEffect(Unit) { onDismiss() }
}
