package com.example.service

import android.content.Context
import android.media.Image
import android.util.Log
import com.example.encoder.LowLatencyAudioEncoder
import com.example.encoder.LowLatencyVideoEncoder
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
import com.example.network.OmtDiscoveryPublisher
import com.example.network.OmtPacketTransmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Live OMT streaming engine:
 * - Runs standard OMT TCP Server on port (for OBS OMT Source / vMix connection)
 * - Broadcasts UDP packets to local network & manual target IP (if configured)
 * - Publishes Bonjour/mDNS service `_omt._tcp` so OBS/vMix lists it automatically
 */
class OmtStreamingEngine(private val context: Context) {
    companion object {
        private const val TAG = "OmtStreamingEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val transmitter = OmtPacketTransmitter()
    private val discoveryPublisher = OmtDiscoveryPublisher(context)

    private var videoEncoder: LowLatencyVideoEncoder? = null
    private var audioEncoder: LowLatencyAudioEncoder? = null

    private val _metrics = MutableStateFlow(StreamMetrics())
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private var telemetryJob: Job? = null
    private val frameCounter = AtomicLong(0)
    private var lastBytesCount = 0L
    private var lastTelemetryTime = System.currentTimeMillis()

    init {
        val ip = transmitter.getLocalWifiIpAddress()
        _metrics.value = _metrics.value.copy(deviceIp = ip)
    }

    fun startStreaming(config: StreamConfig) {
        scope.launch {
            try {
                Log.i(TAG, "Starting OMT engine on port ${config.port} (manual target: '${config.manualTargetIp}')")
                transmitter.start(config.port, config.manualTargetIp)

                // Announce camera stream on local network via mDNS for auto-discovery in OBS/vMix
                discoveryPublisher.registerService(
                    serviceName = config.streamId.ifEmpty { "DF-OmtCamera" },
                    port = config.port
                )

                // Initialize Video Encoder
                val preset = config.preset
                videoEncoder = LowLatencyVideoEncoder(
                    width = preset.width,
                    height = preset.height,
                    fps = preset.fps,
                    bitrateKbps = preset.bitrateKbps,
                    onFrameEncoded = { frameData, ptsUs, isKeyFrame ->
                        transmitter.sendVideoFrame(
                            frameData = frameData,
                            presentationTimeUs = ptsUs,
                            isKeyFrame = isKeyFrame
                        )
                        frameCounter.incrementAndGet()
                    }
                ).apply { start() }

                // Initialize Audio Encoder if enabled
                if (config.audioEnabled) {
                    audioEncoder = LowLatencyAudioEncoder { audioChunk, ptsUs ->
                        transmitter.sendAudioData(audioChunk, ptsUs)
                    }.apply { start() }
                }

                val currentDeviceIp = transmitter.getLocalWifiIpAddress()

                _metrics.value = _metrics.value.copy(
                    isStreaming = true,
                    deviceIp = currentDeviceIp,
                    activeReceivers = transmitter.activeReceiversCount,
                    lastError = null
                )

                startTelemetryLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting stream", e)
                _metrics.value = _metrics.value.copy(
                    isStreaming = false,
                    lastError = "Start failed: ${e.message}"
                )
                stopStreaming()
            }
        }
    }

    fun stopStreaming() {
        telemetryJob?.cancel()
        telemetryJob = null

        discoveryPublisher.unregisterService()

        videoEncoder?.stop()
        videoEncoder = null

        audioEncoder?.stop()
        audioEncoder = null

        transmitter.stop()

        _metrics.value = _metrics.value.copy(
            isStreaming = false,
            liveFps = 0.0,
            liveBitrateKbps = 0,
            activeReceivers = 0,
            lastError = null
        )
    }

    fun requestSyncFrame() {
        videoEncoder?.requestKeyFrame()
    }

    fun processCameraImage(image: Image, timestampNs: Long) {
        videoEncoder?.encodeFrame(image, timestampNs)
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        lastBytesCount = transmitter.totalBytesSent
        frameCounter.set(0)
        lastTelemetryTime = System.currentTimeMillis()

        telemetryJob = scope.launch {
            while (isActive && transmitter.isConnected()) {
                delay(1000)
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastTelemetryTime) / 1000.0

                if (deltaSec >= 0.5) {
                    val currentBytes = transmitter.totalBytesSent
                    val currentFrames = frameCounter.getAndSet(0)

                    val fps = currentFrames / deltaSec
                    val bitrateKbps = (((currentBytes - lastBytesCount) * 8) / deltaSec / 1000).toLong()

                    lastBytesCount = currentBytes
                    lastTelemetryTime = now

                    _metrics.value = _metrics.value.copy(
                        isStreaming = true,
                        liveFps = (fps * 10).toInt() / 10.0,
                        liveBitrateKbps = bitrateKbps,
                        totalBytesSent = currentBytes,
                        totalPacketsSent = transmitter.totalPacketsSent,
                        activeReceivers = transmitter.activeReceiversCount,
                        estimatedLatencyMs = 20
                    )
                }
            }
        }
    }
}
