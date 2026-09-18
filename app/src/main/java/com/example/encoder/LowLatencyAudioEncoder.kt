package com.example.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-latency AAC encoder capturing directly from the device microphone.
 */
class LowLatencyAudioEncoder(
    private val onAudioChunkEncoded: (ByteArray, Long) -> Unit
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITRATE = 64000
    }

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var encoderThread: Thread? = null

    fun start() {
        if (isRunning.get()) return
        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufSize * 2
            )

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            audioRecord?.startRecording()
            isRunning.set(true)

            startPcmPump(minBufSize)
            startDrainEncoder()
            Log.i(TAG, "Audio encoder started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio encoder", e)
            stop()
        }
    }

    private fun startPcmPump(bufferSize: Int) {
        recordThread = Thread({
            val audioBuffer = ByteArray(bufferSize)
            while (isRunning.get()) {
                val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readBytes > 0) {
                    feedToCodec(audioBuffer, readBytes)
                }
            }
        }, "OmtAudioRecordPump").apply { start() }
    }

    private fun feedToCodec(data: ByteArray, length: Int) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(1000)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, 0, length)
                val ptsUs = System.nanoTime() / 1000
                codec.queueInputBuffer(inputIndex, 0, length, ptsUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedToCodec error: ${e.message}")
        }
    }

    private fun startDrainEncoder() {
        encoderThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (isRunning.get()) {
                val codec = mediaCodec ?: break
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 2000)
                } catch (e: Exception) {
                    break
                }

                if (outputIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        onAudioChunkEncoded(chunk, bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }, "OmtAudioEncoderDrain").apply { start() }
    }

    fun stop() {
        isRunning.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        recordThread?.interrupt()
        encoderThread?.interrupt()
        recordThread = null
        encoderThread = null
        Log.i(TAG, "Audio encoder stopped")
    }
}
