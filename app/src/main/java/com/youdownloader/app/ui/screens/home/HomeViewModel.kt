package com.youdownloader.app.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.data.model.VideoPreviewInfo
import com.youdownloader.app.data.model.VideoQuality
import com.youdownloader.app.data.repository.VideoRepository
import com.youdownloader.app.util.Formatters
import com.youdownloader.app.util.StorageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val isSheetOpen: Boolean = false,
    val isLoadingPreview: Boolean = false,
    val previewInfo: VideoPreviewInfo? = null,
    val errorMessage: String? = null,
    val freeStorageFormatted: String = "",
    val totalStorageFormatted: String = ""
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    val completedVideos: StateFlow<List<VideoEntity>> = repository.completedVideosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeDownloads: StateFlow<List<VideoEntity>> = repository.activeDownloadsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        val app = getApplication<Application>()
        val freeBytes = StorageUtils.getAvailableStorageBytes(app)
        val totalBytes = StorageUtils.getTotalStorageBytes(app)
        _uiState.value = _uiState.value.copy(
            freeStorageFormatted = Formatters.formatFileSize(freeBytes),
            totalStorageFormatted = Formatters.formatFileSize(totalBytes)
        )
    }

    fun openBottomSheet() {
        _uiState.value = _uiState.value.copy(
            isSheetOpen = true,
            previewInfo = null,
            errorMessage = null,
            isLoadingPreview = false
        )
    }

    fun closeBottomSheet() {
        _uiState.value = _uiState.value.copy(
            isSheetOpen = false,
            previewInfo = null,
            errorMessage = null,
            isLoadingPreview = false
        )
    }

    fun fetchPreview(url: String) {
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a valid link.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingPreview = true,
                errorMessage = null,
                previewInfo = null
            )

            val result = repository.fetchPreview(url)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isLoadingPreview = false,
                    previewInfo = result.getOrNull(),
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoadingPreview = false,
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to extract video."
                )
            }
        }
    }

    fun startDownload(preview: VideoPreviewInfo, quality: VideoQuality) {
        viewModelScope.launch {
            repository.enqueueDownload(preview, quality)
            closeBottomSheet()
            refreshStorageInfo()
        }
    }

    fun pauseDownload(id: Long) {
        viewModelScope.launch {
            repository.pauseDownload(id)
        }
    }

    fun resumeDownload(id: Long) {
        viewModelScope.launch {
            repository.resumeDownload(id)
        }
    }

    fun cancelDownload(id: Long) {
        viewModelScope.launch {
            repository.cancelDownload(id)
            refreshStorageInfo()
        }
    }

    fun deleteVideo(video: VideoEntity) {
        viewModelScope.launch {
            repository.deleteVideo(video)
            refreshStorageInfo()
        }
    }
}
