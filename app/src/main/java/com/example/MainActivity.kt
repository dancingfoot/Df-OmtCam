package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.CameraStreamPreview
import com.example.ui.components.OmtStreamHud
import com.example.ui.components.OmtViewerScreen
import com.example.ui.components.PermissionRationaleView
import com.example.ui.components.StreamSettingsSheet
import com.example.ui.theme.DeepSpace
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.AppMode
import com.example.viewmodel.OmtCamViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DeepSpace
                ) { _ ->
                    OmtCamApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OmtCamApp(
    viewModel: OmtCamViewModel = viewModel()
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    // Request permissions automatically on start so camera starts immediately
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    val currentMode by viewModel.currentMode.collectAsState()
    val config by viewModel.config.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val showSettings by viewModel.showSettingsSheet.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
    ) {
        if (currentMode == AppMode.STREAM_VIEWER) {
            // Android OMT Viewer Mode
            OmtViewerScreen(
                viewerState = viewerState,
                onToggleListen = { port -> viewModel.toggleViewerListening(port) },
                onSwitchToCamera = { viewModel.switchAppMode(AppMode.CAMERA_SENDER) },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        } else {
            // Camera Broadcast Sender Mode
            if (permissionsState.allPermissionsGranted) {
                // Camera preview immediately starts in full-screen
                CameraStreamPreview(
                    config = config,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )

                // Transparent overlay HUD with status, telemetry, controls, dock
                OmtStreamHud(
                    config = config,
                    metrics = metrics,
                    onToggleStream = { viewModel.toggleStreaming() },
                    onToggleAudio = { viewModel.toggleAudio() },
                    onToggleTorch = { viewModel.toggleTorch() },
                    onSwitchCamera = { viewModel.switchCamera() },
                    onOpenSettings = { viewModel.setSettingsVisible(true) },
                    onRequestKeyFrame = { viewModel.requestKeyFrame() },
                    onPresetChanged = { viewModel.updatePreset(it) },
                    onSwitchToViewer = { viewModel.switchAppMode(AppMode.STREAM_VIEWER) },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                )

                if (showSettings) {
                    StreamSettingsSheet(
                        config = config,
                        isStreaming = metrics.isStreaming,
                        onDismiss = { viewModel.setSettingsVisible(false) },
                        onHostChanged = { viewModel.updateTargetHost(it) },
                        onPortChanged = { viewModel.updateTargetPort(it) },
                        onStreamIdChanged = { viewModel.updateStreamId(it) },
                        onTransportModeChanged = { viewModel.updateTransportMode(it) },
                        onPresetChanged = { viewModel.updatePreset(it) }
                    )
                }
            } else {
                // Permission request prompt if denied
                PermissionRationaleView(
                    onRequestPermission = { permissionsState.launchMultiplePermissionRequest() }
                )
            }
        }
    }
}
