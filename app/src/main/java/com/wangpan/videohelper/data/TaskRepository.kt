package com.wangpan.videohelper.data

import android.content.Context
import com.wangpan.videohelper.data.db.AppDatabase
import com.wangpan.videohelper.data.db.StageStatus
import com.wangpan.videohelper.data.db.TaskDao
import com.wangpan.videohelper.data.db.TaskEntity
import com.wangpan.videohelper.data.remote.OpenAiCompatibleSummarizer
import com.wangpan.videohelper.data.remote.OpenAiCompatibleTranscriber
import com.wangpan.videohelper.data.remote.Summarizer
import com.wangpan.videohelper.data.remote.Transcriber
import com.wangpan.videohelper.data.settings.SettingsRepository
import com.wangpan.videohelper.media.AudioExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Live progress of a running pipeline stage. [fraction] is 0f..1f when known, null when indeterminate. */
data class StageProgress(val message: String, val fraction: Float?)

/**
 * Single entry point for the capture-to-article pipeline. Each stage is independent and persists its
 * artifact, so a failure (or an app restart) only requires re-running the failed step rather than
 * the whole chain.
 */
class TaskRepository private constructor(
    private val appContext: Context,
    private val dao: TaskDao,
    private val settingsRepo: SettingsRepository,
    private val transcriber: Transcriber,
    private val summarizer: Summarizer
) {

    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()
    fun observe(id: String): Flow<TaskEntity?> = dao.observeById(id)

    // In-memory, per-task progress for the currently running stage. Not persisted: on process death
    // the stage is simply re-run, so there's nothing to restore. The UI observes this to replace the
    // static "处理中…" with a live percentage / step count.
    private val progressState = MutableStateFlow<Map<String, StageProgress>>(emptyMap())

    /** Live progress of the running stage for [id], or null when nothing is in progress. */
    fun observeProgress(id: String): Flow<StageProgress?> = progressState.map { it[id] }

    private fun setProgress(id: String, message: String, fraction: Float?) {
        progressState.update { it + (id to StageProgress(message, fraction)) }
    }

    private fun clearProgress(id: String) {
        progressState.update { it - id }
    }

    suspend fun createFromRecording(videoPath: String, durationMs: Long, micIncluded: Boolean): String {
        val now = System.currentTimeMillis()
        val title = "录制 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            videoPath = videoPath,
            durationMs = durationMs,
            micIncluded = micIncluded
        )
        dao.upsert(task)
        return task.id
    }

    /**
     * Rebuilds tasks from recordings already saved on disk (e.g. after a reinstall wiped the Room
     * DB but the mp4 files under /sdcard/videohelper survived). Scans the output directory for
     * `rec_*.mp4` files not already tracked and imports each as a RECORDED task, so the user can
     * one-click process them again. If an exported article Markdown sits alongside the recording,
     * its text is restored too. Returns the number of newly imported tasks.
     */
    suspend fun importRecordingsFromStorage(): Int {
        // Scan both the public /sdcard/videohelper and the app-specific fallback dir, since a
        // recording may have been saved to either depending on whether storage access was granted.
        val roots = buildList {
            add(com.wangpan.videohelper.storage.AppStorage.outputDir(appContext))
            add(com.wangpan.videohelper.storage.AppStorage.publicDir())
            (appContext.getExternalFilesDir(null) ?: appContext.filesDir)?.let {
                add(File(it, com.wangpan.videohelper.storage.AppStorage.DIR_NAME))
            }
        }.filter { it.exists() }.distinctBy { it.absolutePath }
        if (roots.isEmpty()) return 0

        val known = dao.getAllVideoPaths().toHashSet()
        var imported = 0
        val recordings = roots.asSequence()
            .flatMap { root ->
                runCatching {
                    root.walkTopDown()
                        .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                        .filter { it.length() > 0 }
                        .toList()
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.absolutePath }
            .toList()

        for (file in recordings) {
            val path = file.absolutePath
            if (path in known) continue

            val createdAt = parseSessionTimestamp(file) ?: file.lastModified()
            val duration = runCatching { probeDurationMs(file) }.getOrDefault(0L)
            val restoredArticle = runCatching { restoreArticleBeside(file) }.getOrNull()
            val task = TaskEntity(
                id = UUID.randomUUID().toString(),
                title = "录制 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt)),
                createdAt = createdAt,
                videoPath = path,
                durationMs = duration,
                micIncluded = false,
                article = restoredArticle,
                summarizeStatus = if (restoredArticle != null) StageStatus.DONE else StageStatus.IDLE
            )
            dao.upsert(task)
            known.add(path)
            imported++
        }
        return imported
    }

    /** Extracts the recording's creation time from its `rec_yyyyMMdd_HHmmss.mp4` name / session folder. */
    private fun parseSessionTimestamp(file: File): Long? {
        val stamp = Regex("(\\d{8}_\\d{6})").find(file.name)?.groupValues?.get(1)
            ?: file.parentFile?.name?.let { Regex("(\\d{8}_\\d{6})").find(it)?.groupValues?.get(1) }
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(stamp)?.time
        }.getOrNull()
    }

    /** Probes the video duration via MediaMetadataRetriever; returns 0 if unavailable. */
    private fun probeDurationMs(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * If an exported `article_*.md` sits next to the recording, returns its body (without the
     * leading "# title" heading line we add on export), so a previously generated article survives
     * a reinstall.
     */
    private fun restoreArticleBeside(video: File): String? {
        val dir = video.parentFile ?: return null
        val md = dir.listFiles { f -> f.isFile && f.name.startsWith("article_") && f.extension.equals("md", true) }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val text = md.readText().trim()
        if (text.isEmpty()) return null
        // Drop the leading "# <title>" heading added by exportArticleToMarkdown.
        return text.lineSequence()
            .dropWhile { it.isBlank() }
            .let { lines ->
                val list = lines.toList()
                if (list.firstOrNull()?.startsWith("# ") == true) list.drop(1) else list
            }
            .joinToString("\n")
            .trim()
            .ifEmpty { null }
    }

    suspend fun delete(id: String) {
        dao.getById(id)?.let { task ->
            runCatching { File(task.videoPath).delete() }
            task.audioPath?.let { runCatching { File(it).delete() } }
        }
        dao.delete(id)
    }

    /** Stage 2: extract audio from the recorded MP4 into a 16 kHz mono WAV. */
    suspend fun extractAudio(id: String) {
        val task = dao.getById(id) ?: return
        dao.update(task.copy(audioStatus = StageStatus.RUNNING, errorMessage = null))
        setProgress(id, "抽取音频 0%", 0f)
        try {
            val dir = File(appContext.filesDir, "audio").apply { mkdirs() }
            val wav = File(dir, "audio_$id.wav")
            AudioExtractor.extractToWav(File(task.videoPath), wav) { frac ->
                setProgress(id, "抽取音频 ${(frac * 100).toInt()}%", frac)
            }
            dao.update(
                dao.getById(id)!!.copy(
                    audioPath = wav.absolutePath,
                    audioStatus = StageStatus.DONE,
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            dao.update(dao.getById(id)!!.copy(audioStatus = StageStatus.FAILED, errorMessage = e.message))
            throw e
        } finally {
            clearProgress(id)
        }
    }

    /** Stage 3: transcribe the extracted audio to text. Long audio is split into segments so it
     * stays within the ASR endpoint's per-request size/duration limits and gets fully transcribed. */
    suspend fun transcribe(id: String) {
        val task = dao.getById(id) ?: return
        val audio = task.audioPath?.let { File(it) }
            ?: error("请先抽取音频")
        val settings = settingsRepo.settings.first()
        dao.update(task.copy(transcribeStatus = StageStatus.RUNNING, errorMessage = null))
        setProgress(id, "准备转写…", null)

        val segmentsDir = File(appContext.cacheDir, "asr_segments").apply { mkdirs() }
        val segments = try {
            com.wangpan.videohelper.media.WavSplitter.split(audio, TRANSCRIBE_SEGMENT_SECONDS, segmentsDir)
        } catch (e: Exception) {
            // If splitting fails for any reason, fall back to transcribing the whole file.
            listOf(audio)
        }
        try {
            val total = segments.size
            val builder = StringBuilder()
            segments.forEachIndexed { index, segment ->
                val label = if (total > 1) "转写 第${index + 1}/$total 段" else "转写中"
                setProgress(id, "$label · 上传 0%", if (total > 1) index.toFloat() / total else 0f)
                val text = transcriber.transcribe(segment, settings) { up ->
                    val overall = (index + up.coerceIn(0f, 1f)) / total
                    val msg = if (up < 1f) "$label · 上传 ${(up * 100).toInt()}%" else "$label · 云端转写中…"
                    setProgress(id, msg, overall)
                }
                val piece = text.trim()
                if (piece.isNotEmpty()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(piece)
                }
            }
            dao.update(
                dao.getById(id)!!.copy(
                    transcript = builder.toString(),
                    transcribeStatus = StageStatus.DONE,
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            dao.update(dao.getById(id)!!.copy(transcribeStatus = StageStatus.FAILED, errorMessage = e.message))
            throw e
        } finally {
            // Clean up temp segment files (never the original audio).
            segments.filter { it.absolutePath != audio.absolutePath }.forEach { runCatching { it.delete() } }
            clearProgress(id)
        }
    }

    /** Stage 4: summarize the transcript into a finished article. */
    suspend fun summarize(id: String) {
        val task = dao.getById(id) ?: return
        val transcript = task.transcript?.takeIf { it.isNotBlank() }
            ?: error("请先转写文本")
        val settings = settingsRepo.settings.first()
        dao.update(task.copy(summarizeStatus = StageStatus.RUNNING, errorMessage = null))
        setProgress(id, "生成文章…", null)
        try {
            val article = summarizer.summarize(transcript, settings) { done, total ->
                val frac = if (total > 0) done.toFloat() / total else null
                setProgress(id, "生成文章 $done/$total 段", frac)
            }
            dao.update(
                dao.getById(id)!!.copy(
                    article = article,
                    summarizeStatus = StageStatus.DONE,
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            dao.update(dao.getById(id)!!.copy(summarizeStatus = StageStatus.FAILED, errorMessage = e.message))
            throw e
        } finally {
            clearProgress(id)
        }
    }

    /** Runs the whole pipeline, skipping stages that already completed. */
    suspend fun runAll(id: String) {
        val task = dao.getById(id) ?: return
        if (task.audioStatus != StageStatus.DONE) extractAudio(id)
        if (dao.getById(id)?.transcribeStatus != StageStatus.DONE) transcribe(id)
        if (dao.getById(id)?.summarizeStatus != StageStatus.DONE) summarize(id)
    }

    /**
     * Exports the generated article to a Markdown file under /sdcard/videohelper and returns the
     * absolute path. Throws if there is no article yet.
     */
    suspend fun exportArticleToMarkdown(id: String): String {
        val task = dao.getById(id) ?: error("任务不存在")
        val article = task.article?.takeIf { it.isNotBlank() } ?: error("还没有生成文章，无法导出")

        // Prefer the recording's own session folder so all artifacts of one task stay together;
        // fall back to the base /sdcard/videohelper dir if the video path is unavailable.
        val videoParent = File(task.videoPath).parentFile
        val dir = if (videoParent != null && (videoParent.exists() || videoParent.mkdirs())) {
            videoParent
        } else {
            com.wangpan.videohelper.storage.AppStorage.outputDir(appContext)
        }
        val safeTitle = task.title.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(dir, "article_${safeTitle}_$stamp.md")
        out.writeText("# ${task.title}\n\n$article\n")
        return out.absolutePath
    }

    companion object {
        // Max audio seconds per ASR request. A 16 kHz mono 16-bit segment of this length is ~3.8MB,
        // safely under typical endpoint caps (e.g. SiliconFlow's 50MB / 1h), so long recordings are
        // transcribed in full instead of being truncated by the size limit.
        private const val TRANSCRIBE_SEGMENT_SECONDS = 120

        @Volatile
        private var INSTANCE: TaskRepository? = null

        fun get(context: Context): TaskRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskRepository(
                    appContext = context.applicationContext,
                    dao = AppDatabase.get(context).taskDao(),
                    settingsRepo = SettingsRepository(context.applicationContext),
                    transcriber = OpenAiCompatibleTranscriber(),
                    summarizer = OpenAiCompatibleSummarizer()
                ).also { INSTANCE = it }
            }
    }
}
