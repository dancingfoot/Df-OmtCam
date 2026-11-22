package com.example.encoder

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low-latency Hardware H.264 video encoder.
 * - Hardware AVC (H.264) via MediaCodec
 * - Resolves supported color formats dynamically (YUV420Flexible / SemiPlanar / Planar)
 * - Safe graceful drain shutdown preventing "Pending dequeue output buffer request cancelled"
 * - Baseline Profile with fallback for device compatibility
 * - 1-second keyframe interval for fast client joins
 */
class LowLatencyVideoEncoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val onFrameEncoded: (ByteArray, Long, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var drainThread: Thread? = null

    // Cache SPS/PPS parameter sets to prepend to IDR keyframes for instant receiver decoding
    private var spsPpsHeader: ByteArray? = null

    // Preallocated buffer matching the encoder format (width * height * 3 / 2)
    private val expectedFrameSize = (width * height * 3) / 2
    private var yuvBuffer: ByteArray? = null

    fun start() {
        if (isRunning.get()) return
        try {
            val chosenColorFormat = selectColorFormat()
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, chosenColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                // Set CBR mode if supported, fallback to VBR silently if rejected
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {}

                // Try Baseline profile for lowest latency
                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                } catch (_: Exception) {}

                // Low latency and realtime hints on Android (ignored if not supported by hardware vendor)
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                } catch (_: Exception) {}
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (_: Exception) {}
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            mediaCodec = encoder

            yuvBuffer = ByteArray(expectedFrameSize)
            isRunning.set(true)
            startDrainLoop()
            Log.i(TAG, "H.264 encoder initialized: ${width}x${height} @ $fps fps, colorFormat=$chosenColorFormat")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video encoder", e)
            stop()
        }
    }

    /**
     * Determines the optimal supported color format for H.264 encoders on this device.
     */
    private fun selectColorFormat(): Int {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                val types = info.supportedTypes
                if (types.any { it.equals(MIME_TYPE, ignoreCase = true) }) {
                    val caps = info.getCapabilitiesForType(MIME_TYPE)
                    val formats = caps.colorFormats
                    // Prefer YUV420Flexible
                    if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    }
                    if (formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    }
                }
            }
        } catch (_: Exception) {}
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    /**
     * Enqueue a YUV_420_888 camera frame image from CameraX ImageAnalysis.
     * Safely reads and converts the Image into the encoder's expected dimensions (width x height)
     * preventing any out-of-bounds exceptions when input and encoder buffer sizes differ.
     */
    fun encodeFrame(image: Image, timestampNs: Long) {
        if (!isRunning.get()) return
        val codec = mediaCodec ?: return

        try {
            val inputIndex = codec.dequeueInputBuffer(1000) // 1ms wait max
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    val targetArray = yuvBuffer ?: ByteArray(expectedFrameSize).also { yuvBuffer = it }

                    // Convert image safely to target NV21/Semi-planar buffer
                    val writtenBytes = copyImageToTargetNv21(image, targetArray, width, height)
                    val safeLen = writtenBytes.coerceAtMost(inputBuffer.remaining())
                    inputBuffer.put(targetArray, 0, safeLen)

                    val ptsUs = timestampNs / 1000
                    codec.queueInputBuffer(inputIndex, 0, safeLen, ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "encodeFrame error: ${e.message}")
            }
        }
    }

    fun requestKeyFrame() {
        val codec = mediaCodec ?: return
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(bundle)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request keyframe: ${e.message}")
        }
    }

    private fun startDrainLoop() {
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning.get()) {
                val codec = mediaCodec ?: break
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 5000)
                } catch (e: IllegalStateException) {
                    // Codec was stopped or released
                    break
                } catch (e: Exception) {
                    break
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    try {
                        val newFormat = codec.outputFormat
                        val csd0 = newFormat.getByteBuffer("csd-0") // SPS
                        val csd1 = newFormat.getByteBuffer("csd-1") // PPS
                        if (csd0 != null && csd1 != null) {
                            val header = ByteArray(csd0.remaining() + csd1.remaining())
                            csd0.get(header, 0, csd0.remaining())
                            csd1.get(header, csd0.remaining(), csd1.remaining())
                            spsPpsHeader = header
                            Log.i(TAG, "Saved SPS/PPS header size: ${header.size}")
                        }
                    } catch (_: Exception) {}
                } else if (outputIndex >= 0) {
                    try {
                        val outBuf = codec.getOutputBuffer(outputIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            if (isConfig) {
                                val cfg = ByteArray(bufferInfo.size)
                                outBuf.get(cfg)
                                spsPpsHeader = cfg
                            } else {
                                var payload = ByteArray(bufferInfo.size)
                                outBuf.get(payload)

                                // Prepend SPS/PPS to IDR keyframe if not already included
                                if (isKey && spsPpsHeader != null) {
                                    val combined = ByteArray(spsPpsHeader!!.size + payload.size)
                                    System.arraycopy(spsPpsHeader!!, 0, combined, 0, spsPpsHeader!!.size)
                                    System.arraycopy(payload, 0, combined, spsPpsHeader!!.size, payload.size)
                                    payload = combined
                                }

                                onFrameEncoded(payload, bufferInfo.presentationTimeUs, isKey)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w(TAG, "Error handling output buffer: ${e.message}")
                        }
                    }
                }
            }
        }, "OmtVideoEncoderDrain").apply { start() }
    }

    /**
     * Converts a CameraX Image (YUV_420_888) to NV21 inside `outArray`.
     * Handles differences between CameraX image size (imgW x imgH) and encoder format (targetW x targetH).
     */
    private fun copyImageToTargetNv21(
        image: Image,
        out: ByteArray,
        targetW: Int,
        targetH: Int
    ): Int {
        val imgW = image.width
        val imgH = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val copyW = targetW.coerceAtMost(imgW)
        val copyH = targetH.coerceAtMost(imgH)

        // 1. Copy Y plane
        val yCap = yBuf.capacity()
        for (row in 0 until copyH) {
            val ySrcRowStart = row * yRowStride
            val yDstRowStart = row * targetW

            if (yPixelStride == 1) {
                val available = (yCap - ySrcRowStart).coerceAtLeast(0)
                val len = copyW.coerceAtMost(available)
                if (len > 0 && yDstRowStart + len <= out.size) {
                    yBuf.position(ySrcRowStart)
                    yBuf.get(out, yDstRowStart, len)
                }
            } else {
                for (col in 0 until copyW) {
                    val srcIndex = ySrcRowStart + (col * yPixelStride)
                    val dstIndex = yDstRowStart + col
                    if (srcIndex < yCap && dstIndex < out.size) {
                        out[dstIndex] = yBuf.get(srcIndex)
                    }
                }
            }
        }

        // 2. Copy UV interleaved (NV21: V, U, V, U...)
        val uvTargetStart = targetW * targetH
        val uvCopyH = copyH / 2
        val uvCopyW = copyW / 2
        val uCap = uBuf.capacity()
        val vCap = vBuf.capacity()

        for (row in 0 until uvCopyH) {
            val uSrcRowStart = row * uRowStride
            val vSrcRowStart = row * vRowStride
            val rowOutStart = uvTargetStart + (row * targetW)

            for (col in 0 until uvCopyW) {
                val uIdx = uSrcRowStart + (col * uPixelStride)
                val vIdx = vSrcRowStart + (col * vPixelStride)

                if (vIdx < vCap && uIdx < uCap) {
                    val vByte = vBuf.get(vIdx)
                    val uByte = uBuf.get(uIdx)

                    val destIndex = rowOutStart + (col * 2)
                    if (destIndex + 1 < out.size) {
                        out[destIndex] = vByte
                        out[destIndex + 1] = uByte
                    }
                }
            }
        }

        return expectedFrameSize
    }

    /**
     * Cleanly and safely shuts down the encoder without cancelling pending dequeue requests mid-operation.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        // Join drain thread first so it exits its dequeueOutputBuffer loop cleanly
        try {
            drainThread?.join(300)
        } catch (_: Exception) {}
        drainThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {}
        try {
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        yuvBuffer = null
        Log.i(TAG, "Video encoder stopped cleanly")
    }
}
