package com.wangpan.videohelper.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Captures the screen via [MediaProjection] plus the system playback audio (the sound the phone is
 * playing) into a single MP4. Microphone audio is captured and mixed in ONLY when [includeMic] is
 * true; by default we record system audio only, which is what the product requires.
 *
 * System-audio-only capture relies on [AudioPlaybackCaptureConfiguration], available from API 29.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val includeMic: Boolean,
    private val outputFile: File
) {
    companion object {
        private const val TAG = "ScreenRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
        private const val AUDIO_BIT_RATE = 128_000
        private const val VIDEO_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 2
        private const val TIMEOUT_US = 10_000L
    }

    private val recording = AtomicBoolean(false)

    private lateinit var videoEncoder: MediaCodec
    private lateinit var audioEncoder: MediaCodec
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var systemAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private lateinit var muxer: MediaMuxer
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerLock = Any()
    private var muxerStarted = false

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var startNanos = 0L

    /** Aligns video bitrate roughly to resolution. */
    private fun videoBitRate(): Int = (width * height * 5).coerceIn(2_000_000, 12_000_000)

    fun start() {
        if (recording.getAndSet(true)) return
        startNanos = System.nanoTime()

        setupVideoEncoder()
        setupAudioEncoder()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val surface = videoEncoder.createInputSurface()
        videoEncoder.start()
        audioEncoder.start()

        virtualDisplay = projection.createVirtualDisplay(
            "VideoHelperVD",
            width, height, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        setupAudioRecords()
        systemAudioRecord?.startRecording()
        micAudioRecord?.startRecording()

        videoThread = Thread({ drainVideo() }, "vh-video").also { it.start() }
        audioThread = Thread({ pumpAudio() }, "vh-audio").also { it.start() }
    }

    /** @return recorded duration in milliseconds. */
    fun stop(): Long {
        if (!recording.getAndSet(false)) return 0L
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000

        try { audioThread?.join(2_000) } catch (_: InterruptedException) {}
        try { videoThread?.join(2_000) } catch (_: InterruptedException) {}

        try { systemAudioRecord?.stop() } catch (_: Exception) {}
        try { micAudioRecord?.stop() } catch (_: Exception) {}
        systemAudioRecord?.release()
        micAudioRecord?.release()

        try { videoEncoder.stop() } catch (_: Exception) {}
        try { audioEncoder.stop() } catch (_: Exception) {}
        videoEncoder.release()
        audioEncoder.release()

        virtualDisplay?.release()

        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer stop", e) }
            }
            try { muxer.release() } catch (_: Exception) {}
            muxerStarted = false
        }
        return durationMs
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate())
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun setupAudioEncoder() {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        }
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun minBufferBytes(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return max(min, SAMPLE_RATE / 10 * CHANNEL_COUNT * 2)
    }

    private fun setupAudioRecords() {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        systemAudioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferBytes())
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        if (includeMic) {
            micAudioRecord = AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferBytes())
                .build()
        }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        var eosSignaled = false
        while (true) {
            // Once recording stops, signal end-of-stream exactly once and keep draining until EOS.
            if (!recording.get() && !eosSignaled) {
                try { videoEncoder.signalEndOfInputStream() } catch (_: Exception) {}
                eosSignaled = true
            }
            val outIndex = videoEncoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Nothing ready yet; loop again (EOS will eventually arrive after signaling).
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outIndex >= 0 -> {
                    val buf = videoEncoder.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        synchronized(muxerLock) {
                            muxer.writeSampleData(videoTrackIndex, buf, info)
                        }
                    }
                    videoEncoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun pumpAudio() {
        val frameBytes = minBufferBytes()
        val sysBuf = ByteArray(frameBytes)
        val micBuf = ByteArray(frameBytes)
        val info = MediaCodec.BufferInfo()
        var presentationUs = 0L

        while (recording.get()) {
            val read = systemAudioRecord?.read(sysBuf, 0, sysBuf.size) ?: 0
            if (read <= 0) continue

            val mixed: ByteArray = if (includeMic && micAudioRecord != null) {
                val micRead = micAudioRecord!!.read(micBuf, 0, min(micBuf.size, read))
                mixPcm16(sysBuf, read, micBuf, max(micRead, 0))
            } else {
                sysBuf.copyOf(read)
            }

            val inIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val inBuf: ByteBuffer = audioEncoder.getInputBuffer(inIndex)!!
                inBuf.clear()
                inBuf.put(mixed)
                presentationUs = (System.nanoTime() - startNanos) / 1_000
                audioEncoder.queueInputBuffer(inIndex, 0, mixed.size, presentationUs, 0)
            }
            drainAudioEncoder(info, endOfStream = false)
        }

        // Flush a final EOS frame.
        val inIndex = audioEncoder.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex >= 0) {
            audioEncoder.queueInputBuffer(
                inIndex, 0, 0, presentationUs + 1,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drainAudioEncoder(info, endOfStream = true)
    }

    private fun drainAudioEncoder(info: MediaCodec.BufferInfo, endOfStream: Boolean) {
        while (true) {
            val outIndex = audioEncoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
                        maybeStartMuxer()
                    }
                }
                outIndex >= 0 -> {
                    val buf = audioEncoder.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        synchronized(muxerLock) {
                            muxer.writeSampleData(audioTrackIndex, buf, info)
                        }
                    }
                    audioEncoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Starts the muxer once both audio and video tracks are registered. */
    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer.start()
            muxerStarted = true
        }
    }

    /** Adds two 16-bit little-endian PCM buffers with clipping. */
    private fun mixPcm16(a: ByteArray, aLen: Int, b: ByteArray, bLen: Int): ByteArray {
        val out = ByteArray(aLen)
        var i = 0
        while (i + 1 < aLen) {
            val sa = ((a[i + 1].toInt() shl 8) or (a[i].toInt() and 0xff)).toShort().toInt()
            val sb = if (i + 1 < bLen) {
                ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toInt()
            } else 0
            val mixed = (sa + sb).coerceIn(-32768, 32767)
            out[i] = (mixed and 0xff).toByte()
            out[i + 1] = ((mixed shr 8) and 0xff).toByte()
            i += 2
        }
        return out
    }
}
