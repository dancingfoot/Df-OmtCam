package com.example.viewmodel

import android.app.Application
import android.media.Image
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.OmtTransportMode
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
import com.example.model.StreamPreset
import com.example.service.OmtStreamingEngine
import com.example.ui.components.ViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AppMode {
    CAMERA_SENDER,
    STREAM_VIEWER
}

class OmtCamViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OmtStreamingEngine(application.applicationContext)

    private val _currentMode = MutableStateFlow(AppMode.CAMERA_SENDER)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _config = MutableStateFlow(StreamConfig())
    val config: StateFlow<StreamConfig> = _config.asStateFlow()

    val metrics: StateFlow<StreamMetrics> = engine.metrics.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StreamMetrics()
    )

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    // Viewer Mode State
    private val _viewerState = MutableStateFlow(ViewerState())
    val viewerState: StateFlow<ViewerState> = _viewerState.asStateFlow()

    private var viewerSocket: DatagramSocket? = null
    private val isViewerListening = AtomicBoolean(false)
    private var viewerThread: Thread? = null
    private var viewerTelemetryJob: Job? = null
    private val viewerPacketsCount = AtomicLong(0)
    private val viewerBytesCount = AtomicLong(0)
    private val viewerFramesCount = AtomicLong(0)

    fun switchAppMode(mode: AppMode) {
        if (_currentMode.value == mode) return
        if (_currentMode.value == AppMode.CAMERA_SENDER && metrics.value.isStreaming) {
            engine.stopStreaming()
        }
        if (_currentMode.value == AppMode.STREAM_VIEWER && _viewerState.value.isListening) {
            stopViewerListening()
        }
        _currentMode.value = mode
    }

    fun toggleStreaming() {
        val currentMetrics = metrics.value
        if (currentMetrics.isStreaming) {
            engine.stopStreaming()
        } else {
            engine.startStreaming(config.value)
        }
    }

    fun updateTargetHost(host: String) {
        _config.value = _config.value.copy(targetHost = host.trim())
    }

    fun updateTargetPort(portString: String) {
        val port = portString.toIntOrNull() ?: return
        _config.value = _config.value.copy(targetPort = port)
    }

    fun updateStreamId(id: String) {
        _config.value = _config.value.copy(streamId = id.trim())
    }

    fun updateTransportMode(mode: OmtTransportMode) {
        _config.value = _config.value.copy(transportMode = mode)
    }

    fun updatePreset(preset: StreamPreset) {
        _config.value = _config.value.copy(preset = preset)
    }

    fun toggleAudio() {
        _config.value = _config.value.copy(audioEnabled = !_config.value.audioEnabled)
    }

    fun toggleTorch() {
        _config.value = _config.value.copy(torchEnabled = !_config.value.torchEnabled)
    }

    fun switchCamera() {
        _config.value = _config.value.copy(isFrontCamera = !_config.value.isFrontCamera)
    }

    fun setSettingsVisible(visible: Boolean) {
        _showSettingsSheet.value = visible
    }

    fun requestKeyFrame() {
        engine.requestSyncFrame()
    }

    fun onCameraFrame(image: Image, timestampNs: Long) {
        engine.processCameraImage(image, timestampNs)
    }

    // Viewer controls
    fun toggleViewerListening(port: Int) {
        if (_viewerState.value.isListening) {
            stopViewerListening()
        } else {
            startViewerListening(port)
        }
    }

    private fun startViewerListening(port: Int) {
        stopViewerListening()
        try {
            viewerSocket = DatagramSocket(port).apply {
                receiveBufferSize = 2 * 1024 * 1024
            }
            isViewerListening.set(true)
            viewerPacketsCount.set(0)
            viewerBytesCount.set(0)
            viewerFramesCount.set(0)

            _viewerState.value = _viewerState.value.copy(
                isListening = true,
                listeningPort = port,
                senderIp = "Listening on port $port...",
                receivedPackets = 0,
                receivedBytes = 0
            )

            viewerThread = Thread({
                val buf = ByteArray(65535)
                val packet = DatagramPacket(buf, buf.size)
                while (isViewerListening.get()) {
                    try {
                        viewerSocket?.receive(packet)
                        val len = packet.length
                        if (len > 0) {
                            viewerPacketsCount.incrementAndGet()
                            viewerBytesCount.addAndGet(len.toLong())
                            val srcIp = packet.address.hostAddress ?: "Unknown"

                            // Detect OMT keyframe or frame end
                            if (len >= 20 && buf[0] == 0x4F.toByte() && buf[1] == 0x4D.toByte()) {
                                val flags = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                                if ((flags and 0x04) != 0) {
                                    viewerFramesCount.incrementAndGet()
                                }
                            } else {
                                viewerFramesCount.incrementAndGet()
                            }

                            if (_viewerState.value.senderIp != srcIp) {
                                _viewerState.value = _viewerState.value.copy(senderIp = srcIp)
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "OmtViewerWorker").apply { start() }

            startViewerTelemetry()
        } catch (e: Exception) {
            Log.e("OmtCamViewModel", "Failed to start viewer socket", e)
            _viewerState.value = _viewerState.value.copy(
                isListening = false,
                senderIp = "Error: ${e.message}"
            )
        }
    }

    private fun stopViewerListening() {
        isViewerListening.set(false)
        viewerTelemetryJob?.cancel()
        viewerTelemetryJob = null
        try {
            viewerSocket?.close()
        } catch (_: Exception) {}
        viewerSocket = null
        viewerThread?.interrupt()
        viewerThread = null

        _viewerState.value = _viewerState.value.copy(
            isListening = false,
            receivedFps = 0.0,
            receivedBitrateKbps = 0
        )
    }

    private fun startViewerTelemetry() {
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        viewerTelemetryJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && isViewerListening.get()) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).toDouble() / 1000.0
                if (elapsed > 0) {
                    val frames = viewerFramesCount.getAndSet(0)
                    val fps = frames / elapsed
                    val currentBytes = viewerBytesCount.get()
                    val diff = currentBytes - lastBytes
                    lastBytes = currentBytes
                    val kbps = ((diff * 8) / (elapsed * 1000)).toLong()

                    _viewerState.value = _viewerState.value.copy(
                        receivedFps = (fps * 10).toInt() / 10.0,
                        receivedBitrateKbps = kbps,
                        receivedPackets = viewerPacketsCount.get(),
                        receivedBytes = currentBytes
                    )
                    lastTime = now
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.stopStreaming()
        stopViewerListening()
    }
}
