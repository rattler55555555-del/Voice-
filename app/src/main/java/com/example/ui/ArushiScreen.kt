package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPink
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.AssistantState
import com.example.viewmodel.ArushiViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArushiScreen(
    viewModel: ArushiViewModel,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit
) {
    val state by viewModel.assistantState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val transcript by viewModel.geminiLiveService.transcript.collectAsState()
    val lastAction by viewModel.geminiLiveService.lastActionMessage.collectAsState()
    val inputAmp by viewModel.audioRecordManager.inputAmplitude.collectAsState()
    val outputAmp by viewModel.audioTrackPlayer.outputAmplitude.collectAsState()
    val logs by viewModel.logs.collectAsState()

    var showLogsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Arushi",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(NeonViolet.copy(alpha = 0.2f))
                                .border(1.dp, NeonViolet.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "LIVE AI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface.copy(alpha = 0.7f)
                ),
                actions = {
                    // Speaker Diagnostic Test Button
                    IconButton(
                        onClick = { viewModel.runSpeakerTest() },
                        modifier = Modifier.testTag("test_speaker_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Test Speaker (440Hz Tone)",
                            tint = NeonCyan
                        )
                    }

                    // Debug Logs Button
                    IconButton(
                        onClick = { showLogsDialog = true },
                        modifier = Modifier.testTag("debug_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Diagnostics Logs",
                            tint = TextSecondary
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Error banner if any
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.15f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(StatusRed, NeonPink)))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFFB4AB),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = Color(0xFFFFB4AB)
                            )
                        }
                    }
                }
            }

            // Top Status & Action Badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                StatePill(state = state)

                Spacer(modifier = Modifier.height(10.dp))

                if (lastAction != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricBlue.copy(alpha = 0.2f))
                            .border(1.dp, ElectricBlue.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = lastAction ?: "",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Center: Futuristic Holographic Glowing Orb
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                HolographicVoiceOrb(
                    state = state,
                    inputAmplitude = inputAmp,
                    outputAmplitude = outputAmp,
                    onOrbClick = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else {
                            viewModel.toggleAssistant(hasMicPermission)
                        }
                    }
                )

                // Interrupt button when Arushi is speaking
                if (state == AssistantState.SPEAKING) {
                    Button(
                        onClick = { viewModel.interruptSpeaking() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .testTag("interrupt_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop speaking",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tap to Interrupt", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // Bottom Section: Transcript & Controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Live Speech Transcript Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkBorder, NeonViolet.copy(alpha = 0.4f))))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val displayText = when {
                            transcript.isNotBlank() -> transcript
                            state == AssistantState.LISTENING -> "Listening to your voice... Speak in any language!"
                            state == AssistantState.CONNECTING -> "Connecting real-time live session..."
                            state == AssistantState.SPEAKING -> "Arushi is speaking..."
                            else -> "Tap the microphone orb to begin speaking with Arushi"
                        }
                        Text(
                            text = displayText,
                            color = if (transcript.isNotBlank()) TextPrimary else TextMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Suggestion chips
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(label = "\"Hello Arushi\"") {
                        viewModel.sendQuickPrompt("Hello Arushi, who are you?")
                    }
                    SuggestionChip(label = "\"Hindi mein bolo\"") {
                        viewModel.sendQuickPrompt("Arushi, Hindi mein baat karo aur apna intro do.")
                    }
                    SuggestionChip(label = "\"WhatsApp kholo\"") {
                        viewModel.sendQuickPrompt("Open WhatsApp")
                    }
                    SuggestionChip(label = "\"Call Mom\"") {
                        onRequestContactsPermission()
                        viewModel.sendQuickPrompt("Call Mom")
                    }
                    SuggestionChip(label = "\"Open YouTube\"") {
                        viewModel.sendQuickPrompt("Open YouTube")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Mic / Power Activation Button
                PrimaryMicButton(
                    state = state,
                    onClick = {
                        if (!hasMicPermission) {
                            onRequestMicPermission()
                        } else {
                            viewModel.toggleAssistant(hasMicPermission)
                        }
                    }
                )
            }
        }
    }

    // Diagnostics Logs Dialog
    if (showLogsDialog) {
        DiagnosticsLogsDialog(
            logs = logs,
            onClear = { viewModel.clearLogs() },
            onDismiss = { showLogsDialog = false }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        ApiKeySettingsDialog(
            currentKey = viewModel.getEffectiveApiKey(),
            onSave = { viewModel.setCustomApiKey(it) },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun StatePill(state: AssistantState) {
    val (bgColor, borderColor, textColor, text) = when (state) {
        AssistantState.IDLE -> Quad(DarkSurfaceElevated, DarkBorder, TextSecondary, "IDLE • TAP TO START")
        AssistantState.CONNECTING -> Quad(StatusAmber.copy(alpha = 0.2f), StatusAmber, StatusAmber, "CONNECTING LIVE...")
        AssistantState.LISTENING -> Quad(NeonCyan.copy(alpha = 0.15f), NeonCyan, NeonCyan, "LISTENING • SPEAK NOW")
        AssistantState.SPEAKING -> Quad(NeonPink.copy(alpha = 0.15f), NeonPink, NeonPink, "ARUSHI SPEAKING...")
        AssistantState.ERROR -> Quad(StatusRed.copy(alpha = 0.15f), StatusRed, StatusRed, "ERROR • TAP TO RETRY")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun HolographicVoiceOrb(
    state: AssistantState,
    inputAmplitude: Float,
    outputAmplitude: Float,
    onOrbClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val activeAmp = when (state) {
        AssistantState.LISTENING -> inputAmplitude
        AssistantState.SPEAKING -> outputAmplitude
        else -> 0f
    }

    val dynamicScale = (1f + activeAmp * 0.45f) * (if (state != AssistantState.IDLE) pulse else 1f)

    Box(
        modifier = Modifier
            .size(240.dp)
            .clickable(onClick = onOrbClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * 0.7f

            val ringColor1 = when (state) {
                AssistantState.SPEAKING -> NeonPink
                AssistantState.LISTENING -> NeonCyan
                AssistantState.CONNECTING -> StatusAmber
                AssistantState.ERROR -> StatusRed
                AssistantState.IDLE -> NeonViolet
            }

            val ringColor2 = when (state) {
                AssistantState.SPEAKING -> NeonViolet
                AssistantState.LISTENING -> ElectricBlue
                AssistantState.CONNECTING -> NeonPink
                AssistantState.ERROR -> NeonPink
                AssistantState.IDLE -> ElectricBlue
            }

            // Outer soundwave aura
            if (activeAmp > 0.05f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor1.copy(alpha = activeAmp * 0.35f), Color.Transparent),
                        center = center,
                        radius = baseRadius * 1.5f
                    ),
                    radius = baseRadius * 1.4f,
                    center = center
                )
            }

            // Outer orbiting ring
            drawCircle(
                color = ringColor1.copy(alpha = 0.45f),
                radius = baseRadius * dynamicScale * 0.95f,
                center = center,
                style = Stroke(width = 2.5f)
            )

            // Middle glowing ring
            drawCircle(
                color = ringColor2.copy(alpha = 0.65f),
                radius = baseRadius * dynamicScale * 0.8f,
                center = center,
                style = Stroke(width = 3.5f)
            )

            // Inner core gradient sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        ringColor1.copy(alpha = 0.9f),
                        ringColor2.copy(alpha = 0.7f),
                        DarkSurfaceElevated
                    ),
                    center = center,
                    radius = baseRadius * 0.65f
                ),
                radius = baseRadius * 0.65f * dynamicScale,
                center = center
            )

            // Center glow spark
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 8.dp.toPx() * (1f + activeAmp),
                center = center
            )
        }
    }
}

@Composable
private fun PrimaryMicButton(
    state: AssistantState,
    onClick: () -> Unit
) {
    val (buttonColor, icon, text) = when (state) {
        AssistantState.IDLE -> Triple(NeonViolet, Icons.Default.Mic, "START ARUSHI")
        AssistantState.CONNECTING -> Triple(StatusAmber, Icons.Default.Mic, "CONNECTING...")
        AssistantState.LISTENING -> Triple(NeonCyan, Icons.Default.Mic, "LISTENING (TAP TO STOP)")
        AssistantState.SPEAKING -> Triple(NeonPink, Icons.Default.MicOff, "ARUSHI SPEAKING")
        AssistantState.ERROR -> Triple(StatusRed, Icons.Default.Mic, "RETRY")
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .shadow(12.dp, RoundedCornerShape(28.dp), ambientColor = buttonColor, spotColor = buttonColor)
            .testTag("mic_power_button")
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = DarkBackground,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = DarkBackground,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurfaceElevated,
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(DarkBorder, NeonCyan.copy(alpha = 0.3f)))),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DiagnosticsLogsDialog(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Audio & Live Diagnostics", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = TextMuted)
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF04060C))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No diagnostics logs yet. Start speaking to stream logs.", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(logs) { log ->
                            val color = when {
                                log.contains("error", ignoreCase = true) || log.contains("failed", ignoreCase = true) -> StatusRed
                                log.contains("audio", ignoreCase = true) -> NeonCyan
                                log.contains("connected", ignoreCase = true) -> StatusGreen
                                else -> TextSecondary
                            }
                            Text(
                                text = log,
                                color = color,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet)
            ) {
                Text("Close", color = TextPrimary)
            }
        },
        containerColor = DarkSurface
    )
}

@Composable
private fun ApiKeySettingsDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Gemini API Key Configuration", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Arushi uses the Gemini Live & REST Audio APIs. The key can be set in the AI Studio Secrets panel as GEMINI_API_KEY, or entered below for testing:",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("AIzaSy...", color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = DarkBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(keyInput)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet)
            ) {
                Text("Save", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = DarkSurface
    )
}
