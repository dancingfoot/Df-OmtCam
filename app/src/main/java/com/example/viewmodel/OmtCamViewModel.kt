package com.example.viewmodel

import android.app.Application
import android.media.Image
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.OmtTransportMode
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
import com.example.model.StreamPreset
import com.example.service.OmtStreamingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class OmtCamViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OmtStreamingEngine(application.applicationContext)

    private val _config = MutableStateFlow(StreamConfig())
    val config: StateFlow<StreamConfig> = _config.asStateFlow()

    val metrics: StateFlow<StreamMetrics> = engine.metrics.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StreamMetrics()
    )

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

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

    override fun onCleared() {
        super.onCleared()
        engine.stopStreaming()
    }
}
