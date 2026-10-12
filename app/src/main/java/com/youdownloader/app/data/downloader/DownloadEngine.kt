package com.youdownloader.app.data.downloader

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.youdownloader.app.data.local.AppDatabase
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.util.StorageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DownloadEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Active download coroutine jobs indexed by video ID
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    // State listeners for UI and Foreground Service updates
    private val listeners = ConcurrentHashMap<Long, (VideoEntity) -> Unit>()

    fun registerListener(videoId: Long, listener: (VideoEntity) -> Unit) {
        listeners[videoId] = listener
    }

    fun unregisterListener(videoId: Long) {
        listeners.remove(videoId)
    }

    fun isDownloading(videoId: Long): Boolean {
        return activeJobs[videoId]?.isActive == true
    }

    /**
     * Starts or resumes a download task for the given VideoEntity
     */
    fun startDownload(
        context: Context,
        scope: CoroutineScope,
        video: VideoEntity,
        onProgress: ((VideoEntity) -> Unit)? = null
    ) {
        if (activeJobs[video.id]?.isActive == true) return

        onProgress?.let { registerListener(video.id, it) }

        val job = scope.launch(Dispatchers.IO) {
            executeDownload(context, video)
        }
        activeJobs[video.id] = job
    }

    /**
     * Pauses an active download
     */
    suspend fun pauseDownload(context: Context, videoId: Long) = withContext(Dispatchers.IO) {
        activeJobs[videoId]?.cancel()
        activeJobs.remove(videoId)
        val dao = AppDatabase.getInstance(context).videoDao()
        dao.updateStatus(videoId, DownloadStatus.PAUSED, null)
        dao.getVideoById(videoId)?.let { updated ->
            listeners[videoId]?.invoke(updated)
        }
    }

    /**
     * Cancels an active download and cleans up temporary partial files
     */
    suspend fun cancelDownload(context: Context, videoId: Long) = withContext(Dispatchers.IO) {
        activeJobs[videoId]?.cancel()
        activeJobs.remove(videoId)
        val dao = AppDatabase.getInstance(context).videoDao()
        val video = dao.getVideoById(videoId)
        if (video != null) {
            val videosDir = StorageUtils.getVideosDirectory(context)
            File(videosDir, "vid_${videoId}.tmp").delete()
            File(videosDir, "audio_${videoId}.tmp").delete()
            if (video.filePath.isNotBlank()) {
                File(video.filePath).delete()
            }
            dao.deleteById(videoId)
        }
    }

    private suspend fun executeDownload(context: Context, initialVideo: VideoEntity) {
        val dao = AppDatabase.getInstance(context).videoDao()
        val videoId = initialVideo.id
        var currentVideo = initialVideo

        try {
            // Check storage capacity
            val freeSpace = StorageUtils.getAvailableStorageBytes(context)
            if (freeSpace < 50 * 1024 * 1024) { // Less than 50MB free
                dao.updateStatus(videoId, DownloadStatus.FAILED, "Insufficient storage space.")
                return
            }

            // Download thumbnail first if not already saved
            val thumbPath = downloadThumbnail(context, currentVideo.youtubeId, currentVideo.thumbnailPath)

            // Update state to DOWNLOADING
            dao.updateStatus(videoId, DownloadStatus.DOWNLOADING, null)
            currentVideo = currentVideo.copy(downloadStatus = DownloadStatus.DOWNLOADING, thumbnailPath = thumbPath)
            dao.update(currentVideo)
            listeners[videoId]?.invoke(currentVideo)

            val videosDir = StorageUtils.getVideosDirectory(context)
            val videoTmpFile = File(videosDir, "vid_${videoId}.tmp")
            val sanitizedTitle = currentVideo.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(60)

            val isAudioOnly = currentVideo.quality.contains("Audio", ignoreCase = true)
            val fileExtension = if (isAudioOnly) ".m4a" else ".mp4"
            val finalFile = File(videosDir, "${sanitizedTitle}_${currentVideo.youtubeId}$fileExtension")

            val videoStreamUrl = currentVideo.downloadUrl
                ?: throw IllegalStateException("Download URL is missing.")

            // 1. Download Video Stream (or progressive combined stream / audio-only stream)
            downloadStreamToFile(
                url = videoStreamUrl,
                targetFile = videoTmpFile,
                onProgressUpdate = { bytesDownloaded, totalBytes, percent ->
                    dao.updateProgress(videoId, bytesDownloaded, percent, DownloadStatus.DOWNLOADING)
                    currentVideo = currentVideo.copy(
                        bytesDownloaded = bytesDownloaded,
                        fileSizeBytes = if (totalBytes > 0) totalBytes else currentVideo.fileSizeBytes,
                        progressPercent = percent,
                        downloadStatus = DownloadStatus.DOWNLOADING
                    )
                    listeners[videoId]?.invoke(currentVideo)
                }
            )

            // 2. Check if a separate audio stream needs to be downloaded & merged
            val audioStreamUrl = currentVideo.audioDownloadUrl
            if (!audioStreamUrl.isNullOrBlank() && !isAudioOnly) {
                val audioTmpFile = File(videosDir, "audio_${videoId}.tmp")
                downloadStreamToFile(
                    url = audioStreamUrl,
                    targetFile = audioTmpFile,
                    onProgressUpdate = { _, _, _ -> }
                )

                // 3. Merge Video and Audio using Android native MediaMuxer
                mergeVideoAndAudio(videoTmpFile, audioTmpFile, finalFile)

                // Clean temporary tracks
                videoTmpFile.delete()
                audioTmpFile.delete()
            } else {
                // Progressive stream or audio-only: already complete and valid
                if (finalFile.exists()) finalFile.delete()
                videoTmpFile.renameTo(finalFile)
            }

            // Verify final file exists and is not empty
            if (!finalFile.exists() || finalFile.length() == 0L) {
                throw IllegalStateException("Downloaded file is empty or corrupted.")
            }

            // Scan file into Android media system
            StorageUtils.scanMediaFile(context, finalFile)

            // Mark completed in database
            val now = System.currentTimeMillis()
            val totalSize = finalFile.length()
            dao.markCompleted(videoId, finalFile.absolutePath, totalSize, now)

            currentVideo = currentVideo.copy(
                filePath = finalFile.absolutePath,
                fileSizeBytes = totalSize,
                downloadStatus = DownloadStatus.COMPLETED,
                progressPercent = 100,
                downloadedAt = now
            )
            listeners[videoId]?.invoke(currentVideo)

        } catch (_: CancellationException) {
            // Task was cancelled or paused
        } catch (e: Exception) {
            e.printStackTrace()
            dao.updateStatus(videoId, DownloadStatus.FAILED, e.message ?: "Download failed.")
            currentVideo = currentVideo.copy(
                downloadStatus = DownloadStatus.FAILED,
                errorMessage = e.message
            )
            listeners[videoId]?.invoke(currentVideo)
        } finally {
            activeJobs.remove(videoId)
        }
    }

    private suspend fun downloadStreamToFile(
        url: String,
        targetFile: File,
        onProgressUpdate: suspend (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 416) {
            throw Exception("HTTP download error: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty HTTP response body")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) existingBytes + contentLength else existingBytes

        val isAppend = response.code == 206 // Partial Content
        val outputStream = FileOutputStream(targetFile, isAppend)
        val inputStream: InputStream = body.byteStream()

        val buffer = ByteArray(8192)
        var bytesRead = 0
        var totalRead = if (isAppend) existingBytes else 0L
        var lastUpdateTime = 0L

        try {
            while (isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= 800) {
                    lastUpdateTime = now
                    val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                    onProgressUpdate(totalRead, totalBytes, percent)
                }
            }
        } finally {
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            body.close()
        }
    }

    /**
     * Downloads and saves thumbnail to local storage
     */
    private fun downloadThumbnail(context: Context, videoId: String, currentThumbPath: String): String {
        return try {
            val thumbsDir = StorageUtils.getThumbnailsDirectory(context)
            val thumbFile = File(thumbsDir, "thumb_${videoId}.jpg")
            if (thumbFile.exists() && thumbFile.length() > 0) {
                return thumbFile.absolutePath
            }

            val request = Request.Builder()
                .url(if (currentThumbPath.startsWith("http")) currentThumbPath else "https://img.youtube.com/vi/$videoId/hqdefault.jpg")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(thumbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                thumbFile.absolutePath
            } else {
                currentThumbPath
            }
        } catch (_: Exception) {
            currentThumbPath
        }
    }

    /**
     * Merges video track and audio track into a single MP4 container using Android MediaMuxer.
     * Interleaves samples by timestamp to produce a fully standard, 100% playable MP4 file.
     */
    private fun mergeVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxingSucceeded = false

        try {
            if (outputFile.exists()) outputFile.delete()

            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            // Find video track
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            // Find audio track
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                videoFile.copyTo(outputFile, overwrite = true)
                return
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                videoFile.copyTo(outputFile, overwrite = true)
                return
            }

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            // Ensure audio is compatible with MPEG-4 muxer (must be AAC / mp4a)
            if (!audioMime.contains("mp4a", ignoreCase = true) && !audioMime.contains("aac", ignoreCase = true)) {
                // If audio codec is incompatible, fallback to video file directly so it remains playable
                videoFile.copyTo(outputFile, overwrite = true)
                return
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = false

            // Interleaved writing loop by presentation time
            while (!videoDone || !audioDone) {
                val videoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoDone && (videoTime <= audioTime || audioDone)) {
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoDone = true
                    } else {
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        audioDone = true
                    } else {
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            muxingSucceeded = true
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}

            // If muxing failed or output file is 0 bytes, fallback cleanly to videoFile
            if (!muxingSucceeded || !outputFile.exists() || outputFile.length() == 0L) {
                if (outputFile.exists()) outputFile.delete()
                if (videoFile.exists()) {
                    videoFile.copyTo(outputFile, overwrite = true)
                }
            }
        }
    }
}
