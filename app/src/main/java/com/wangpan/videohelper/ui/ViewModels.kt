package com.wangpan.videohelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wangpan.videohelper.data.TaskRepository
import com.wangpan.videohelper.data.db.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository.get(app)

    val tasks: StateFlow<List<TaskEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // On open, rebuild any tasks whose recordings still exist on disk but were lost from the DB
        // (e.g. after a reinstall), so the tasks page isn't empty when recordings are present.
        rescanRecordings()
    }

    /** Re-scans the recording save directory and imports orphaned recordings as tasks. */
    fun rescanRecordings() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.importRecordingsFromStorage() }
    }

    fun runAll(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.runAll(id) }
    }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
}

class TaskDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository.get(app)

    fun task(id: String) = repo.observe(id)

    fun extractAudio(id: String) = launchStage { repo.extractAudio(id) }
    fun transcribe(id: String) = launchStage { repo.transcribe(id) }
    fun summarize(id: String) = launchStage { repo.summarize(id) }
    fun runAll(id: String) = launchStage { repo.runAll(id) }

    /** Exports the article to a .md file; reports the path or error back on the main thread. */
    fun exportArticle(id: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repo.exportArticleToMarkdown(id) }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun launchStage(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
        }
    }
}
