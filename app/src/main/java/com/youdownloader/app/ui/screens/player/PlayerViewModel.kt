package com.youdownloader.app.ui.screens.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.data.repository.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    private val _video = MutableStateFlow<VideoEntity?>(null)
    val video: StateFlow<VideoEntity?> = _video.asStateFlow()

    fun loadVideo(videoId: Long) {
        viewModelScope.launch {
            _video.value = repository.getVideoById(videoId)
        }
    }

    fun savePlaybackPosition(positionMs: Long) {
        val current = _video.value ?: return
        viewModelScope.launch {
            repository.updatePlaybackPosition(current.id, positionMs)
        }
    }

    fun deleteVideo(onDeleted: () -> Unit) {
        val current = _video.value ?: return
        viewModelScope.launch {
            repository.deleteVideo(current)
            onDeleted()
        }
    }
}
