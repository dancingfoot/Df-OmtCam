package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.StreamConfig
import com.example.model.StreamPreset
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamSettingsSheet(
    config: StreamConfig,
    deviceIp: String,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    onPortChanged: (String) -> Unit,
    onStreamIdChanged: (String) -> Unit,
    onManualIpChanged: (String) -> Unit,
    onPresetChanged: (StreamPreset) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateCard,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "OMT Broadcast Settings",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Compatible with OBS OMT Source & vMix",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // OBS / VMIX CONNECTION INSTRUCTIONS CARD
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = DeepSpace,
                border = androidx.compose.foundation.BorderStroke(1.dp, SlateBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "How to connect in OBS / vMix",
                            color = EmeraldSuccess,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. In OBS, add an 'OMT Source' (requires OBS 31+ with OMT Plugin).\n2. Look for '${config.streamId}' in the source drop-down list.\n3. If your Wi-Fi blocks Bonjour discovery, enter this phone's IP directly in OBS:",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = SlateBorder.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Phone IP: ${deviceIp.ifEmpty { "Check Wi-Fi connection" }} : ${config.port}",
                            color = CyanAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. STREAM / CAMERA NAME
            Text(
                text = "CAMERA NAME (mDNS BROADCAST)",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = config.streamId,
                onValueChange = onStreamIdChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stream_id_input"),
                label = { Text("Camera Name") },
                placeholder = { Text("e.g. Cam-1, DF-OmtCamera") },
                singleLine = true,
                enabled = !isStreaming,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = SlateBorder,
                    focusedLabelColor = CyanAccent,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // Preset Quick-Name Suggestions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Cam-1", "Cam-2", "Mobile-OMT", "DF-OmtCamera").forEach { suggestion ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isStreaming) { onStreamIdChanged(suggestion) },
                        color = SlateBorder.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = suggestion,
                            color = CyanAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. OMT PORT
            Text(
                text = "OMT PORT (OBS default: 6400)",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = config.port.toString(),
                onValueChange = onPortChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("target_port_input"),
                label = { Text("Port (6400 - 6600)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isStreaming,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = SlateBorder,
                    focusedLabelColor = CyanAccent,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // Quick Port selection buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(6400, 6401, 6402, 6403).forEach { p ->
                    val isCurrent = config.port == p
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isStreaming) { onPortChanged(p.toString()) },
                        color = if (isCurrent) CyanAccent.copy(alpha = 0.2f) else SlateBorder.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.dp, CyanAccent) else null
                    ) {
                        Text(
                            text = ":$p",
                            color = if (isCurrent) CyanAccent else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. OPTIONAL MANUAL TARGET PC IP (Fallback for strict router multicast filtering)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TARGET PC IP (OPTIONAL FALLBACK)",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = config.manualTargetIp,
                onValueChange = onManualIpChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_target_ip_input"),
                label = { Text("PC IP (Leave blank for auto-discovery)") },
                placeholder = { Text("e.g. 192.168.1.50 (Optional)") },
                supportingText = {
                    Text(
                        text = "Leave empty for standard mDNS auto-discovery. If your router blocks multicast, enter your PC's IP here.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                },
                singleLine = true,
                enabled = !isStreaming,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = SlateBorder,
                    focusedLabelColor = CyanAccent,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. RESOLUTION & FPS PRESET
            Text(
                text = "RESOLUTION & QUALITY",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))

            StreamPreset.entries.forEach { preset ->
                val selected = config.preset == preset
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) DeepSpace else SlateSurface)
                        .border(1.dp, if (selected) CyanAccent else SlateBorder, RoundedCornerShape(10.dp))
                        .clickable(enabled = !isStreaming) { onPresetChanged(preset) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = preset.label,
                            color = if (selected) CyanAccent else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${preset.width}x${preset.height} • ${preset.fps} FPS • ${preset.bitrateKbps} kbps",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = CyanAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CyanAccent,
                    contentColor = DeepSpace
                )
            ) {
                Text(
                    text = "Done",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
