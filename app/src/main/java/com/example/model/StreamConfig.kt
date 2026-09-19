package com.example.model

enum class OmtTransportMode(val displayName: String, val description: String) {
    OMT_TCP_SERVER("OMT Standard TCP Server", "Listens for OMT PC receivers (vMix/OBS) on port 6400+ with mDNS"),
    OMT_TCP("OMT Unicast TCP Client", "Length-prefixed OMTMediaFrame over persistent low-latency TCP"),
    OMT_UDP("OMT Direct UDP", "Framed NAL/TS elementary stream with OMT packet header"),
    RTP_H264("RTP / H.264 (RFC 6184)", "Standard ultra-low latency RTP packetization for OBS/vMix"),
    RAW_SOCKET("Raw Video Socket", "Direct low-overhead TCP/UDP socket streaming")
}

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

data class StreamConfig(
    val targetHost: String = "192.168.1.100",
    val targetPort: Int = 6400,
    val streamId: String = "DF-OmtCamera",
    val transportMode: OmtTransportMode = OmtTransportMode.OMT_TCP_SERVER,
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
    val connectedEndpoint: String = "",
    val activeClients: Int = 0,
    val lastError: String? = null
)
