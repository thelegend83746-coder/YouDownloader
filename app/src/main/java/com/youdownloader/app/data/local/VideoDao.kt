package com.youdownloader.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Query("SELECT * FROM videos ORDER BY id DESC")
    fun getAllVideosFlow(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE download_status = 'COMPLETED' ORDER BY downloaded_at DESC, id DESC")
    fun getCompletedVideosFlow(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE download_status != 'COMPLETED' ORDER BY id DESC")
    fun getActiveDownloadsFlow(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getVideoById(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE id = :id")
    fun getVideoByIdFlow(id: Long): Flow<VideoEntity?>

    @Query("SELECT * FROM videos WHERE youtube_id = :youtubeId LIMIT 1")
    suspend fun getVideoByYoutubeId(youtubeId: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: VideoEntity): Long

    @Update
    suspend fun update(video: VideoEntity)

    @Query("UPDATE videos SET bytes_downloaded = :bytesDownloaded, progress_percent = :progressPercent, download_status = :status WHERE id = :id")
    suspend fun updateProgress(id: Long, bytesDownloaded: Long, progressPercent: Int, status: DownloadStatus)

    @Query("UPDATE videos SET download_status = :status, error_message = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus, error: String? = null)

    @Query("UPDATE videos SET download_status = 'COMPLETED', file_path = :filePath, file_size_bytes = :fileSizeBytes, downloaded_at = :downloadedAt, progress_percent = 100 WHERE id = :id")
    suspend fun markCompleted(id: Long, filePath: String, fileSizeBytes: Long, downloadedAt: Long)

    @Query("UPDATE videos SET last_position_ms = :positionMs WHERE id = :id")
    suspend fun updatePlaybackPosition(id: Long, positionMs: Long)

    @Delete
    suspend fun delete(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteById(id: Long)
}
