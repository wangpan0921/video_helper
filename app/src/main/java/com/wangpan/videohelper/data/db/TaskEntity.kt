package com.wangpan.videohelper.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lifecycle of a single capture-to-article job. Each stage produces a persisted artifact so the
 * pipeline can resume or retry a single step without redoing the whole chain.
 */
enum class TaskStage {
    RECORDED,      // MP4 captured, nothing processed yet
    AUDIO_READY,   // audio extracted to a standalone file
    TRANSCRIBED,   // ASR text produced
    SUMMARIZED     // final article produced
}

enum class StageStatus {
    IDLE,       // not started
    RUNNING,    // in progress
    DONE,       // succeeded
    FAILED      // failed; see errorMessage
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,

    // Artifacts (absolute paths in app-private storage; null until produced).
    val videoPath: String,
    val audioPath: String? = null,
    val transcript: String? = null,
    val article: String? = null,

    // Recording metadata.
    val durationMs: Long = 0L,
    val micIncluded: Boolean = false,

    // Per-stage status so a failure in one step does not lose earlier artifacts.
    val audioStatus: StageStatus = StageStatus.IDLE,
    val transcribeStatus: StageStatus = StageStatus.IDLE,
    val summarizeStatus: StageStatus = StageStatus.IDLE,
    val errorMessage: String? = null
)
