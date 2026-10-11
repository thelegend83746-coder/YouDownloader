package com.youdownloader.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Download status states matching PRD Section 7
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

/**
 * Video Quality representations
 */
enum class VideoQuality(val label: String, val height: Int) {
    Q_1080P("1080p (FHD)", 1080),
    Q_720P("720p (HD)", 720),
    Q_480P("480p (SD)", 480),
    Q_360P("360p", 360),
    AUDIO_ONLY("Audio Only (M4A)", 0);

    companion object {
        fun fromLabel(label: String?): VideoQuality {
            return entries.firstOrNull { it.name.equals(label, ignoreCase = true) || it.label.equals(label, ignoreCase = true) }
                ?: Q_720P
        }
    }
}

/**
 * Stream format information extracted from YouTube
 */
data class StreamFormat(
    val itag: Int,
    val mimeType: String,
    val qualityLabel: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0,
    val contentLength: Long = 0,
    val url: String,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true
)

/**
 * Video metadata preview fetched after entering a link
 */
data class VideoPreviewInfo(
    val youtubeId: String,
    val originalUrl: String,
    val title: String,
    val channel: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val availableQualities: List<VideoQuality>,
    val formats: List<StreamFormat>
)

/**
 * Room Entity representing a video in the local database.
 * Matches PRD Section 7 schema:
 * id, youtube_id, title, channel, duration_ms, thumbnail_path,
 * file_path, file_size_bytes, quality, download_status, downloaded_at, last_position_ms
 */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "youtube_id")
    val youtubeId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "channel")
    val channel: String,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String = "",

    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "quality")
    val quality: String = VideoQuality.Q_720P.label,

    @ColumnInfo(name = "download_status")
    val downloadStatus: DownloadStatus = DownloadStatus.QUEUED,

    @ColumnInfo(name = "downloaded_at")
    val downloadedAt: Long = 0L,

    @ColumnInfo(name = "last_position_ms")
    val lastPositionMs: Long = 0L,

    // Runtime state tracking for active downloads
    @ColumnInfo(name = "progress_percent")
    val progressPercent: Int = 0,

    @ColumnInfo(name = "bytes_downloaded")
    val bytesDownloaded: Long = 0L,

    @ColumnInfo(name = "download_url")
    val downloadUrl: String? = null,

    @ColumnInfo(name = "audio_download_url")
    val audioDownloadUrl: String? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
