package com.example.service

import android.content.Context
import android.media.Image
import android.util.Log
import com.example.encoder.LowLatencyAudioEncoder
import com.example.encoder.LowLatencyVideoEncoder
import com.example.model.StreamConfig
import com.example.model.StreamMetrics
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
 * Manages the live OMT streaming pipeline:
 * - Coordinates Video & Audio encoders
 * - Directs frames to the OmtPacketTransmitter
 * - Emits real-time network telemetry (FPS, Bitrate, Bytes, Latency)
 */
class OmtStreamingEngine(private val context: Context) {
    companion object {
        private const val TAG = "OmtStreamingEngine"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val transmitter = OmtPacketTransmitter()

    private var videoEncoder: LowLatencyVideoEncoder? = null
    private var audioEncoder: LowLatencyAudioEncoder? = null

    private val _metrics = MutableStateFlow(StreamMetrics())
    val metrics: StateFlow<StreamMetrics> = _metrics.asStateFlow()

    private var telemetryJob: Job? = null
    private val frameCounter = AtomicLong(0)
    private var lastBytesCount = 0L
    private var lastTelemetryTime = System.currentTimeMillis()

    fun startStreaming(config: StreamConfig) {
        scope.launch {
            try {
                Log.i(TAG, "Starting stream to ${config.targetHost}:${config.targetPort} via ${config.transportMode}")
                transmitter.connect(config.targetHost, config.targetPort, config.transportMode)

                // Initialize Video Encoder
                val preset = config.preset
                videoEncoder = LowLatencyVideoEncoder(
                    width = preset.width,
                    height = preset.height,
                    fps = preset.fps,
                    bitrateKbps = preset.bitrateKbps,
                    onFrameEncoded = { frameData, ptsUs, isKeyFrame ->
                        transmitter.sendVideoFrame(frameData, ptsUs, isKeyFrame)
                        frameCounter.incrementAndGet()
                    }
                ).apply { start() }

                // Initialize Audio Encoder if enabled
                if (config.audioEnabled) {
                    audioEncoder = LowLatencyAudioEncoder { audioChunk, ptsUs ->
                        transmitter.sendAudioData(audioChunk, ptsUs)
                    }.apply { start() }
                }

                _metrics.value = _metrics.value.copy(
                    isStreaming = true,
                    connectedEndpoint = "${config.targetHost}:${config.targetPort}",
                    lastError = null
                )

                startTelemetryLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting stream", e)
                _metrics.value = _metrics.value.copy(
                    isStreaming = false,
                    lastError = "Connection failed: ${e.message}"
                )
                stopStreaming()
            }
        }
    }

    fun stopStreaming() {
        telemetryJob?.cancel()
        telemetryJob = null

        videoEncoder?.stop()
        videoEncoder = null

        audioEncoder?.stop()
        audioEncoder = null

        transmitter.disconnect()

        _metrics.value = _metrics.value.copy(
            isStreaming = false,
            liveFps = 0.0,
            liveBitrateKbps = 0
        )
        Log.i(TAG, "Streaming stopped")
    }

    /**
     * Pass camera image from CameraX to the active encoder.
     */
    fun processCameraImage(image: Image, timestampNs: Long) {
        videoEncoder?.encodeFrame(image, timestampNs)
    }

    fun requestSyncFrame() {
        videoEncoder?.requestKeyFrame()
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        frameCounter.set(0)
        lastBytesCount = transmitter.totalBytesSent
        lastTelemetryTime = System.currentTimeMillis()

        telemetryJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastTelemetryTime).toDouble() / 1000.0
                if (elapsedSec > 0) {
                    val currentFrames = frameCounter.getAndSet(0)
                    val fps = currentFrames / elapsedSec

                    val currentBytes = transmitter.totalBytesSent
                    val bytesDiff = currentBytes - lastBytesCount
                    lastBytesCount = currentBytes
                    val bitrateKbps = ((bytesDiff * 8) / (elapsedSec * 1000)).toLong()

                    // Simulated one-way latency estimation for standard LAN UDP socket
                    val estimatedLatency = 12L + (bitrateKbps / 800L).coerceAtMost(18L)

                    _metrics.value = _metrics.value.copy(
                        liveFps = (fps * 10).toInt() / 10.0,
                        liveBitrateKbps = bitrateKbps,
                        totalBytesSent = currentBytes,
                        totalPacketsSent = transmitter.totalPacketsSent,
                        estimatedLatencyMs = estimatedLatency
                    )
                    lastTelemetryTime = now
                }
            }
        }
    }
}
