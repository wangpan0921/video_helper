package com.wangpan.videohelper.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavSplitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes a 16 kHz mono 16-bit WAV whose PCM payload is [seconds] seconds of ramp data. */
    private fun writeWav(seconds: Int): Pair<File, ByteArray> {
        val sampleRate = 16_000
        val pcm = ByteArray(seconds * sampleRate * 2) { (it % 251).toByte() }
        val file = tmp.newFile("audio.wav")
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
            header.putInt(16); header.putShort(1); header.putShort(1)
            header.putInt(sampleRate); header.putInt(sampleRate * 2)
            header.putShort(2); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(pcm.size)
            raf.write(header.array())
            raf.write(pcm)
        }
        return file to pcm
    }

    private fun readPcm(wav: File): ByteArray = RandomAccessFile(wav, "r").use { raf ->
        val info = WavSplitter.parse(raf)
        val out = ByteArray(info.dataSize.toInt())
        raf.seek(info.dataOffset)
        raf.readFully(out)
        out
    }

    @Test
    fun shortAudioReturnsOriginalUnchanged() {
        val (wav, _) = writeWav(seconds = 5)
        val segments = WavSplitter.split(wav, segmentSeconds = 60, outDir = tmp.newFolder("out1"))
        assertEquals(listOf(wav), segments)
    }

    @Test
    fun longAudioSplitsIntoSegmentsThatReconstructOriginal() {
        val (wav, pcm) = writeWav(seconds = 10)
        val segments = WavSplitter.split(wav, segmentSeconds = 3, outDir = tmp.newFolder("out2"))
        // 10s / 3s -> 4 segments (3,3,3,1).
        assertEquals(4, segments.size)
        assertTrue(segments.all { it != wav })

        val concatenated = segments.fold(ByteArray(0)) { acc, seg -> acc + readPcm(seg) }
        assertEquals("concatenated PCM length must equal original", pcm.size, concatenated.size)
        assertTrue("PCM content must match after split+concat", pcm.contentEquals(concatenated))
    }
}
