package com.youdownloader.app.data.repository

import android.content.Context
import com.youdownloader.app.data.downloader.DownloadEngine
import com.youdownloader.app.data.downloader.DownloadService
import com.youdownloader.app.data.extractor.YouTubeExtractor
import com.youdownloader.app.data.local.AppDatabase
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.data.model.VideoPreviewInfo
import com.youdownloader.app.data.model.VideoQuality
import com.youdownloader.app.util.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.videoDao()

    /**
     * Completed videos flow with physical file validation
     */
    val completedVideosFlow: Flow<List<VideoEntity>> = dao.getCompletedVideosFlow()
        .map { list ->
            // Filter or flag videos where file was removed externally
            list.map { video ->
                if (video.filePath.isNotBlank() && !StorageUtils.fileExists(video.filePath)) {
                    video.copy(errorMessage = "File missing from storage")
                } else {
                    video
                }
            }
        }

    /**
     * Active and paused downloads flow
     */
    val activeDownloadsFlow: Flow<List<VideoEntity>> = dao.getActiveDownloadsFlow()

    suspend fun getVideoById(id: Long): VideoEntity? = withContext(Dispatchers.IO) {
        dao.getVideoById(id)
    }

    fun getVideoByIdFlow(id: Long): Flow<VideoEntity?> = dao.getVideoByIdFlow(id)

    /**
     * Fetch video preview from URL using YouTubeExtractor
     */
    suspend fun fetchPreview(url: String): Result<VideoPreviewInfo> {
        return YouTubeExtractor.fetchVideoInfo(context, url)
    }

    /**
     * Enqueue a new download
     */
    suspend fun enqueueDownload(preview: VideoPreviewInfo, selectedQuality: VideoQuality): Long = withContext(Dispatchers.IO) {
        val (videoStream, audioStream) = YouTubeExtractor.resolveStreamsForQuality(
            formats = preview.formats,
            targetQuality = selectedQuality
        )

        val entity = VideoEntity(
            youtubeId = preview.youtubeId,
            title = preview.title,
            channel = preview.channel,
            durationMs = preview.durationMs,
            thumbnailPath = preview.thumbnailUrl,
            filePath = "",
            fileSizeBytes = videoStream.contentLength + (audioStream?.contentLength ?: 0L),
            quality = selectedQuality.label,
            downloadStatus = DownloadStatus.QUEUED,
            downloadedAt = 0L,
            lastPositionMs = 0L,
            progressPercent = 0,
            bytesDownloaded = 0L,
            downloadUrl = videoStream.url,
            audioDownloadUrl = audioStream?.url,
            errorMessage = null
        )

        val id = dao.insert(entity)
        // Start Foreground Service
        DownloadService.startDownload(context, id)
        id
    }

    suspend fun pauseDownload(id: Long) {
        DownloadService.pauseDownload(context, id)
    }

    suspend fun resumeDownload(id: Long) {
        DownloadService.resumeDownload(context, id)
    }

    suspend fun cancelDownload(id: Long) {
        DownloadService.cancelDownload(context, id)
    }

    /**
     * Deletes video record and removes local video & thumbnail files
     */
    suspend fun deleteVideo(video: VideoEntity) = withContext(Dispatchers.IO) {
        // Cancel if active
        if (DownloadEngine.isDownloading(video.id)) {
            DownloadService.cancelDownload(context, video.id)
        }
        // Delete local files
        if (video.filePath.isNotBlank()) {
            StorageUtils.deleteFile(video.filePath)
        }
        if (video.thumbnailPath.isNotBlank() && !video.thumbnailPath.startsWith("http")) {
            StorageUtils.deleteFile(video.thumbnailPath)
        }
        // Delete DB record
        dao.delete(video)
    }

    suspend fun updatePlaybackPosition(id: Long, positionMs: Long) = withContext(Dispatchers.IO) {
        dao.updatePlaybackPosition(id, positionMs)
    }
}
