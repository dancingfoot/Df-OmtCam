package com.example.service

import android.content.Context
import android.media.Image
import android.util.Log
import com.example.media.OmtAudioCapturer
import com.example.media.OmtVideoConverter
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
import com.example.network.OmtDiscoveryPublisher
import com.example.network.OmtTcpServer
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
 * Live OMT 1.0 Streaming Engine:
 * - Runs PROTOCOL.md-compliant OMT TCP Server on configured port (default 6400)
 * - Registers compliant mDNS (`_omt._tcp`) service `DeviceModel (SourceName)`
 * - Converts CameraX YUV_420_888 frames to UYVY when a receiver is subscribed to Video
 * - Captures 48kHz mono 'FPA1' float planar audio when a receiver is subscribed to Audio
 */
class OmtStreamingEngine(context: Context) {
    companion object {
        private const val TAG = "OmtStreamingEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val tcpServer = OmtTcpServer()
    private val discoveryPublisher = OmtDiscoveryPublisher(context)

    private var audioCapturer: OmtAudioCapturer? = null
    private var activeConfig: StreamConfig = StreamConfig()
    private var reusableUyvyBuffer: ByteArray? = null
    private val isStreamingActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val _metrics = MutableStateFlow(
        StreamMetrics(deviceIp = discoveryPublisher.getLocalWifiIpAddress())
    )
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private var telemetryJob: Job? = null
    private val frameCounter = AtomicLong(0)
    private var lastBytesCount = 0L
    private var lastTelemetryTime = System.currentTimeMillis()

    fun startStreaming(config: StreamConfig) {
        stopStreaming()
        activeConfig = config

        scope.launch(Dispatchers.IO) {
            try {
                tcpServer.start(config.port)
                val advertisedName = discoveryPublisher.registerService(
                    sourceName = config.streamId,
                    port = config.port
                )
                val ip = discoveryPublisher.getLocalWifiIpAddress()

                if (config.audioEnabled) {
                    audioCapturer = OmtAudioCapturer { fpa1Bytes, sampleRate, samplesPerChannel, tsNs ->
                        if (tcpServer.hasAudioSubscribers()) {
                            tcpServer.sendAudioFrame(
                                fpa1Data = fpa1Bytes,
                                sampleRate = sampleRate,
                                samplesPerChannel = samplesPerChannel,
                                channels = 1,
                                timestampNs = tsNs
                            )
                        }
                    }.apply { start() }
                }

                isStreamingActive.value = true
                _metrics.value = _metrics.value.copy(
                    isStreaming = true,
                    deviceIp = ip,
                    advertisedName = advertisedName,
                    activeReceivers = tcpServer.connectedReceiversCount,
                    lastError = null
                )

                startTelemetryLoop()
                Log.i(TAG, "OMT 1.0 stream started as '$advertisedName' on $ip:${config.port}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OMT engine: ${e.message}", e)
                _metrics.value = _metrics.value.copy(
                    isStreaming = false,
                    lastError = "Start failed: ${e.message}"
                )
                stopStreaming()
            }
        }
    }

    fun stopStreaming() {
        isStreamingActive.value = false
        telemetryJob?.cancel()
        telemetryJob = null

        discoveryPublisher.unregisterService()

        audioCapturer?.stop()
        audioCapturer = null

        tcpServer.stop()
        reusableUyvyBuffer = null

        _metrics.value = _metrics.value.copy(
            isStreaming = false,
            liveFps = 0.0,
            liveBitrateKbps = 0,
            activeReceivers = 0,
            advertisedName = "",
            lastError = null
        )
    }

    fun processCameraImage(image: Image, timestampNs: Long) {
        if (!isStreamingActive.value || !tcpServer.hasVideoSubscribers()) return

        val (uyvyBytes, dims) = OmtVideoConverter.convertYuv420ToUyvy(image, reusableUyvyBuffer)
        reusableUyvyBuffer = uyvyBytes

        tcpServer.sendVideoFrame(
            uyvyData = uyvyBytes,
            width = dims.first,
            height = dims.second,
            fps = activeConfig.preset.fps,
            timestampNs = timestampNs
        )
        frameCounter.incrementAndGet()
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        lastBytesCount = tcpServer.totalBytesSent
        frameCounter.set(0)
        lastTelemetryTime = System.currentTimeMillis()

        telemetryJob = scope.launch {
            while (isActive && isStreamingActive.value) {
                delay(1000)
                val now = System.currentTimeMillis()
                val deltaSec = (now - lastTelemetryTime) / 1000.0

                if (deltaSec >= 0.5) {
                    val currentBytes = tcpServer.totalBytesSent
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
                        totalPacketsSent = tcpServer.totalPacketsSent,
                        activeReceivers = tcpServer.connectedReceiversCount,
                        estimatedLatencyMs = if (tcpServer.subscribedVideoCount > 0) 8 else 0
                    )
                }
            }
        }
    }
}
