package com.example.ui.components

import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.StreamConfig
import com.example.viewmodel.OmtCamViewModel
import java.util.concurrent.Executors

@Composable
fun CameraStreamPreview(
    config: StreamConfig,
    viewModel: OmtCamViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var currentPreviewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(config.torchEnabled, activeCamera) {
        try {
            if (activeCamera?.cameraInfo?.hasFlashUnit() == true) {
                activeCamera?.cameraControl?.enableTorch(config.torchEnabled)
            }
        } catch (e: Exception) {
            Log.w("CameraStreamPreview", "Failed to set torch: ${e.message}")
        }
    }

    // Re-bind when camera flips or resolution preset changes
    LaunchedEffect(config.isFrontCamera, config.preset, cameraProvider, currentPreviewView) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val pv = currentPreviewView ?: return@LaunchedEffect

        val cameraSelector = if (config.isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(pv.surfaceProvider)
        }

        val targetResolution = Size(config.preset.width, config.preset.height)
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val image = imageProxy.image
                if (image != null) {
                    viewModel.onCameraFrame(image, imageProxy.imageInfo.timestamp)
                }
            } catch (e: Exception) {
                Log.e("CameraStreamPreview", "Analyzer error: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        try {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            if (config.torchEnabled && activeCamera?.cameraInfo?.hasFlashUnit() == true) {
                activeCamera?.cameraControl?.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e("CameraStreamPreview", "Failed to bind camera: ${e.message}", e)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            currentPreviewView = previewView

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                } catch (e: Exception) {
                    Log.e("CameraStreamPreview", "Failed to get camera provider", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
