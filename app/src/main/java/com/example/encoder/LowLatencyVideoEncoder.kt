package com.example.encoder

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra low latency H.264 (AVC) Hardware Video Encoder using Android MediaCodec.
 * Optimized for real-time live streaming like opencamera-omt:
 * - Baseline Profile (no B-frames)
 * - Zero-latency mode enabled (KEY_LATENCY = 0, Realtime priority)
 * - 1-second keyframe interval for fast client joins
 * - Fast zero-allocation buffer pooling for YUV data
 * - CBR (Constant Bitrate) with dynamic bitrate adjustment
 */
class LowLatencyVideoEncoder(
    private val width: Int,
    private val height: Int,
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

    // Reusable byte array buffer to avoid GC pressure during 30/60fps camera analysis
    private var yuvBuffer: ByteArray? = null

    fun start() {
        if (isRunning.get()) return
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between keyframes for fast join
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)

                // Low latency hints on Android
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
                } catch (_: Exception) {}
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            yuvBuffer = ByteArray((width * height * 3) / 2)
            isRunning.set(true)
            startDrainLoop()
            Log.i(TAG, "Hardware H.264 encoder initialized: ${width}x${height} @ $fps fps, $bitrateKbps kbps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video encoder", e)
            stop()
        }
    }

    /**
     * Enqueue a YUV_420_888 camera frame image from CameraX ImageAnalysis.
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
                    val targetArray = yuvBuffer ?: ByteArray((width * height * 3) / 2).also { yuvBuffer = it }
                    val filledSize = fastYuv420ToNv21(image, targetArray)
                    val len = filledSize.coerceAtMost(inputBuffer.remaining())
                    inputBuffer.put(targetArray, 0, len)
                    val ptsUs = timestampNs / 1000
                    codec.queueInputBuffer(inputIndex, 0, len, ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "encodeFrame error: ${e.message}")
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
                    codec.dequeueOutputBuffer(bufferInfo, 2000)
                } catch (e: Exception) {
                    break
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
                } else if (outputIndex >= 0) {
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
                }
            }
        }, "OmtVideoEncoderDrain").apply { start() }
    }

    /**
     * High-speed YUV420 to NV21/NV12 direct buffer copy into preallocated array.
     */
    private fun fastYuv420ToNv21(image: Image, out: ByteArray): Int {
        val imgWidth = image.width
        val imgHeight = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var outOffset = 0
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        if (yPixelStride == 1 && yRowStride == imgWidth) {
            val ySize = imgWidth * imgHeight
            yBuffer.position(0)
            yBuffer.get(out, 0, ySize)
            outOffset += ySize
        } else {
            for (row in 0 until imgHeight) {
                yBuffer.position(row * yRowStride)
                if (yPixelStride == 1) {
                    yBuffer.get(out, outOffset, imgWidth)
                    outOffset += imgWidth
                } else {
                    for (col in 0 until imgWidth) {
                        out[outOffset++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                    }
                }
            }
        }

        // Copy UV
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvHeight = imgHeight / 2
        val uvWidth = imgWidth / 2

        for (row in 0 until uvHeight) {
            val uPos = row * uRowStride
            val vPos = row * vRowStride
            for (col in 0 until uvWidth) {
                out[outOffset++] = uBuffer.get(uPos + col * uPixelStride)
                out[outOffset++] = vBuffer.get(vPos + col * vPixelStride)
            }
        }

        return outOffset
    }

    fun stop() {
        isRunning.set(false)
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        drainThread?.interrupt()
        drainThread = null
        yuvBuffer = null
        Log.i(TAG, "Video encoder stopped")
    }
}
