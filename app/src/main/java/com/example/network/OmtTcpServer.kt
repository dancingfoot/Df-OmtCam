package com.example.network

import android.util.Log
import com.example.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Open Media Transport (OMT) 1.0 TCP Server strictly implementing PROTOCOL.md:
 *
 * - All multi-byte fields are Little-Endian.
 * - 16-byte Frame Header:
 *   [0]     BYTE Version (1)
 *   [1]     BYTE FrameType (1=Metadata, 2=Video, 4=Audio)
 *   [2..9]  INT64 Timestamp (100ns units; 1 sec = 10,000,000)
 *   [10..11] UINT16 MetadataLength (UTF-8 XML length incl. '\0')
 *   [12..15] INT32 DataLength (ExtendedHeader + Data + MetadataLength)
 * - Waits for `<OMTSubscribe ... />` commands from each client before sending Video/Audio/Metadata.
 * - Handles `<OMTTally ... />` aggregation (preserving `Program==` per spec) and `<OMTInfo ... />`.
 * - Uses thread-per-client bounded async queues so slow receivers never stall camera capture.
 */
class OmtTcpServer {
    companion object {
        private const val TAG = "OmtTcpServer"

        const val OMT_PROTOCOL_VERSION: Byte = 1
        const val FRAME_TYPE_METADATA: Byte = 1
        const val FRAME_TYPE_VIDEO: Byte = 2
        const val FRAME_TYPE_AUDIO: Byte = 4

        const val HEADER_SIZE = 16
        const val VIDEO_EXT_HEADER_SIZE = 32
        const val AUDIO_EXT_HEADER_SIZE = 24

        val FOURCC_UYVY: Int = fourCc('U', 'Y', 'V', 'Y')
        val FOURCC_FPA1: Int = fourCc('F', 'P', 'A', '1')

        private fun fourCc(a: Char, b: Char, c: Char, d: Char): Int {
            return (a.code and 0xFF) or
                ((b.code and 0xFF) shl 8) or
                ((c.code and 0xFF) shl 16) or
                ((d.code and 0xFF) shl 24)
        }
    }

    private enum class OutboundKind { VIDEO, AUDIO, METADATA }

    private class OutboundPacket(
        val kind: OutboundKind,
        val bytes: ByteArray
    )

    private inner class ClientSession(
        val socket: Socket
    ) {
        val alive = AtomicBoolean(true)
        @Volatile var subscribedVideo = false
        @Volatile var subscribedAudio = false
        @Volatile var subscribedMetadata = false
        @Volatile var tallyPreview = false
        @Volatile var tallyProgram = false

        private val queue = ArrayBlockingQueue<OutboundPacket>(6)
        private val outStream = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
        private val inStream = BufferedInputStream(socket.getInputStream(), 16 * 1024)

        private val sendThread = Thread({ runSendLoop() }, "OmtClientSend").apply { start() }
        private val recvThread = Thread({ runRecvLoop() }, "OmtClientRecv").apply { start() }

        fun enqueue(packet: OutboundPacket) {
            if (!alive.get()) return
            when (packet.kind) {
                OutboundKind.VIDEO -> if (!subscribedVideo) return
                OutboundKind.AUDIO -> if (!subscribedAudio) return
                OutboundKind.METADATA -> if (!subscribedMetadata) return
            }
            if (!queue.offer(packet)) {
                // Drop oldest frame to keep latency near zero
                if (packet.kind == OutboundKind.VIDEO) {
                    queue.poll()
                    queue.offer(packet)
                }
            }
        }

        private fun runSendLoop() {
            try {
                while (alive.get() && isRunning.get()) {
                    val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    outStream.write(packet.bytes)
                    if (queue.isEmpty()) {
                        outStream.flush()
                    }
                    totalPackets.incrementAndGet()
                    totalBytes.addAndGet(packet.bytes.size.toLong())
                }
            } catch (e: Exception) {
                if (alive.get()) {
                    Log.w(TAG, "Client send closed (${socket.remoteSocketAddress}): ${e.message}")
                }
            } finally {
                close()
            }
        }

        private fun runRecvLoop() {
            val header = ByteArray(HEADER_SIZE)
            try {
                while (alive.get() && isRunning.get()) {
                    readFully(inStream, header, HEADER_SIZE)
                    val hdrBuf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    val version = hdrBuf.get().toInt() and 0xFF
                    val frameType = hdrBuf.get().toInt() and 0xFF
                    hdrBuf.getLong() // timestamp
                    hdrBuf.getShort() // metadataLength
                    val dataLength = hdrBuf.getInt()

                    if (version != OMT_PROTOCOL_VERSION.toInt()) {
                        Log.w(TAG, "Client sent unsupported OMT version $version")
                        break
                    }
                    if (dataLength < 0 || dataLength > 4 * 1024 * 1024) {
                        Log.w(TAG, "Client sent invalid dataLength=$dataLength")
                        break
                    }

                    if (dataLength > 0) {
                        val payload = ByteArray(dataLength)
                        readFully(inStream, payload, dataLength)
                        if (frameType == FRAME_TYPE_METADATA.toInt()) {
                            val xml = payload.toString(Charsets.UTF_8).trimEnd('\u0000', ' ', '\r', '\n')
                            handleClientMetadataCommand(this, xml)
                        }
                    }
                }
            } catch (_: EOFException) {
                Log.i(TAG, "OMT receiver disconnected: ${socket.remoteSocketAddress}")
            } catch (e: Exception) {
                if (alive.get() && isRunning.get()) {
                    Log.w(TAG, "Client recv closed (${socket.remoteSocketAddress}): ${e.message}")
                }
            } finally {
                close()
            }
        }

        fun close() {
            if (!alive.getAndSet(false)) return
            sessions.remove(this)
            try { socket.close() } catch (_: Exception) {}
            sendThread.interrupt()
            recvThread.interrupt()
            broadcastCombinedTally()
        }
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = CopyOnWriteArrayList<ClientSession>()
    private val isRunning = AtomicBoolean(false)

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    val totalPacketsSent: Long get() = totalPackets.get()
    val totalBytesSent: Long get() = totalBytes.get()
    val connectedReceiversCount: Int get() = sessions.count { it.alive.get() }
    val subscribedVideoCount: Int get() = sessions.count { it.alive.get() && it.subscribedVideo }

    fun start(port: Int) {
        stop()
        isRunning.set(true)
        totalPackets.set(0)
        totalBytes.set(0)

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = server

        acceptThread = Thread({
            Log.i(TAG, "OMT 1.0 TCP Server listening on port $port")
            while (isRunning.get() && !server.isClosed) {
                try {
                    val client = server.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 1024 * 1024
                        keepAlive = true
                    }
                    val session = ClientSession(client)
                    sessions.add(session)
                    Log.i(TAG, "OMT receiver connected: ${client.remoteSocketAddress}")
                } catch (e: Exception) {
                    if (!isRunning.get() || server.isClosed) break
                    Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }, "OmtTcpAccept").apply { start() }
    }

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        acceptThread?.interrupt()
        acceptThread = null

        for (session in sessions.toList()) {
            session.close()
        }
        sessions.clear()
    }

    fun hasVideoSubscribers(): Boolean {
        return isRunning.get() && sessions.any { it.alive.get() && it.subscribedVideo }
    }

    fun hasAudioSubscribers(): Boolean {
        return isRunning.get() && sessions.any { it.alive.get() && it.subscribedAudio }
    }

    fun sendVideoFrame(
        uyvyData: ByteArray,
        width: Int,
        height: Int,
        fps: Int,
        timestampNs: Long
    ) {
        if (!hasVideoSubscribers()) return

        val dataLength = VIDEO_EXT_HEADER_SIZE + uyvyData.size
        val timestamp100ns = timestampNs / 100L
        val colorSpace = if (height >= 720) 709 else 601

        val packetBytes = ByteBuffer.allocate(HEADER_SIZE + dataLength).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            // 16-byte OMT Header
            put(OMT_PROTOCOL_VERSION)
            put(FRAME_TYPE_VIDEO)
            putLong(timestamp100ns)
            putShort(0.toShort()) // MetadataLength
            putInt(dataLength)

            // 32-byte Video Extended Header
            putInt(FOURCC_UYVY)
            putInt(width)
            putInt(height)
            putInt(fps)
            putInt(1)
            putFloat(width.toFloat() / height.toFloat())
            putInt(0) // Progressive, no alpha
            putInt(colorSpace)

            // Raw UYVY frame data
            put(uyvyData)
        }.array()

        val packet = OutboundPacket(OutboundKind.VIDEO, packetBytes)
        for (session in sessions) {
            session.enqueue(packet)
        }
    }

    fun sendAudioFrame(
        fpa1Data: ByteArray,
        sampleRate: Int,
        samplesPerChannel: Int,
        channels: Int,
        timestampNs: Long
    ) {
        if (!hasAudioSubscribers()) return

        val dataLength = AUDIO_EXT_HEADER_SIZE + fpa1Data.size
        val timestamp100ns = timestampNs / 100L
        val activeMask = if (channels == 1) 1 else (1 shl channels) - 1

        val packetBytes = ByteBuffer.allocate(HEADER_SIZE + dataLength).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            // 16-byte OMT Header
            put(OMT_PROTOCOL_VERSION)
            put(FRAME_TYPE_AUDIO)
            putLong(timestamp100ns)
            putShort(0.toShort()) // MetadataLength
            putInt(dataLength)

            // 24-byte Audio Extended Header
            putInt(FOURCC_FPA1)
            putInt(sampleRate)
            putInt(samplesPerChannel)
            putInt(channels)
            putInt(activeMask)
            putInt(0) // Reserved1

            // 32-bit float planar audio samples
            put(fpa1Data)
        }.array()

        val packet = OutboundPacket(OutboundKind.AUDIO, packetBytes)
        for (session in sessions) {
            session.enqueue(packet)
        }
    }

    private fun handleClientMetadataCommand(session: ClientSession, xml: String) {
        Log.d(TAG, "OMT metadata from ${session.socket.remoteSocketAddress}: $xml")

        when {
            xml.contains("<OMTSubscribe Video=\"true\"") -> {
                session.subscribedVideo = true
                Log.i(TAG, "Receiver subscribed to VIDEO: ${session.socket.remoteSocketAddress}")
            }
            xml.contains("<OMTSubscribe Video=\"false\"") -> {
                session.subscribedVideo = false
            }
            xml.contains("<OMTSubscribe Audio=\"true\"") -> {
                session.subscribedAudio = true
                Log.i(TAG, "Receiver subscribed to AUDIO: ${session.socket.remoteSocketAddress}")
            }
            xml.contains("<OMTSubscribe Audio=\"false\"") -> {
                session.subscribedAudio = false
            }
            xml.contains("<OMTSubscribe Metadata=\"true\"") -> {
                session.subscribedMetadata = true
                Log.i(TAG, "Receiver subscribed to METADATA: ${session.socket.remoteSocketAddress}")
                val infoXml = "<OMTInfo ProductName=\"DF-OmtCamera\" Manufacturer=\"OmtCam\" Version=\"${BuildConfig.VERSION_NAME}\" />"
                session.enqueue(OutboundPacket(OutboundKind.METADATA, buildMetadataFrame(infoXml)))
                session.enqueue(OutboundPacket(OutboundKind.METADATA, buildMetadataFrame(currentCombinedTallyXml())))
            }
            xml.contains("<OMTSubscribe Metadata=\"false\"") -> {
                session.subscribedMetadata = false
            }
            xml.startsWith("<OMTTally") -> {
                session.tallyPreview = xml.contains("Preview=\"true\"")
                session.tallyProgram = xml.contains("Program==\"true\"") || xml.contains("Program=\"true\"")
                broadcastCombinedTally()
            }
            xml.startsWith("<OMTSettings") -> {
                Log.i(TAG, "OMT settings command: $xml")
            }
        }
    }

    private fun currentCombinedTallyXml(): String {
        val anyPreview = sessions.any { it.alive.get() && it.tallyPreview }
        val anyProgram = sessions.any { it.alive.get() && it.tallyProgram }
        // Keep exact `Program==` spelling required by PROTOCOL.md backwards compatibility
        return "<OMTTally Preview=\"$anyPreview\" Program==\"$anyProgram\" />"
    }

    private fun broadcastCombinedTally() {
        if (!isRunning.get()) return
        val packet = OutboundPacket(OutboundKind.METADATA, buildMetadataFrame(currentCombinedTallyXml()))
        for (session in sessions) {
            session.enqueue(packet)
        }
    }

    private fun buildMetadataFrame(xml: String): ByteArray {
        val xmlBytes = (xml + "\u0000").toByteArray(Charsets.UTF_8)
        val timestamp100ns = System.nanoTime() / 100L
        return ByteBuffer.allocate(HEADER_SIZE + xmlBytes.size).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(OMT_PROTOCOL_VERSION)
            put(FRAME_TYPE_METADATA)
            putLong(timestamp100ns)
            putShort(0.toShort())
            putInt(xmlBytes.size)
            put(xmlBytes)
        }.array()
    }

    private fun readFully(stream: InputStream, dst: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = stream.read(dst, offset, length - offset)
            if (read < 0) throw EOFException("Stream closed")
            offset += read
        }
    }
}
