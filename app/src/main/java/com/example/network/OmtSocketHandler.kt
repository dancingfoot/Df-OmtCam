package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages raw TCP socket connections and data serialization strictly adhering
 * to the Open Media Transport (OMT) protocol and `opencamera-omt` structure.
 *
 * Serialization specification (OMTMediaFrame / OMT Wire Format):
 * ---------------------------------------------------------------
 * [0..3]   Total Frame Payload Length (Int32, Big-Endian)
 * [4..5]   OMT Magic Bytes: 0x4F4D ("OM")
 * [6]      Protocol Version (0x01)
 * [7]      Media Type (0x01 = Video, 0x02 = Audio, 0x03 = Metadata XML)
 * [8..9]   Frame Flags (Bit 0: KeyFrame (0x01), Bit 1: Discontinuity (0x02))
 * [10..17] Presentation Timestamp (PTS) in microseconds (Int64, Big-Endian)
 * [18..21] Video Width / Sample Rate (Int32, Big-Endian)
 * [22..25] Video Height / Channels (Int32, Big-Endian)
 * [26..29] Codec FourCC: 0x34363248 ("H264") or 0x00000000
 * [30..]   Raw Payload (NAL units / compressed audio / metadata)
 */
class OmtSocketHandler {
    companion object {
        private const val TAG = "OmtSocketHandler"
        const val OMT_MAGIC: Short = 0x4F4D // "OM"
        const val OMT_VERSION: Byte = 0x01

        const val MEDIA_TYPE_VIDEO: Byte = 0x01
        const val MEDIA_TYPE_AUDIO: Byte = 0x02
        const val MEDIA_TYPE_METADATA: Byte = 0x03

        const val FLAG_KEYFRAME: Short = 0x01
        const val FLAG_DISCONTINUITY: Short = 0x02

        const val FOURCC_H264: Int = 0x34363248 // 'H','2','6','4'
        const val FOURCC_AAC: Int = 0x00434141  // 'A','A','C'
        const val HEADER_SIZE = 30
    }

    enum class ConnectionRole {
        SERVER, // Listens on local port for OMT receivers/pull clients (vMix/OBS)
        CLIENT  // Connects out to a remote OMT receiver host and port
    }

    private val isRunning = AtomicBoolean(false)
    private var role: ConnectionRole = ConnectionRole.SERVER

    // Client connection state
    private var clientSocket: Socket? = null
    private var clientOutputStream: DataOutputStream? = null

    // Server connection state
    private var serverSocket: ServerSocket? = null
    private var serverAcceptThread: Thread? = null
    private val connectedClients = CopyOnWriteArrayList<Socket>()
    private val connectedOutputStreams = CopyOnWriteArrayList<DataOutputStream>()

    // Telemetry & counters
    private val totalFramesSent = AtomicLong(0)
    private val totalBytesSent = AtomicLong(0)

    val framesSent: Long get() = totalFramesSent.get()
    val bytesSent: Long get() = totalBytesSent.get()
    val clientCount: Int get() = if (role == ConnectionRole.SERVER) connectedClients.size else if (isConnected()) 1 else 0

    /**
     * Start listening as an OMT TCP server for incoming receiver connections (OBS, vMix, etc.)
     */
    suspend fun startServer(port: Int) = withContext(Dispatchers.IO) {
        close()
        role = ConnectionRole.SERVER
        isRunning.set(true)
        totalFramesSent.set(0)
        totalBytesSent.set(0)

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = server

        serverAcceptThread = Thread({
            Log.i(TAG, "OmtSocketHandler TCP Server listening on port $port")
            while (isRunning.get() && !server.isClosed) {
                try {
                    val socket = server.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 1024 * 1024
                        keepAlive = true
                    }
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
                    connectedClients.add(socket)
                    connectedOutputStreams.add(out)
                    Log.i(TAG, "OmtSocketHandler accepted receiver from ${socket.remoteSocketAddress} (total: ${connectedClients.size})")
                } catch (e: Exception) {
                    if (!isRunning.get() || server.isClosed) break
                    Log.w(TAG, "Exception accepting socket: ${e.message}")
                }
            }
        }, "OmtSocketServerThread").apply { start() }
    }

    /**
     * Connect directly as a client to a remote OMT receiver listening on host:port.
     */
    suspend fun connectClient(host: String, port: Int) = withContext(Dispatchers.IO) {
        close()
        role = ConnectionRole.CLIENT
        isRunning.set(true)
        totalFramesSent.set(0)
        totalBytesSent.set(0)

        try {
            val socket = Socket().apply {
                tcpNoDelay = true
                sendBufferSize = 1024 * 1024
                keepAlive = true
                connect(InetSocketAddress(InetAddress.getByName(host), port), 5000)
            }
            clientSocket = socket
            clientOutputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
            Log.i(TAG, "OmtSocketHandler connected to remote OMT receiver $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port: ${e.message}", e)
            close()
            throw e
        }
    }

    fun isConnected(): Boolean {
        if (!isRunning.get()) return false
        return when (role) {
            ConnectionRole.SERVER -> serverSocket != null && !serverSocket!!.isClosed && connectedClients.isNotEmpty()
            ConnectionRole.CLIENT -> clientSocket?.isConnected == true && !clientSocket!!.isClosed
        }
    }

    /**
     * Serializes and sends an OMT video frame adhering to the open-camera-omt structure.
     */
    fun sendVideoFrame(
        frameData: ByteArray,
        presentationTimeUs: Long,
        isKeyFrame: Boolean,
        width: Int = 1280,
        height: Int = 720
    ) {
        if (!isRunning.get()) return
        val flags = if (isKeyFrame) FLAG_KEYFRAME else 0x00

        val serializedFrame = serializeOmtFrame(
            mediaType = MEDIA_TYPE_VIDEO,
            flags = flags,
            ptsUs = presentationTimeUs,
            param1 = width,
            param2 = height,
            codec = FOURCC_H264,
            payload = frameData
        )

        dispatchSerializedFrame(serializedFrame)
    }

    /**
     * Serializes and sends an OMT audio frame adhering to the open-camera-omt structure.
     */
    fun sendAudioFrame(
        audioData: ByteArray,
        presentationTimeUs: Long,
        sampleRate: Int = 44100,
        channels: Int = 1
    ) {
        if (!isRunning.get()) return

        val serializedFrame = serializeOmtFrame(
            mediaType = MEDIA_TYPE_AUDIO,
            flags = 0,
            ptsUs = presentationTimeUs,
            param1 = sampleRate,
            param2 = channels,
            codec = FOURCC_AAC,
            payload = audioData
        )

        dispatchSerializedFrame(serializedFrame)
    }

    /**
     * Serializes and sends metadata (XML / JSON side data).
     */
    fun sendMetadata(
        metadataXml: String,
        presentationTimeUs: Long
    ) {
        if (!isRunning.get()) return
        val bytes = metadataXml.toByteArray(Charsets.UTF_8)
        val serializedFrame = serializeOmtFrame(
            mediaType = MEDIA_TYPE_METADATA,
            flags = 0,
            ptsUs = presentationTimeUs,
            param1 = 0,
            param2 = 0,
            codec = 0,
            payload = bytes
        )
        dispatchSerializedFrame(serializedFrame)
    }

    /**
     * Constructs the exact length-prefixed binary OMT frame buffer.
     */
    private fun serializeOmtFrame(
        mediaType: Byte,
        flags: Short,
        ptsUs: Long,
        param1: Int,
        param2: Int,
        codec: Int,
        payload: ByteArray
    ): ByteArray {
        val payloadLen = payload.size
        // 4 bytes length prefix + (HEADER_SIZE - 4) header bytes + payload
        val totalBuffer = ByteBuffer.allocate(HEADER_SIZE + payloadLen).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(HEADER_SIZE - 4 + payloadLen) // Length after prefix
            putShort(OMT_MAGIC)
            put(OMT_VERSION)
            put(mediaType)
            putShort(flags)
            putLong(ptsUs)
            putInt(param1)
            putInt(param2)
            putInt(codec)
            put(payload)
        }
        return totalBuffer.array()
    }

    /**
     * Dispatches serialized frame bytes across active TCP sockets.
     */
    private fun dispatchSerializedFrame(frameBytes: ByteArray) {
        when (role) {
            ConnectionRole.CLIENT -> {
                val out = clientOutputStream ?: return
                try {
                    synchronized(out) {
                        out.write(frameBytes)
                        out.flush()
                    }
                    totalFramesSent.incrementAndGet()
                    totalBytesSent.addAndGet(frameBytes.size.toLong())
                } catch (e: Exception) {
                    Log.w(TAG, "Client socket write error: ${e.message}")
                }
            }

            ConnectionRole.SERVER -> {
                if (connectedOutputStreams.isEmpty()) return
                val deadIndices = mutableListOf<Int>()

                for (i in 0 until connectedOutputStreams.size) {
                    val out = connectedOutputStreams.getOrNull(i) ?: continue
                    try {
                        synchronized(out) {
                            out.write(frameBytes)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Receiver socket write error: ${e.message}")
                        deadIndices.add(i)
                    }
                }

                if (deadIndices.isNotEmpty()) {
                    for (i in deadIndices.asReversed()) {
                        if (i < connectedOutputStreams.size) {
                            connectedOutputStreams.removeAt(i)
                            try {
                                val sock = connectedClients.removeAt(i)
                                sock.close()
                            } catch (_: Exception) {}
                        }
                    }
                }

                totalFramesSent.incrementAndGet()
                totalBytesSent.addAndGet(frameBytes.size.toLong())
            }
        }
    }

    /**
     * Closes all active sockets and stops background threads.
     */
    fun close() {
        isRunning.set(false)

        try {
            clientOutputStream?.close()
            clientSocket?.close()
        } catch (_: Exception) {}
        clientOutputStream = null
        clientSocket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverAcceptThread?.interrupt()
        serverAcceptThread = null

        for (client in connectedClients) {
            try { client.close() } catch (_: Exception) {}
        }
        connectedClients.clear()
        connectedOutputStreams.clear()
    }
}
