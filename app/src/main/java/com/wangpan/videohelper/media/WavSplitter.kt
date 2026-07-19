package com.wangpan.videohelper.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Splits a PCM WAV file into time-bounded segment files, each with its own valid WAV header.
 *
 * Why: cloud ASR endpoints cap a single request (e.g. SiliconFlow allows ≤ 1 hour / ≤ 50MB). A
 * 30-minute 16 kHz mono 16-bit recording is ~57MB, which exceeds the size cap, so uploading the
 * whole file returns only a partial transcript. Splitting into short segments keeps every request
 * small and well within limits, so the full audio gets transcribed and the concatenated text is
 * complete.
 */
object WavSplitter {

    /** Parsed WAV format info plus the byte range of the PCM `data` chunk. */
    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        val bytesPerSecond: Int get() = sampleRate * channels * bitsPerSample / 8
        val blockAlign: Int get() = channels * bitsPerSample / 8
    }

    /**
     * Splits [wav] into segments of at most [segmentSeconds] each, writing them into [outDir].
     *
     * @return the list of segment files in order. If the audio already fits in a single segment,
     *   returns `listOf(wav)` (the original file, not a copy) so callers avoid needless IO — callers
     *   should therefore only delete segment files that differ from [wav].
     */
    fun split(wav: File, segmentSeconds: Int, outDir: File): List<File> {
        RandomAccessFile(wav, "r").use { raf ->
            val info = parse(raf)
            var segBytes = segmentSeconds.toLong() * info.bytesPerSecond
            // Align to a whole sample frame so segments never split a sample.
            if (info.blockAlign > 0) segBytes -= segBytes % info.blockAlign
            if (segBytes <= 0 || info.dataSize <= segBytes) return listOf(wav)

            outDir.mkdirs()
            val result = mutableListOf<File>()
            val buffer = ByteArray(64 * 1024)
            var pos = 0L
            var index = 0
            while (pos < info.dataSize) {
                val thisLen = min(segBytes, info.dataSize - pos)
                val out = File(outDir, "${wav.nameWithoutExtension}_seg$index.wav")
                RandomAccessFile(out, "rw").use { o ->
                    o.setLength(0)
                    writeHeader(o, thisLen, info.sampleRate, info.channels, info.bitsPerSample)
                    raf.seek(info.dataOffset + pos)
                    var remaining = thisLen
                    while (remaining > 0) {
                        val toRead = min(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        o.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                result.add(out)
                pos += thisLen
                index++
            }
            return result
        }
    }

    /** Parses the RIFF/WAVE header to locate the `fmt ` and `data` chunks. */
    fun parse(raf: RandomAccessFile): WavInfo {
        val riff = ByteArray(12)
        raf.seek(0)
        if (raf.read(riff) < 12 || String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") {
            error("不是有效的 WAV 文件")
        }
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = 0L
        var offset = 12L
        val fileLen = raf.length()
        val chunkHeader = ByteArray(8)
        while (offset + 8 <= fileLen) {
            raf.seek(offset)
            if (raf.read(chunkHeader) < 8) break
            val id = String(chunkHeader, 0, 4)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(minOf(16, size.toInt()))
                    raf.seek(body)
                    raf.read(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    bb.short // audioFormat
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataSize = min(size, fileLen - body)
                }
            }
            // Chunks are word-aligned (padded to even length).
            offset = body + size + (size and 1L)
            if (dataOffset >= 0 && sampleRate > 0) break
        }
        require(sampleRate > 0 && channels > 0 && bitsPerSample > 0) { "WAV 头解析失败" }
        require(dataOffset >= 0 && dataSize > 0) { "WAV 缺少音频数据" }
        return WavInfo(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
    }

    private fun writeHeader(
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
        header.putInt(16)
        header.putShort(1)
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
