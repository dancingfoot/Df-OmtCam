package com.example.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts CameraX YUV_420_888 images into OMT-compatible packed 4:2:2 UYVY frames,
 * and captures 48kHz mono 32-bit float planar PCM ('FPA1') audio frames.
 */
object OmtVideoConverter {

    /**
     * Converts a YUV_420_888 [Image] to packed UYVY (`[U0, Y0, V0, Y1]`).
     * Uses even-aligned actual image dimensions so ExtendedHeader dimensions always match byte count (`w * h * 2`).
     */
    fun convertYuv420ToUyvy(image: Image, reuseBuffer: ByteArray?): Pair<ByteArray, Pair<Int, Int>> {
        val w = image.width and 1.inv()
        val h = image.height and 1.inv()
        val requiredSize = w * h * 2
        val out = if (reuseBuffer != null && reuseBuffer.size == requiredSize) {
            reuseBuffer
        } else {
            ByteArray(requiredSize)
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val yCap = yBuf.capacity()
        val uCap = uBuf.capacity()
        val vCap = vBuf.capacity()

        var dstIdx = 0
        for (row in 0 until h) {
            val yRowStart = row * yRowStride
            val uvRow = row ushr 1
            val uRowStart = uvRow * uRowStride
            val vRowStart = uvRow * vRowStride

            for (col in 0 until w step 2) {
                val uvCol = col ushr 1
                val uIdx = uRowStart + uvCol * uPixelStride
                val vIdx = vRowStart + uvCol * vPixelStride
                val y0Idx = yRowStart + col * yPixelStride
                val y1Idx = y0Idx + yPixelStride

                val u = if (uIdx < uCap) uBuf.get(uIdx) else 128.toByte()
                val v = if (vIdx < vCap) vBuf.get(vIdx) else 128.toByte()
                val y0 = if (y0Idx < yCap) yBuf.get(y0Idx) else 16.toByte()
                val y1 = if (y1Idx < yCap) yBuf.get(y1Idx) else 16.toByte()

                out[dstIdx++] = u
                out[dstIdx++] = y0
                out[dstIdx++] = v
                out[dstIdx++] = y1
            }
        }

        return out to (w to h)
    }
}

/**
 * Captures 48kHz mono PCM from the device microphone and emits OMT-compliant
 * Little-Endian 32-bit floating-point planar audio frames ('FPA1').
 */
class OmtAudioCapturer(
    private val onAudioFrame: (fpa1Bytes: ByteArray, sampleRate: Int, samplesPerChannel: Int, timestampNs: Long) -> Unit
) {
    companion object {
        private const val TAG = "OmtAudioCapturer"
        const val SAMPLE_RATE = 48000
        const val SAMPLES_PER_FRAME = 960 // 20ms @ 48kHz
    }

    private var audioRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get()) return
        try {
            val minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recordBufBytes = (minBufBytes * 2).coerceAtLeast(SAMPLES_PER_FRAME * 2 * 4)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufBytes
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            audioRecord = recorder
            recorder.startRecording()
            isRunning.set(true)

            captureThread = Thread({
                val pcmShorts = ShortArray(SAMPLES_PER_FRAME)
                val floatBytes = ByteArray(SAMPLES_PER_FRAME * 4)
                val byteBuf = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN)

                while (isRunning.get()) {
                    var offset = 0
                    while (offset < SAMPLES_PER_FRAME && isRunning.get()) {
                        val read = recorder.read(pcmShorts, offset, SAMPLES_PER_FRAME - offset)
                        if (read > 0) {
                            offset += read
                        } else if (read < 0) {
                            break
                        }
                    }

                    if (offset == SAMPLES_PER_FRAME && isRunning.get()) {
                        byteBuf.clear()
                        for (i in 0 until SAMPLES_PER_FRAME) {
                            byteBuf.putFloat(pcmShorts[i] / 32768.0f)
                        }
                        onAudioFrame(floatBytes.copyOf(), SAMPLE_RATE, SAMPLES_PER_FRAME, System.nanoTime())
                    }
                }
            }, "OmtAudioCapture").apply { start() }

            Log.i(TAG, "FPA1 48kHz audio capturer started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capturer: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        captureThread?.interrupt()
        captureThread = null
        Log.i(TAG, "FPA1 audio capturer stopped")
    }
}
