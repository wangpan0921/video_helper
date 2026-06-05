package com.wangpan.videohelper.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Pulls the audio track out of a recorded MP4 and decodes it to a 16 kHz mono 16-bit PCM WAV file,
 * which is the format most cloud ASR endpoints prefer. Uses only platform MediaCodec/MediaExtractor
 * so we avoid bundling FFmpeg (which would bloat the APK).
 */
object AudioExtractor {

    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /**
     * @return the produced WAV file.
     */
    fun extractToWav(videoFile: File, outputWav: File): File {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val audioTrack = selectAudioTrack(extractor)
                ?: error("录制文件中没有找到音频轨道")
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)

            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("音频轨道缺少 MIME 类型")
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            RandomAccessFile(outputWav, "rw").use { raf ->
                raf.setLength(0)
                // Reserve 44 bytes for the WAV header; filled in at the end once size is known.
                raf.write(ByteArray(44))
                var totalPcmBytes = 0L

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = decoder.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        if (bufferInfo.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIndex)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val pcm = downmixAndResample(outBuf, srcChannels, srcSampleRate)
                            raf.write(pcm)
                            totalPcmBytes += pcm.size
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                }

                writeWavHeader(raf, totalPcmBytes, TARGET_SAMPLE_RATE, 1, 16)
            }
            return outputWav
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * Convert decoded 16-bit PCM to mono and naively resample to 16 kHz. Naive (nearest-sample)
     * resampling is good enough for speech recognition and keeps the code dependency-free.
     */
    private fun downmixAndResample(buffer: ByteBuffer, channels: Int, srcRate: Int): ByteArray {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frameCount = shorts.remaining() / channels
        val mono = ShortArray(frameCount)
        for (f in 0 until frameCount) {
            var acc = 0
            for (c in 0 until channels) acc += shorts.get(f * channels + c).toInt()
            mono[f] = (acc / channels).toShort()
        }

        val resampled: ShortArray = if (srcRate == TARGET_SAMPLE_RATE) {
            mono
        } else {
            val outCount = (frameCount.toLong() * TARGET_SAMPLE_RATE / srcRate).toInt()
            ShortArray(outCount) { i ->
                val srcIndex = min((i.toLong() * srcRate / TARGET_SAMPLE_RATE).toInt(), frameCount - 1)
                mono[srcIndex]
            }
        }

        val out = ByteArray(resampled.size * 2)
        var j = 0
        for (s in resampled) {
            out[j++] = (s.toInt() and 0xff).toByte()
            out[j++] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun writeWavHeader(
        raf: RandomAccessFile,
        pcmBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((36 + pcmBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)                 // PCM chunk size
        header.putShort(1)                // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(pcmBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }
}
