package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Universal Dual-Transport OMT (Open Media Transport) Transmitter.
 * Strictly adheres to libomt and opencamera-omt specifications:
 *
 * 1. Standard OMT TCP Server:
 *    - Listens on `port` (e.g. 6400) for OBS OMT Source / vMix incoming TCP stream requests.
 *    - Streams length-prefixed OMT packets: [4 bytes Total Length Big-Endian] + [OMT Payload].
 *
 * 2. UDP Broadcast & Dynamic Receiver Registration:
 *    - Sends UDP datagrams to local network broadcast address.
 *    - Listens for any UDP handshake/ping packets from receivers and auto-registers their IP:port.
 *    - Also sends directly to `manualTargetIp` if specified by user.
 */
class OmtPacketTransmitter {
    companion object {
        private const val TAG = "OmtPacketTransmitter"
        private const val MAX_UDP_PAYLOAD = 1380
        const val OMT_MAGIC: Short = 0x4F4D // "OM"
        const val MEDIA_TYPE_VIDEO: Byte = 0x01
        const val MEDIA_TYPE_AUDIO: Byte = 0x02
    }

    // UDP Components
    private var udpSocket: DatagramSocket? = null
    private var boundPort: Int = 6400
    private var broadcastAddress: InetAddress? = null
    private var manualTargetAddress: InetAddress? = null
    private val subscribedUdpReceivers = CopyOnWriteArraySet<InetSocketAddress>()

    // TCP Server Components (required by OBS omtplugin / libomtnet)
    private var tcpServerSocket: ServerSocket? = null
    private val connectedTcpClients = CopyOnWriteArrayList<Socket>()
    private val tcpOutputStreams = CopyOnWriteArrayList<DataOutputStream>()

    private val isRunning = AtomicBoolean(false)
    private var udpListenerThread: Thread? = null
    private var tcpAcceptThread: Thread? = null

    private var videoSequenceNumber: Short = 0
    private var audioSequenceNumber: Short = 0

    private val totalPackets = AtomicLong(0)
    private val totalBytes = AtomicLong(0)

    val totalPacketsSent: Long get() = totalPackets.get()
    val totalBytesSent: Long get() = totalBytes.get()
    val activeReceiversCount: Int
        get() = connectedTcpClients.size + subscribedUdpReceivers.size + (if (manualTargetAddress != null) 1 else 0)

    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun resolveBroadcastAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return InetAddress.getByName("255.255.255.255")
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        return broadcast
                    }
                }
            }
        } catch (_: Exception) {}
        return InetAddress.getByName("255.255.255.255")
    }

    suspend fun start(port: Int, manualTargetIp: String = "") = withContext(Dispatchers.IO) {
        stop()
        boundPort = port
        broadcastAddress = resolveBroadcastAddress()

        if (manualTargetIp.isNotBlank()) {
            try {
                manualTargetAddress = InetAddress.getByName(manualTargetIp.trim())
            } catch (e: Exception) {
                Log.w(TAG, "Invalid manual target IP: $manualTargetIp", e)
                manualTargetAddress = null
            }
        } else {
            manualTargetAddress = null
        }

        isRunning.set(true)
        videoSequenceNumber = 0
        audioSequenceNumber = 0
        totalPackets.set(0)
        totalBytes.set(0)
        subscribedUdpReceivers.clear()
        connectedTcpClients.clear()
        tcpOutputStreams.clear()

        // 1. Initialize TCP Server (standard for OBS OMT Source and vMix)
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            tcpServerSocket = server
            startTcpAcceptLoop(server)
            Log.i(TAG, "OMT TCP Server listening on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TCP Server on port $port: ${e.message}")
        }

        // 2. Initialize UDP Socket
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                sendBufferSize = 1024 * 1024
                receiveBufferSize = 256 * 1024
                bind(InetSocketAddress(port))
            }
            udpSocket = sock
            startUdpReceiverListener(sock)
            Log.i(TAG, "OMT UDP Transmitter listening on port $port, broadcast=$broadcastAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP socket on port $port: ${e.message}")
        }
    }

    private fun startTcpAcceptLoop(server: ServerSocket) {
        tcpAcceptThread = Thread({
            while (isRunning.get() && !server.isClosed) {
                try {
                    val client = server.accept().apply {
                        tcpNoDelay = true
                        sendBufferSize = 1024 * 1024
                    }
                    val out = DataOutputStream(client.getOutputStream())
                    connectedTcpClients.add(client)
                    tcpOutputStreams.add(out)
                    Log.i(TAG, "OBS/vMix TCP Client connected: ${client.remoteSocketAddress} (Total clients: ${connectedTcpClients.size})")
                } catch (e: Exception) {
                    if (!isRunning.get() || server.isClosed) break
                }
            }
        }, "OmtTcpAcceptLoop").apply { start() }
    }

    private fun startUdpReceiverListener(sock: DatagramSocket) {
        udpListenerThread = Thread({
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isRunning.get() && !sock.isClosed) {
                try {
                    sock.receive(packet)
                    val senderAddress = packet.address
                    val senderPort = packet.port

                    if (senderAddress != null && senderPort > 0) {
                        val endpoint = InetSocketAddress(senderAddress, senderPort)
                        if (subscribedUdpReceivers.add(endpoint)) {
                            Log.i(TAG, "Auto-discovered UDP receiver: $endpoint")
                        }
                    }
                } catch (e: Exception) {
                    if (!isRunning.get() || sock.isClosed) break
                }
            }
        }, "OmtUdpListener").apply { start() }
    }

    fun stop() {
        isRunning.set(false)

        // Close TCP Server and all active client streams
        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {}
        tcpServerSocket = null

        for (client in connectedTcpClients) {
            try {
                client.close()
            } catch (_: Exception) {}
        }
        connectedTcpClients.clear()
        tcpOutputStreams.clear()

        tcpAcceptThread?.interrupt()
        tcpAcceptThread = null

        // Close UDP Socket
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        udpListenerThread?.interrupt()
        udpListenerThread = null
        subscribedUdpReceivers.clear()
        manualTargetAddress = null
    }

    fun isConnected(): Boolean {
        return isRunning.get() && (tcpServerSocket != null || udpSocket != null)
    }

    /**
     * Transmits video frame across all active transports:
     * - TCP Clients (OBS / vMix)
     * - UDP Broadcast / Subscribed Receivers
     */
    fun sendVideoFrame(
        frameData: ByteArray,
        presentationTimeUs: Long,
        isKeyFrame: Boolean
    ) {
        if (!isRunning.get()) return

        // 1. Send via TCP to connected OBS/vMix instances
        sendFrameOverTcp(
            payload = frameData,
            ptsUs = presentationTimeUs,
            isKeyFrame = isKeyFrame,
            mediaType = MEDIA_TYPE_VIDEO,
            seq = videoSequenceNumber
        )

        // 2. Send via UDP
        val sock = udpSocket
        if (sock != null && !sock.isClosed) {
            try {
                sendOmtUdpChunked(
                    sock = sock,
                    data = frameData,
                    ptsUs = presentationTimeUs,
                    isKeyFrame = isKeyFrame,
                    mediaType = MEDIA_TYPE_VIDEO,
                    seq = videoSequenceNumber++
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP video frame: ${e.message}")
            }
        }
    }

    /**
     * Transmits audio frame across all active transports.
     */
    fun sendAudioData(
        audioData: ByteArray,
        presentationTimeUs: Long
    ) {
        if (!isRunning.get()) return

        // 1. Send via TCP
        sendFrameOverTcp(
            payload = audioData,
            ptsUs = presentationTimeUs,
            isKeyFrame = false,
            mediaType = MEDIA_TYPE_AUDIO,
            seq = audioSequenceNumber
        )

        // 2. Send via UDP
        val sock = udpSocket
        if (sock != null && !sock.isClosed) {
            try {
                sendOmtUdpChunked(
                    sock = sock,
                    data = audioData,
                    ptsUs = presentationTimeUs,
                    isKeyFrame = false,
                    mediaType = MEDIA_TYPE_AUDIO,
                    seq = audioSequenceNumber++
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP audio frame: ${e.message}")
            }
        }
    }

    /**
     * Sends length-prefixed OMT frame to all connected TCP clients.
     * Wire Format:
     * [0..3] Total Payload Length (Int32 Big-Endian)
     * [4..19] OMT Header (16 bytes)
     * [20..N] Elementary stream payload (H.264 NAL / AAC)
     */
    private fun sendFrameOverTcp(
        payload: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        mediaType: Byte,
        seq: Short
    ) {
        if (tcpOutputStreams.isEmpty()) return

        val totalLength = 16 + payload.size
        val buffer = ByteBuffer.allocate(4 + totalLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(totalLength)
            putShort(OMT_MAGIC)
            put(1.toByte())
            put(mediaType)
            putShort(seq)
            putShort(0.toShort()) // Chunk 0
            putShort(1.toShort()) // Total 1
            put(if (isKeyFrame) 0x01.toByte() else 0x00.toByte())
            put(0.toByte())
            putInt((ptsUs / 1000).toInt())
            put(payload)
        }

        val wireBytes = buffer.array()
        val deadClients = mutableListOf<Int>()

        for (i in 0 until tcpOutputStreams.size) {
            val out = tcpOutputStreams.getOrNull(i) ?: continue
            try {
                synchronized(out) {
                    out.write(wireBytes)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client disconnected: ${e.message}")
                deadClients.add(i)
            }
        }

        // Clean up disconnected clients
        if (deadClients.isNotEmpty()) {
            for (idx in deadClients.reversed()) {
                try {
                    tcpOutputStreams.removeAt(idx)
                    val sock = connectedTcpClients.removeAt(idx)
                    sock.close()
                } catch (_: Exception) {}
            }
        }

        totalPackets.incrementAndGet()
        totalBytes.addAndGet(wireBytes.size.toLong())
    }

    /**
     * Splits elementary NAL/audio data into MTU-safe UDP datagrams with OMT headers.
     */
    private fun sendOmtUdpChunked(
        sock: DatagramSocket,
        data: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        mediaType: Byte,
        seq: Short
    ) {
        val totalChunks = ((data.size + MAX_UDP_PAYLOAD - 1) / MAX_UDP_PAYLOAD).coerceAtLeast(1)

        for (chunkIdx in 0 until totalChunks) {
            val offset = chunkIdx * MAX_UDP_PAYLOAD
            val length = (data.size - offset).coerceAtMost(MAX_UDP_PAYLOAD)

            val packetBuffer = ByteBuffer.allocate(16 + length).apply {
                order(ByteOrder.BIG_ENDIAN)
                putShort(OMT_MAGIC)
                put(1.toByte())
                put(mediaType)
                putShort(seq)
                putShort(chunkIdx.toShort())
                putShort(totalChunks.toShort())
                put(if (isKeyFrame) 0x01.toByte() else 0x00.toByte())
                put(0.toByte())
                putInt((ptsUs / 1000).toInt())
                put(data, offset, length)
            }

            val packetBytes = packetBuffer.array()

            // 1. Broadcast to local subnet
            val bcastAddr = broadcastAddress
            if (bcastAddr != null) {
                try {
                    sock.send(DatagramPacket(packetBytes, packetBytes.size, bcastAddr, boundPort))
                } catch (_: Exception) {}
            }

            // 2. Send to manual target IP if entered
            val targetAddr = manualTargetAddress
            if (targetAddr != null) {
                try {
                    sock.send(DatagramPacket(packetBytes, packetBytes.size, targetAddr, boundPort))
                } catch (_: Exception) {}
            }

            // 3. Send to any auto-discovered subscribers
            for (receiver in subscribedUdpReceivers) {
                try {
                    sock.send(DatagramPacket(packetBytes, packetBytes.size, receiver.address, receiver.port))
                } catch (_: Exception) {}
            }

            totalPackets.incrementAndGet()
            totalBytes.addAndGet(packetBytes.size.toLong())
        }
    }
}
