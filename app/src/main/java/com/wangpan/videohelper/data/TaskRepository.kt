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
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        try {
            val dir = File(appContext.filesDir, "audio").apply { mkdirs() }
            val wav = File(dir, "audio_$id.wav")
            AudioExtractor.extractToWav(File(task.videoPath), wav)
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
        }
    }

    /** Stage 3: transcribe the extracted audio to text. */
    suspend fun transcribe(id: String) {
        val task = dao.getById(id) ?: return
        val audio = task.audioPath?.let { File(it) }
            ?: error("请先抽取音频")
        val settings = settingsRepo.settings.first()
        dao.update(task.copy(transcribeStatus = StageStatus.RUNNING, errorMessage = null))
        try {
            val text = transcriber.transcribe(audio, settings)
            dao.update(
                dao.getById(id)!!.copy(
                    transcript = text,
                    transcribeStatus = StageStatus.DONE,
                    errorMessage = null
                )
            )
        } catch (e: Exception) {
            dao.update(dao.getById(id)!!.copy(transcribeStatus = StageStatus.FAILED, errorMessage = e.message))
            throw e
        }
    }

    /** Stage 4: summarize the transcript into a finished article. */
    suspend fun summarize(id: String) {
        val task = dao.getById(id) ?: return
        val transcript = task.transcript?.takeIf { it.isNotBlank() }
            ?: error("请先转写文本")
        val settings = settingsRepo.settings.first()
        dao.update(task.copy(summarizeStatus = StageStatus.RUNNING, errorMessage = null))
        try {
            val article = summarizer.summarize(transcript, settings)
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

        val dir = com.wangpan.videohelper.storage.AppStorage.outputDir(appContext)
        val safeTitle = task.title.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(dir, "article_${safeTitle}_$stamp.md")
        out.writeText("# ${task.title}\n\n$article\n")
        return out.absolutePath
    }

    companion object {
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
