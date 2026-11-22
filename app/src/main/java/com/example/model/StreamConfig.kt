package com.example.model

/**
 * Clean resolution & quality presets matching opencamera-omt (720p, 1080p, 4K).
 */
enum class StreamPreset(
    val label: String,
    val shortName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    RES_720P("720p HD (1280x720 @ 30fps)", "720p", 1280, 720, 30, 2500),
    RES_720P_60("720p 60fps (1280x720 @ 60fps)", "720p60", 1280, 720, 60, 3500),
    RES_1080P("1080p Full HD (1920x1080 @ 30fps)", "1080p", 1920, 1080, 30, 5000),
    RES_4K("4K Ultra HD (3840x2160 @ 30fps)", "4K", 3840, 2160, 30, 15000)
}

/**
 * Flexible OMT Streaming Configuration:
 * - port: Listening port (default 6400, standard OMT sender range 6400-6600)
 * - streamId: Bonjour/mDNS service instance name shown in OBS/vMix dropdown
 * - manualTargetIp: Optional unicast IP. When empty, runs 100% zero-config (mDNS + TCP Server + Subnet Broadcast).
 *                   If user's router blocks mDNS/multicast, entering a target PC IP guarantees packet delivery!
 */
data class StreamConfig(
    val port: Int = 6400,
    val streamId: String = "DF-OmtCamera",
    val manualTargetIp: String = "",
    val preset: StreamPreset = StreamPreset.RES_720P,
    val audioEnabled: Boolean = true,
    val torchEnabled: Boolean = false,
    val isFrontCamera: Boolean = false
)

data class StreamMetrics(
    val isStreaming: Boolean = false,
    val liveFps: Double = 0.0,
    val liveBitrateKbps: Long = 0,
    val totalBytesSent: Long = 0,
    val totalPacketsSent: Long = 0,
    val estimatedLatencyMs: Long = 0,
    val activeReceivers: Int = 0,
    val deviceIp: String = "",
    val lastError: String? = null
)
