package com.example.model

/**
 * Resolution & FPS presets for OMT/VMX1 streaming.
 */
enum class StreamPreset(
    val label: String,
    val shortName: String,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    RES_360P("360p Smooth Wi-Fi (640x360 @ 30fps)", "360p", 640, 360, 30),
    RES_720P("720p HD (1280x720 @ 30fps)", "720p", 1280, 720, 30),
    RES_1080P("1080p Full HD (1920x1080 @ 30fps)", "1080p", 1920, 1080, 30),
    RES_4K("4K Ultra HD (3840x2160 @ 30fps)", "4K", 3840, 2160, 30)
}

/**
 * VMX1 quality presets matching OMT / opencamera-omt profiles.
 */
enum class VmxQuality(val label: String) {
    LOW("Low (~43 Mbps)"),
    MEDIUM("Medium (~100 Mbps)"),
    HIGH("High (~130 Mbps)")
}

/**
 * OMT Streaming Configuration:
 * - port: Fixed OMT TCP sender port (default 6400, standard OMT range 6400-6600)
 * - streamId: Source name advertised in mDNS as `DeviceModel (StreamName)`
 */
data class StreamConfig(
    val port: Int = 6400,
    val streamId: String = "Camera",
    val preset: StreamPreset = StreamPreset.RES_720P,
    val quality: VmxQuality = VmxQuality.MEDIUM,
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
    val advertisedName: String = "",
    val lastError: String? = null
)
