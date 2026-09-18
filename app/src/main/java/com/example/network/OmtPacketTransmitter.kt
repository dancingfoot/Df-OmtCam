package com.example.network

import android.util.Log
import com.example.model.OmtTransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Enhanced high-performance low-latency packetizer and network transmitter for OMT streams.
 * Supports:
 * 1. OMT_TCP_SERVER: Standard OMT broadcast server listening on port (e.g. 6400) for PC receivers
 * 2. OMT_TCP: Length-prefixed OMTMediaFrame unicast to a PC listener
 * 3. OMT_UDP: Microsecond PTS chunked UDP packets
 * 4. RTP_H264: RFC 6184 standard single-NAL and FU-A fragmentation
 * 5. RAW_SOCKET: Direct elementary stream UDP
 */
class OmtPacketTransmitter {
    companion object {
        private const val TAG = "OmtPacketTransmitter"
        private const val MAX_PAYLOAD_SIZE = 1380 // Safe MTU size below 1500 to avoid IP fragmentation
        private const val OMT_MAGIC: Short = 0x4F4D // "OM" in ASCII
        private const val RTP_PAYLOAD_TYPE_H264 = 96
    }

    // UDP Socket
    private var udpSocket: DatagramSocket? = null
    private var destinationAddress: InetAddress? = null
    private var destinationPort: Int = 9998

    // TCP Client
    private var tcpClientSocket: Socket? = null
    private var tcpClientOut: DataOutputStream? = null

    // TCP Server (Accepts incoming connections from OMT receivers, OBS, or vMix)
    private var tcpServerSocket: ServerSocket? = null
    private var tcpServerThread: Thread? = null
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val clientStreams = CopyOnWriteArrayList<DataOutputStream>()

    private var transportMode: OmtTransportMode = OmtTransportMode.OMT_UDP
    private val isRunning = AtomicBoolean(false)
    private var sequenceNumber: Short = 0
    private var rtpSequenceNumber: Short = 0
    private val ssrc: Int = (Math.random() * Int.MAX_VALUE).toInt()

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    val totalPacketsSent: Long get() = totalPackets.get()
    val totalBytesSent: Long get() = totalBytes.get()
    val activeClientsCount: Int get() = clientStreams.size

    suspend fun connect(
        host: String,
        port: Int,
        mode: OmtTransportMode
    ) = withContext(Dispatchers.IO) {
        disconnect()
        transportMode = mode
        destinationPort = port
        isRunning.set(true)
        sequenceNumber = 0
        rtpSequenceNumber = 0
        totalPackets.set(0)
        totalBytes.set(0)

        try {
            when (mode) {
                OmtTransportMode.OMT_TCP_SERVER -> {
                    startTcpServer(port)
                }
                OmtTransportMode.OMT_TCP -> {
                    startTcpClient(host, port)
                }
                OmtTransportMode.OMT_UDP,
                OmtTransportMode.RTP_H264,
                OmtTransportMode.RAW_SOCKET -> {
                    startUdp(host, port)
                }
            }
            Log.i(TAG, "Initialized transmitter in mode $mode on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize transmitter: ${e.message}", e)
            disconnect()
            throw e
        }
    }

    private fun startUdp(host: String, port: Int) {
        destinationAddress = InetAddress.getByName(host)
        destinationPort = port
        val sock = DatagramSocket().apply {
            sendBufferSize = 1024 * 1024
            trafficClass = 0x10 // Low-delay IPTOS
        }
        udpSocket = sock
    }

    private fun startTcpClient(host: String, port: Int) {
        val sock = Socket(host, port).apply {
            tcpNoDelay = true
            sendBufferSize = 1024 * 1024
        }
        tcpClientSocket = sock
        tcpClientOut = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 65536))
    }

    private fun startTcpServer(port: Int) {
        val server = ServerSocket(port, 10).apply {
            reuseAddress = true
        }
        tcpServerSocket = server

        tcpServerThread = Thread({
            Log.i(TAG, "OMT TCP Server listening on port $port for incoming connections")
            while (isRunning.get() && !server.isClosed) {
                try {
                    val client = server.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 1024 * 1024
                    }
                    Log.i(TAG, "Accepted OMT Receiver client connection from ${client.remoteSocketAddress}")
                    val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), 65536))
                    clientSockets.add(client)
                    clientStreams.add(out)
                } catch (e: Exception) {
                    if (!isRunning.get() || server.isClosed) break
                    Log.w(TAG, "Error accepting client: ${e.message}")
                }
            }
        }, "OmtTcpServerThread").apply { start() }
    }

    fun disconnect() {
        isRunning.set(false)
        // Close UDP
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        // Close TCP Client
        try {
            tcpClientOut?.close()
            tcpClientSocket?.close()
        } catch (_: Exception) {}
        tcpClientOut = null
        tcpClientSocket = null

        // Close TCP Server
        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {}
        tcpServerSocket = null

        tcpServerThread?.interrupt()
        tcpServerThread = null

        // Close all active clients
        for (client in clientSockets) {
            try { client.close() } catch (_: Exception) {}
        }
        clientSockets.clear()
        clientStreams.clear()
    }

    fun isConnected(): Boolean {
        if (!isRunning.get()) return false
        return when (transportMode) {
            OmtTransportMode.OMT_TCP_SERVER -> tcpServerSocket != null && !tcpServerSocket!!.isClosed
            OmtTransportMode.OMT_TCP -> tcpClientSocket?.isConnected == true && !tcpClientSocket!!.isClosed
            else -> udpSocket != null && !udpSocket!!.isClosed
        }
    }

    /**
     * Packetizes an H.264 video frame and transmits via configured OMT transport.
     */
    fun sendVideoFrame(frameData: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        if (!isRunning.get()) return

        try {
            when (transportMode) {
                OmtTransportMode.OMT_TCP_SERVER -> {
                    broadcastOmtMediaFrame(frameData, presentationTimeUs, isKeyFrame, 0x01)
                }
                OmtTransportMode.OMT_TCP -> {
                    sendOmtMediaFrameTcp(tcpClientOut, frameData, presentationTimeUs, isKeyFrame, 0x01)
                }
                OmtTransportMode.OMT_UDP -> {
                    val sock = udpSocket ?: return
                    val addr = destinationAddress ?: return
                    sendOmtChunked(sock, addr, destinationPort, frameData, presentationTimeUs, isKeyFrame, 0x01)
                }
                OmtTransportMode.RTP_H264 -> {
                    val sock = udpSocket ?: return
                    val addr = destinationAddress ?: return
                    sendRtpH264(sock, addr, destinationPort, frameData, presentationTimeUs)
                }
                OmtTransportMode.RAW_SOCKET -> {
                    val sock = udpSocket ?: return
                    val addr = destinationAddress ?: return
                    sendRawUdp(sock, addr, destinationPort, frameData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending video frame: ${e.message}")
        }
    }

    /**
     * Transmits AAC/Opus audio payload.
     */
    fun sendAudioData(audioData: ByteArray, presentationTimeUs: Long) {
        if (!isRunning.get()) return

        try {
            when (transportMode) {
                OmtTransportMode.OMT_TCP_SERVER -> {
                    broadcastOmtMediaFrame(audioData, presentationTimeUs, false, 0x02)
                }
                OmtTransportMode.OMT_TCP -> {
                    sendOmtMediaFrameTcp(tcpClientOut, audioData, presentationTimeUs, false, 0x02)
                }
                OmtTransportMode.OMT_UDP -> {
                    val sock = udpSocket ?: return
                    val addr = destinationAddress ?: return
                    sendOmtChunked(sock, addr, destinationPort, audioData, presentationTimeUs, false, 0x02)
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio data: ${e.message}")
        }
    }

    /**
     * OMTMediaFrame standard TCP framing:
     * [0..3] Total Frame Length (Int32 BigEndian)
     * [4..5] Magic (0x4F4D "OM")
     * [6] Version (1)
     * [7] Media Type (0x01 = Video, 0x02 = Audio)
     * [8..9] Flags (Bit 0: KeyFrame)
     * [10..17] Presentation Timestamp in microseconds (Int64)
     * [18..] Payload data
     */
    private fun sendOmtMediaFrameTcp(
        out: DataOutputStream?,
        payload: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        mediaType: Byte
    ) {
        if (out == null) return
        val flags: Short = if (isKeyFrame) 0x01 else 0x00
        val headerLen = 18
        val totalLen = headerLen + payload.size

        synchronized(out) {
            out.writeInt(totalLen)
            out.writeShort(OMT_MAGIC.toInt())
            out.writeByte(1) // version 1
            out.writeByte(mediaType.toInt())
            out.writeShort(flags.toInt())
            out.writeLong(ptsUs)
            out.write(payload)
            out.flush()
        }

        totalPackets.incrementAndGet()
        totalBytes.addAndGet((4 + totalLen).toLong())
    }

    private fun broadcastOmtMediaFrame(
        payload: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        mediaType: Byte
    ) {
        if (clientStreams.isEmpty()) return

        val flags: Short = if (isKeyFrame) 0x01 else 0x00
        val headerLen = 18
        val totalLen = headerLen + payload.size

        val deadClients = mutableListOf<DataOutputStream>()

        for (i in 0 until clientStreams.size) {
            val out = clientStreams.getOrNull(i) ?: continue
            try {
                synchronized(out) {
                    out.writeInt(totalLen)
                    out.writeShort(OMT_MAGIC.toInt())
                    out.writeByte(1)
                    out.writeByte(mediaType.toInt())
                    out.writeShort(flags.toInt())
                    out.writeLong(ptsUs)
                    out.write(payload)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client socket disconnected or write failed: ${e.message}")
                deadClients.add(out)
            }
        }

        if (deadClients.isNotEmpty()) {
            for (dead in deadClients) {
                val idx = clientStreams.indexOf(dead)
                if (idx != -1) {
                    clientStreams.removeAt(idx)
                    try {
                        val sock = clientSockets.removeAt(idx)
                        sock.close()
                    } catch (_: Exception) {}
                }
            }
        }

        totalPackets.incrementAndGet()
        totalBytes.addAndGet((4 + totalLen).toLong())
    }

    /**
     * OMT Protocol UDP Framing:
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
        val totalSize = data.size
        val totalChunks = ((totalSize + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE).coerceAtLeast(1)

        for (chunkIdx in 0 until totalChunks) {
            val offset = chunkIdx * MAX_PAYLOAD_SIZE
            val length = (totalSize - offset).coerceAtMost(MAX_PAYLOAD_SIZE)

            val isStart = chunkIdx == 0
            val isEnd = chunkIdx == totalChunks - 1

            var flags: Short = 0
            if (isKeyFrame) flags = (flags.toInt() or 0x01).toShort()
            if (isStart) flags = (flags.toInt() or 0x02).toShort()
            if (isEnd) flags = (flags.toInt() or 0x04).toShort()

            val packetBuffer = ByteBuffer.allocate(20 + length)
            packetBuffer.putShort(OMT_MAGIC)
            packetBuffer.put(1.toByte())
            packetBuffer.put(mediaType)
            packetBuffer.putShort(sequenceNumber)
            packetBuffer.putShort(flags)
            packetBuffer.putLong(ptsUs)
            packetBuffer.putShort(chunkIdx.toShort())
            packetBuffer.putShort(totalChunks.toShort())
            packetBuffer.put(data, offset, length)

            val packetBytes = packetBuffer.array()
            val datagram = DatagramPacket(packetBytes, packetBytes.size, address, port)
            sock.send(datagram)

            totalPackets.incrementAndGet()
            totalBytes.addAndGet(packetBytes.size.toLong())
            sequenceNumber = ((sequenceNumber.toInt() + 1) and 0xFFFF).toShort()
        }
    }

    /**
     * RFC 6184 RTP Packetization for H.264
     */
    private fun sendRtpH264(
        sock: DatagramSocket,
        address: InetAddress,
        port: Int,
        nalUnit: ByteArray,
        ptsUs: Long
    ) {
        if (nalUnit.isEmpty()) return
        val rtpTimestamp = (ptsUs * 90 / 1000).toInt()

        if (nalUnit.size <= MAX_PAYLOAD_SIZE) {
            val rtpBuffer = ByteBuffer.allocate(12 + nalUnit.size)
            writeRtpHeader(rtpBuffer, marker = true, timestamp = rtpTimestamp)
            rtpBuffer.put(nalUnit)

            val bytes = rtpBuffer.array()
            sock.send(DatagramPacket(bytes, bytes.size, address, port))
            totalPackets.incrementAndGet()
            totalBytes.addAndGet(bytes.size.toLong())
        } else {
            val nalHeader = nalUnit[0]
            val nalF = (nalHeader.toInt() and 0x80) != 0
            val nalNri = (nalHeader.toInt() and 0x60) shr 5
            val nalType = nalHeader.toInt() and 0x1F

            val fuIndicator = ((if (nalF) 0x80 else 0x00) or (nalNri shl 5) or 28).toByte()

            var offset = 1
            var remaining = nalUnit.size - 1

            while (remaining > 0) {
                val isStart = offset == 1
                val isEnd = remaining <= MAX_PAYLOAD_SIZE - 2
                val chunkSize = remaining.coerceAtMost(MAX_PAYLOAD_SIZE - 2)

                val startBit = if (isStart) 0x80 else 0x00
                val endBit = if (isEnd) 0x40 else 0x00
                val fuHeader = (startBit or endBit or nalType).toByte()

                val rtpBuffer = ByteBuffer.allocate(12 + 2 + chunkSize)
                writeRtpHeader(rtpBuffer, marker = isEnd, timestamp = rtpTimestamp)
                rtpBuffer.put(fuIndicator)
                rtpBuffer.put(fuHeader)
                rtpBuffer.put(nalUnit, offset, chunkSize)

                val bytes = rtpBuffer.array()
                sock.send(DatagramPacket(bytes, bytes.size, address, port))
                totalPackets.incrementAndGet()
                totalBytes.addAndGet(bytes.size.toLong())

                offset += chunkSize
                remaining -= chunkSize
            }
        }
    }

    private fun writeRtpHeader(buffer: ByteBuffer, marker: Boolean, timestamp: Int) {
        val vPxCc: Byte = 0x80.toByte() // V=2, P=0, X=0, CC=0
        val mPt: Byte = ((if (marker) 0x80 else 0x00) or (RTP_PAYLOAD_TYPE_H264 and 0x7F)).toByte()

        buffer.put(vPxCc)
        buffer.put(mPt)
        buffer.putShort(rtpSequenceNumber)
        buffer.putInt(timestamp)
        buffer.putInt(ssrc)

        rtpSequenceNumber = ((rtpSequenceNumber.toInt() + 1) and 0xFFFF).toShort()
    }

    private fun sendRawUdp(
        sock: DatagramSocket,
        address: InetAddress,
        port: Int,
        data: ByteArray
    ) {
        val totalSize = data.size
        var offset = 0
        while (offset < totalSize) {
            val length = (totalSize - offset).coerceAtMost(MAX_PAYLOAD_SIZE)
            val datagram = DatagramPacket(data, offset, length, address, port)
            sock.send(datagram)
            totalPackets.incrementAndGet()
            totalBytes.addAndGet(length.toLong())
            offset += length
        }
    }
}
