package org.wyrdsekai.app.ui

import androidx.compose.runtime.Composable

/**
 * Camera QR scanning for wyrdphone:// invites (/P5).
 *
 * Android: CameraX preview + ML Kit barcode scanning (mirrors the RN
 * client's expo-camera scanner). iOS/desktop: not available — the paste
 * path covers the same invite payload (a QR encodes the same URL).
 */
expect val qrScanningSupported: Boolean

/**
 * Full-screen scanner pane. Calls [onResult] once with the first decoded
 * QR payload, [onDismiss] when the user cancels or permission is denied.
 */
@Composable
expect fun QrScannerPane(onResult: (String) -> Unit, onDismiss: () -> Unit)
