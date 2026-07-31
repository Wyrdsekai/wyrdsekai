package org.wyrdsekai.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.viewmodel.NodeViewModel

/**
 * BirthScreen — the gate that ensures the companion is present and ready
 * before the room appears.
 *
 * State machine:
 *   CHECKING_MODEL -> DOWNLOADING_MODEL -> LOADING_MODEL -> BOOTING_NODE ->
 *   COMPANION_ENTERING -> GENERATING_GREETING -> READY
 *
 * On first run (no model on disk), shows "Wyrd is being born..." with a
 * download progress bar. On subsequent launches (model cached), shows
 * "Wyrd is waking up..." and moves through the states quickly.
 *
 * The onReady callback fires with the PhoneNode and the companion's first
 * greeting text, then the caller transitions to LocalRoomScreen.
 */
@Composable
fun BirthScreen(
    companionName: String,
    nodeVM: NodeViewModel,
    scope: CoroutineScope,
    isFirstRun: Boolean,
    onReady: (PhoneNode, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // State machine
    var step by remember { mutableStateOf(BirthStep.CHECKING_MODEL) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    // Observe NodeManager model flows
    val modelStatus by nodeVM.nodeManager.modelStatus.collectAsState()
    val modelProgress by nodeVM.nodeManager.modelProgress.collectAsState()
    val nodeState by nodeVM.nodeState.collectAsState()

    // Map NodeManager states to BirthScreen steps
    LaunchedEffect(Unit) {
        // Start the node if not already started
        if (nodeState != "running" && nodeState != "starting") {
            step = BirthStep.BOOTING_NODE
            statusText = if (isFirstRun) {
                "$companionName is being born..."
            } else {
                "$companionName is waking up..."
            }
            nodeVM.startNode()
        }

        // Wait for node to start running (PhoneNode boots, companion enters room)
        nodeVM.nodeState.first { it == "running" || it == "error" }

        if (nodeVM.nodeState.value == "error") {
            statusText = nodeVM.errorMessage.value ?: "Failed to start"
            return@LaunchedEffect
        }

        // Node is running — companion is in room (Wave 1 guarantee)
        step = BirthStep.COMPANION_ENTERING
        statusText = "$companionName is entering the world..."
        delay(800) // Brief pause for visual weight

        // Now wait for model to be ready (it downloads/loads in background via NodeManager)
        // Observe model status changes
        step = BirthStep.CHECKING_MODEL
        statusText = "Checking for model..."

        // Wait for model status to settle
        nodeVM.nodeManager.modelStatus.first { it != "idle" && it != "checking" }

        when (nodeVM.nodeManager.modelStatus.value) {
            "downloading" -> {
                step = BirthStep.DOWNLOADING_MODEL
                isDownloading = true
                // Wait for download to complete
                nodeVM.nodeManager.modelStatus.first { it != "downloading" }
                isDownloading = false
            }
        }

        when (nodeVM.nodeManager.modelStatus.value) {
            "loading" -> {
                step = BirthStep.LOADING_MODEL
                statusText = "Loading model..."
                nodeVM.nodeManager.modelStatus.first { it != "loading" }
            }
        }

        // Model is ready (or unavailable — either way, companion can still work via remote)
        val phoneNode = nodeVM.nodeManager.phoneNode
        if (phoneNode == null) {
            statusText = "Node failed to initialize"
            return@LaunchedEffect
        }

        // Generate greeting
        // Companion is in the room — transition immediately
        // Greeting will happen naturally when the user enters the room
        val greeting = "*$companionName materializes in a shimmer of light, looking around curiously.*"

        step = BirthStep.READY
        delay(500) // Brief moment before transition
        onReady(phoneNode, greeting)
    }

    // Update download progress from NodeManager
    LaunchedEffect(modelProgress) {
        if (isDownloading) {
            downloadProgress = modelProgress
        }
    }

    // Update status text based on model status during download
    LaunchedEffect(modelStatus) {
        when (modelStatus) {
            "downloading" -> {
                val modelText = nodeVM.nodeManager.modelStatusText.value
                if (modelText != null) {
                    statusText = modelText
                } else {
                    statusText = if (isFirstRun) {
                        "$companionName is being born..."
                    } else {
                        "Downloading model..."
                    }
                }
            }
            "loading" -> statusText = "Loading model..."
            "ready" -> {} // Handled by main flow
            "unavailable" -> {} // Handled by main flow
        }
    }

    // UI
    Surface(
        modifier = modifier.fillMaxSize().testTag("birth-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Companion name — large, prominent
            Text(
                text = companionName,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 36.sp,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Status message
            AnimatedVisibility(
                visible = statusText.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Progress indicators
            when (step) {
                BirthStep.DOWNLOADING_MODEL -> {
                    // Determinate progress bar during download
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
                BirthStep.READY -> {
                    // No indicator — about to transition
                }
                else -> {
                    // Indeterminate spinner for all other states
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp).testTag("birth-spinner"),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
    }
}

/** Steps in the birth state machine. */
private enum class BirthStep {
    CHECKING_MODEL,
    DOWNLOADING_MODEL,
    LOADING_MODEL,
    BOOTING_NODE,
    COMPANION_ENTERING,
    GENERATING_GREETING,
    READY,
}
