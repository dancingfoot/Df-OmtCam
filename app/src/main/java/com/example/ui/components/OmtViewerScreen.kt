package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.LiveRed
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

data class ViewerState(
    val isListening: Boolean = false,
    val listeningPort: Int = 9998,
    val senderIp: String = "None",
    val receivedFps: Double = 0.0,
    val receivedBitrateKbps: Long = 0,
    val receivedPackets: Long = 0,
    val receivedBytes: Long = 0,
    val streamResolution: String = "Waiting for stream..."
)

@Composable
fun OmtViewerScreen(
    viewerState: ViewerState,
    onToggleListen: (Int) -> Unit,
    onSwitchToCamera: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var portInput by remember { mutableStateOf(viewerState.listeningPort.toString()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepSpace)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // HEADER BAR
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (viewerState.isListening) EmeraldSuccess else TextMuted)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (viewerState.isListening) "RECEIVING" else "STANDBY",
                    color = if (viewerState.isListening) EmeraldSuccess else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Button(
                onClick = onSwitchToCamera,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlateSurface,
                    contentColor = CyanAccent
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Switch to Camera Mode",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("CAM", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // CENTER MONITOR CANVAS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(SlateSurface, DeepSpace)
                    )
                )
                .border(1.dp, if (viewerState.isListening) CyanAccent.copy(alpha = 0.5f) else SlateBorder, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = "OMT Display",
                    tint = if (viewerState.isListening) CyanAccent else TextMuted,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (viewerState.isListening) "Live OMT Low-Latency Stream Active" else "Ready to Receive OMT Stream",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (viewerState.isListening) "Source: ${viewerState.senderIp}" else "Set receiver port and tap Start Listening",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (viewerState.isListening) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = DeepSpace.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("FPS", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text("${viewerState.receivedFps}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanAccent, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("BITRATE", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text("${viewerState.receivedBitrateKbps}k", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmeraldSuccess, fontFamily = FontFamily.Monospace)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PACKETS", fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                                Text("${viewerState.receivedPackets}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // BOTTOM CONTROLS
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SlateCard)
                .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("viewer_port_input"),
                    label = { Text("UDP Listening Port") },
                    singleLine = true,
                    enabled = !viewerState.isListening,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = SlateBorder,
                        focusedLabelColor = CyanAccent,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: 9998
                        onToggleListen(port)
                    },
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("viewer_toggle_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewerState.isListening) LiveRed else CyanAccent,
                        contentColor = if (viewerState.isListening) Color.White else DeepSpace
                    )
                ) {
                    Icon(
                        imageVector = if (viewerState.isListening) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (viewerState.isListening) "Stop Listening" else "Start Listening"
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (viewerState.isListening) "STOP" else "LISTEN",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
