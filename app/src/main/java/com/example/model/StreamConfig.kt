package com.example.model

enum class OmtTransportMode(val displayName: String, val description: String) {
    OMT_UDP("OMT Direct UDP", "Framed NAL/TS elementary stream with OMT packet header"),
    RTP_H264("RTP / H.264 (RFC 6184)", "Standard ultra-low latency RTP packetization for OBS/vMix"),
    RAW_SOCKET("Raw Video Socket", "Direct low-overhead TCP/UDP socket streaming")
}

enum class StreamPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    ULTRA_LOW_LATENCY("Ultra Low Latency (720p @ 30fps - 2.5 Mbps)", 1280, 720, 30, 2500),
    HIGH_FRAME_RATE("High Speed (720p @ 60fps - 3.5 Mbps)", 1280, 720, 60, 3500),
    FULL_HD("Broadcast 1080p (1080p @ 30fps - 5.0 Mbps)", 1920, 1080, 30, 5000)
}

data class StreamConfig(
    val targetHost: String = "192.168.1.100",
    val targetPort: Int = 9998,
    val streamId: String = "omt-cam-01",
    val transportMode: OmtTransportMode = OmtTransportMode.OMT_UDP,
    val preset: StreamPreset = StreamPreset.ULTRA_LOW_LATENCY,
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
    val connectedEndpoint: String = "",
    val lastError: String? = null
)
