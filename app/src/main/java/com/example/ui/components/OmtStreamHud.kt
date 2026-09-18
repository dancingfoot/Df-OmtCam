package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.LiveRed
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun OmtStreamHud(
    config: StreamConfig,
    metrics: StreamMetrics,
    onToggleStream: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleTorch: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestKeyFrame: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
    val liveAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // TOP HUD BAR: Title, Live Status Pill & Quick Info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SlateCard)
                .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Live Status Indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (metrics.isStreaming) LiveRed else TextMuted)
                        .then(if (metrics.isStreaming) Modifier.alpha(liveAlpha) else Modifier)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (metrics.isStreaming) "OMT LIVE" else "READY",
                    color = if (metrics.isStreaming) LiveRed else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${config.preset.fps}fps / ${config.preset.width}x${config.preset.height}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Target endpoint indicator
            Text(
                text = "${config.targetHost}:${config.targetPort}",
                color = CyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // ERROR BANNER (if any)
        AnimatedVisibility(visible = metrics.lastError != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = LiveRed.copy(alpha = 0.9f)
            ) {
                Text(
                    text = metrics.lastError ?: "",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // TELEMETRY STRIP (Visible when streaming)
        AnimatedVisibility(visible = metrics.isStreaming) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SlateCard)
                    .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryItem(label = "FPS", value = "${metrics.liveFps}")
                TelemetryItem(label = "BITRATE", value = "${metrics.liveBitrateKbps}k")
                TelemetryItem(label = "LATENCY", value = "${metrics.estimatedLatencyMs}ms", color = EmeraldSuccess)
                TelemetryItem(label = "PACKETS", value = "${metrics.totalPacketsSent}")
            }
        }

        // BOTTOM CONTROLS DOCK
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(SlateCard, DeepSpace.copy(alpha = 0.95f))
                    )
                )
                .border(1.dp, SlateBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Audio toggle
            FilledTonalIconButton(
                onClick = onToggleAudio,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("toggle_audio_button"),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (config.audioEnabled) CyanAccent.copy(alpha = 0.15f) else SlateBorder,
                    contentColor = if (config.audioEnabled) CyanAccent else TextMuted
                )
            ) {
                Icon(
                    imageVector = if (config.audioEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Toggle Audio"
                )
            }

            // Torch toggle
            FilledTonalIconButton(
                onClick = onToggleTorch,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("toggle_torch_button"),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (config.torchEnabled) AmberWarning.copy(alpha = 0.15f) else SlateBorder,
                    contentColor = if (config.torchEnabled) AmberWarning else TextMuted
                )
            ) {
                Icon(
                    imageVector = if (config.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Toggle Flashlight"
                )
            }

            // PRIMARY STREAM TOGGLE BUTTON
            Button(
                onClick = onToggleStream,
                modifier = Modifier
                    .height(52.dp)
                    .padding(horizontal = 4.dp)
                    .testTag("stream_toggle_button"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (metrics.isStreaming) LiveRed else CyanAccent,
                    contentColor = if (metrics.isStreaming) Color.White else DeepSpace
                )
            ) {
                Icon(
                    imageVector = if (metrics.isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (metrics.isStreaming) "Stop Streaming" else "Start Streaming",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (metrics.isStreaming) "STOP" else "GO LIVE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Switch Camera
            FilledTonalIconButton(
                onClick = onSwitchCamera,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("switch_camera_button"),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = SlateBorder,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera Front/Back"
                )
            }

            // Settings Sheet Toggle
            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("open_settings_button"),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = SlateBorder,
                    contentColor = TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Stream Configuration"
                )
            }
        }
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, color: Color = TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}
