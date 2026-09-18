package com.example.network

import android.util.Log
import com.example.model.OmtTransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance low-latency packetizer and network transmitter for OMT streams.
 * Handles MTU chunking, sequence numbering, microsecond timestamps, and socket transmission.
 */
class OmtPacketTransmitter {
    companion object {
        private const val TAG = "OmtPacketTransmitter"
        private const val MAX_PAYLOAD_SIZE = 1380 // Safe MTU size below 1500 to avoid IP fragmentation
        private const val OMT_MAGIC: Short = 0x4F4D // "OM" in ASCII
        private const val RTP_PAYLOAD_TYPE_H264 = 96
    }

    private var socket: DatagramSocket? = null
    private var destinationAddress: InetAddress? = null
    private var destinationPort: Int = 9998
    private var transportMode: OmtTransportMode = OmtTransportMode.OMT_UDP

    private val isRunning = AtomicBoolean(false)
    private var sequenceNumber: Short = 0
    private var rtpSequenceNumber: Short = 0
    private val ssrc: Int = (Math.random() * Int.MAX_VALUE).toInt()

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    val totalPacketsSent: Long get() = totalPackets.get()
    val totalBytesSent: Long get() = totalBytes.get()

    suspend fun connect(
        host: String,
        port: Int,
        mode: OmtTransportMode
    ) = withContext(Dispatchers.IO) {
        disconnect()
        try {
            destinationAddress = InetAddress.getByName(host)
            destinationPort = port
            transportMode = mode
            val sock = DatagramSocket().apply {
                sendBufferSize = 1024 * 1024 // 1MB buffer for low latency burst handling
                trafficClass = 0x10 // Low-delay IPTOS
            }
            socket = sock
            isRunning.set(true)
            sequenceNumber = 0
            rtpSequenceNumber = 0
            totalPackets.set(0)
            totalBytes.set(0)
            Log.i(TAG, "Connected to $host:$port using mode $mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize socket to $host:$port", e)
            throw e
        }
    }

    fun disconnect() {
        isRunning.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }

    fun isConnected(): Boolean = isRunning.get() && socket != null

    /**
     * Packetizes an H.264 video NAL unit or frame payload and transmits via UDP/OMT.
     */
    fun sendVideoFrame(frameData: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        if (!isRunning.get()) return
        val currentSocket = socket ?: return
        val address = destinationAddress ?: return

        try {
            when (transportMode) {
                OmtTransportMode.OMT_UDP -> {
                    sendOmtChunked(currentSocket, address, destinationPort, frameData, presentationTimeUs, isKeyFrame, 0x01)
                }
                OmtTransportMode.RTP_H264 -> {
                    sendRtpH264(currentSocket, address, destinationPort, frameData, presentationTimeUs)
                }
                OmtTransportMode.RAW_SOCKET -> {
                    sendRawUdp(currentSocket, address, destinationPort, frameData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending frame packet: ${e.message}")
        }
    }

    /**
     * Packetizes AAC/Opus audio payload.
     */
    fun sendAudioData(audioData: ByteArray, presentationTimeUs: Long) {
        if (!isRunning.get()) return
        val currentSocket = socket ?: return
        val address = destinationAddress ?: return

        try {
            if (transportMode == OmtTransportMode.OMT_UDP) {
                sendOmtChunked(currentSocket, address, destinationPort, audioData, presentationTimeUs, false, 0x02)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio packet: ${e.message}")
        }
    }

    /**
     * OMT Protocol Framing:
     * [0..1] Magic (0x4F4D "OM")
     * [2] Version (1)
     * [3] Media Type (0x01 = Video, 0x02 = Audio)
     * [4..5] Sequence Number
     * [6..7] Flags (Bit 0: Keyframe, Bit 1: Fragment Start, Bit 2: Fragment End)
     * [8..15] Presentation Timestamp (microseconds)
     * [16..17] Chunk Index
     * [18..19] Total Chunks
     * [20..] Payload bytes
     */
    private fun sendOmtChunked(
        sock: DatagramSocket,
        address: InetAddress,
        port: Int,
        data: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        mediaType: Byte
    ) {
        val totalLen = data.size
        val numChunks = ((totalLen + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE).coerceAtLeast(1)
        val headerSize = 20

        for (i in 0 until numChunks) {
            val offset = i * MAX_PAYLOAD_SIZE
            val chunkSize = (totalLen - offset).coerceAtMost(MAX_PAYLOAD_SIZE)
            val packetBuf = ByteBuffer.allocate(headerSize + chunkSize)

            val flags: Short = (
                (if (isKeyFrame) 0x01 else 0x00) or
                (if (i == 0) 0x02 else 0x00) or
                (if (i == numChunks - 1) 0x04 else 0x00)
            ).toShort()

            packetBuf.putShort(OMT_MAGIC)
            packetBuf.put(1.toByte()) // version
            packetBuf.put(mediaType) // 1=video, 2=audio
            packetBuf.putShort(sequenceNumber++)
            packetBuf.putShort(flags)
            packetBuf.putLong(ptsUs)
            packetBuf.putShort(i.toShort())
            packetBuf.putShort(numChunks.toShort())
            packetBuf.put(data, offset, chunkSize)

            val bytesToSend = packetBuf.array()
            val packet = DatagramPacket(bytesToSend, bytesToSend.size, address, port)
            sock.send(packet)

            totalPackets.incrementAndGet()
            totalBytes.addAndGet(bytesToSend.size.toLong())
        }
    }

    /**
     * Standard RFC 6184 RTP fragmentation (FU-A or Single NAL) for H.264
     */
    private fun sendRtpH264(
        sock: DatagramSocket,
        address: InetAddress,
        port: Int,
        data: ByteArray,
        ptsUs: Long
    ) {
        // Strip 3-byte or 4-byte start codes (0x000001 or 0x00000001) if present
        var startOffset = 0
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            startOffset = 4
        } else if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            startOffset = 3
        }
        val nalSize = data.size - startOffset
        if (nalSize <= 0) return

        val nalHeader = data[startOffset]
        val nalType = (nalHeader.toInt() and 0x1F)
        val rtpTimestamp = (ptsUs * 90 / 1000).toInt() // 90 kHz clock for video

        if (nalSize <= MAX_PAYLOAD_SIZE) {
            // Single NAL unit packet
            val rtpHeader = ByteBuffer.allocate(12 + nalSize)
            putRtpHeader(rtpHeader, marker = true, rtpTimestamp)
            rtpHeader.put(data, startOffset, nalSize)
            val packetBytes = rtpHeader.array()
            sock.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
            totalPackets.incrementAndGet()
            totalBytes.addAndGet(packetBytes.size.toLong())
        } else {
            // FU-A Fragmentation
            val nalDataOffset = startOffset + 1
            val payloadSize = nalSize - 1
            val numChunks = ((payloadSize + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE).coerceAtLeast(1)

            val fuIndicator = (nalHeader.toInt() and 0xE0 or 28).toByte()

            for (i in 0 until numChunks) {
                val offset = nalDataOffset + (i * MAX_PAYLOAD_SIZE)
                val chunkSize = (nalSize - (offset - startOffset)).coerceAtMost(MAX_PAYLOAD_SIZE)
                val isStart = (i == 0)
                val isEnd = (i == numChunks - 1)

                var fuHeader = (nalType and 0x1F)
                if (isStart) fuHeader = fuHeader or 0x80
                if (isEnd) fuHeader = fuHeader or 0x40

                val rtpPacket = ByteBuffer.allocate(12 + 2 + chunkSize)
                putRtpHeader(rtpPacket, marker = isEnd, rtpTimestamp)
                rtpPacket.put(fuIndicator)
                rtpPacket.put(fuHeader.toByte())
                rtpPacket.put(data, offset, chunkSize)

                val packetBytes = rtpPacket.array()
                sock.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
                totalPackets.incrementAndGet()
                totalBytes.addAndGet(packetBytes.size.toLong())
            }
        }
    }

    private fun putRtpHeader(buf: ByteBuffer, marker: Boolean, timestamp: Int) {
        val vPXM = 0x80 // V=2, P=0, X=0, CC=0
        val mPt = (if (marker) 0x80 else 0x00) or (RTP_PAYLOAD_TYPE_H264 and 0x7F)
        buf.put(vPXM.toByte())
        buf.put(mPt.toByte())
        buf.putShort(rtpSequenceNumber++)
        buf.putInt(timestamp)
        buf.putInt(ssrc)
    }

    private fun sendRawUdp(
        sock: DatagramSocket,
        address: InetAddress,
        port: Int,
        data: ByteArray
    ) {
        val chunks = ((data.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE).coerceAtLeast(1)
        for (i in 0 until chunks) {
            val offset = i * MAX_PAYLOAD_SIZE
            val len = (data.size - offset).coerceAtMost(MAX_PAYLOAD_SIZE)
            val packet = DatagramPacket(data, offset, len, address, port)
            sock.send(packet)
            totalPackets.incrementAndGet()
            totalBytes.addAndGet(len.toLong())
        }
    }
}
