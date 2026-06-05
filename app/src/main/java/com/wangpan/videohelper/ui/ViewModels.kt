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

class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository.get(app)

    val tasks: StateFlow<List<TaskEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
}

class TaskDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository.get(app)

    fun task(id: String) = repo.observe(id)

    fun extractAudio(id: String) = launchStage { repo.extractAudio(id) }
    fun transcribe(id: String) = launchStage { repo.transcribe(id) }
    fun summarize(id: String) = launchStage { repo.summarize(id) }
    fun runAll(id: String) = launchStage { repo.runAll(id) }

    private fun launchStage(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
        }
    }
}
