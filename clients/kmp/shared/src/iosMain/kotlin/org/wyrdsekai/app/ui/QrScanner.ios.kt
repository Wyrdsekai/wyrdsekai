@file:OptIn(ExperimentalForeignApi::class)

package org.wyrdsekai.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

// AVFoundation scanner (mirrors the Android ML Kit actual): camera preview
// via AVCaptureVideoPreviewLayer in a UIKitView, QR decode via
// AVCaptureMetadataOutput. The simulator has no camera — the coordinator
// fails to build a session there and the pane shows a no-camera message
// with the same qr-cancel escape hatch.
actual val qrScanningSupported: Boolean = true

@Composable
actual fun QrScannerPane(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    var hasPermission by remember {
        mutableStateOf(
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
                AVAuthorizationStatusAuthorized
        )
    }
    var denied by remember {
        mutableStateOf(
            !hasPermission &&
                AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) !=
                AVAuthorizationStatusNotDetermined
        )
    }
    LaunchedEffect(Unit) {
        if (!hasPermission && !denied) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                dispatch_async(dispatch_get_main_queue()) {
                    hasPermission = granted
                    if (!granted) denied = true
                }
            }
        }
    }

    when {
        hasPermission -> CameraQrPreview(onResult, onDismiss)
        denied -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is needed to scan invites.")
            OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("qr-cancel")) {
                Text("Close")
            }
        }
        else -> Box(Modifier.fillMaxSize()) // awaiting the permission dialog
    }
}

@Composable
private fun CameraQrPreview(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val coordinator = remember { QrCaptureCoordinator(onResult) }
    val cameraAvailable = remember { coordinator.start() }

    Box(Modifier.fillMaxSize().testTag("qr-scanner")) {
        if (cameraAvailable) {
            UIKitView(
                factory = { QrPreviewView(coordinator.session) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No camera available — paste the invite instead.")
            }
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .testTag("qr-cancel"),
        ) {
            Text("Cancel")
        }
    }

    DisposableEffect(Unit) {
        onDispose { coordinator.stop() }
    }
}

/**
 * Owns the capture session and receives metadata callbacks on the main
 * queue. Delivers the first decoded QR payload exactly once (the output
 * keeps firing per frame, like ML Kit on Android).
 */
private class QrCaptureCoordinator(
    private val onCode: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    val session = AVCaptureSession()
    private var delivered = false

    /** Builds the session; false when no camera or QR decode unavailable. */
    fun start(): Boolean {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return false
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
            ?: return false
        if (!session.canAddInput(input)) return false
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) return false
        session.addOutput(output)
        // Only legal AFTER addOutput; setting an unsupported type raises an
        // ObjC exception K/N can't catch, so gate on availability.
        if (!output.availableMetadataObjectTypes.contains(AVMetadataObjectTypeQRCode)) {
            return false
        }
        output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

        // startRunning blocks until the session is live — keep it off main.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            session.startRunning()
        }
        return true
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        if (delivered) return
        val value = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
        if (value != null) {
            delivered = true
            onCode(value)
        }
    }

    fun stop() {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            if (session.running) session.stopRunning()
        }
    }
}

/** UIView hosting the preview layer; tracks bounds through layout. */
private class QrPreviewView(session: AVCaptureSession) : UIView(frame = CGRectZero.readValue()) {
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    init {
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }
}
