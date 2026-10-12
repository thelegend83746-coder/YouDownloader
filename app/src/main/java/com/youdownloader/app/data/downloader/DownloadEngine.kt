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
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DownloadEngine {

    // YouTube Official Mobile User-Agent bypasses CDN bandwidth rate-throttling
    private const val YOUTUBE_USER_AGENT =
        "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US; Pixel 8 Pro Build/UQ1A.240205.004) gzip"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
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
            File(videosDir, "part_${videoId}.tmp").delete()
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
            currentVideo = currentVideo.copy(
                downloadStatus = DownloadStatus.DOWNLOADING,
                thumbnailPath = thumbPath,
                errorMessage = null
            )
            dao.update(currentVideo)
            listeners[videoId]?.invoke(currentVideo)

            val videosDir = StorageUtils.getVideosDirectory(context)
            val sanitizedTitle = currentVideo.title.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(60)

            val isAudioOnly = currentVideo.quality.contains("Audio", ignoreCase = true)
            val fileExtension = if (isAudioOnly) ".m4a" else ".mp4"
            val finalFile = File(videosDir, "${sanitizedTitle}_${currentVideo.youtubeId}$fileExtension")

            val videoStreamUrl = currentVideo.downloadUrl
                ?: throw IllegalStateException("Download URL is missing.")

            val audioStreamUrl = currentVideo.audioDownloadUrl
            val needsMuxing = !audioStreamUrl.isNullOrBlank() && !isAudioOnly

            if (!needsMuxing) {
                // SINGLE STREAM (Progressive video with audio or Audio-only)
                // Downloads directly with turbo speed and zero muxing needed
                val partFile = File(videosDir, "${sanitizedTitle}_${currentVideo.youtubeId}.part")
                downloadStreamToFile(
                    url = videoStreamUrl,
                    targetFile = partFile,
                    basePercent = 0,
                    maxPercent = 99,
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

                safeMoveFile(partFile, finalFile)
            } else {
                // SEPARATE VIDEO + AUDIO (Adaptive 1080p / 720p)
                val videoTmpFile = File(videosDir, "vid_${videoId}_temp.mp4")
                val audioTmpFile = File(videosDir, "aud_${videoId}_temp.m4a")

                // Step 1: Download Video (0% -> 80%)
                downloadStreamToFile(
                    url = videoStreamUrl,
                    targetFile = videoTmpFile,
                    basePercent = 0,
                    maxPercent = 80,
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

                // Step 2: Download Audio (80% -> 95%)
                downloadStreamToFile(
                    url = audioStreamUrl!!,
                    targetFile = audioTmpFile,
                    basePercent = 80,
                    maxPercent = 95,
                    onProgressUpdate = { bytesDownloaded, _, percent ->
                        dao.updateProgress(videoId, currentVideo.bytesDownloaded + bytesDownloaded, percent, DownloadStatus.DOWNLOADING)
                        currentVideo = currentVideo.copy(
                            progressPercent = percent,
                            downloadStatus = DownloadStatus.DOWNLOADING
                        )
                        listeners[videoId]?.invoke(currentVideo)
                    }
                )

                // Step 3: Merge Video and Audio
                val merged = mergeVideoAndAudio(videoTmpFile, audioTmpFile, finalFile)
                if (!merged || !finalFile.exists() || finalFile.length() == 0L) {
                    // Fail-safe: keep video track so user always gets a valid playable file
                    safeMoveFile(videoTmpFile, finalFile)
                }

                videoTmpFile.delete()
                audioTmpFile.delete()
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
                downloadedAt = now,
                errorMessage = null
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
        basePercent: Int = 0,
        maxPercent: Int = 100,
        onProgressUpdate: suspend (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", YOUTUBE_USER_AGENT)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")

        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 416) {
            throw Exception("HTTP download error: ${response.code}")
        }

        // If HTTP 416 (Range Not Satisfiable), file is already complete
        if (response.code == 416) {
            onProgressUpdate(existingBytes, existingBytes, maxPercent)
            return@withContext
        }

        val body = response.body ?: throw Exception("Empty HTTP response body")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) existingBytes + contentLength else existingBytes

        val isAppend = response.code == 206 // Partial Content
        val outputStream = BufferedOutputStream(FileOutputStream(targetFile, isAppend), 128 * 1024)
        val inputStream = BufferedInputStream(body.byteStream(), 128 * 1024)

        // 128 KB buffer for turbo download throughput
        val buffer = ByteArray(128 * 1024)
        var bytesRead = 0
        var totalRead = if (isAppend) existingBytes else 0L
        var lastUpdateTime = 0L

        try {
            while (isActive && inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= 500) {
                    lastUpdateTime = now
                    val ratio = if (totalBytes > 0) (totalRead.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) else 0.0
                    val percent = basePercent + (ratio * (maxPercent - basePercent)).toInt()
                    onProgressUpdate(totalRead, totalBytes, percent.coerceIn(0, maxPercent))
                }
            }
            outputStream.flush()
        } finally {
            try { outputStream.flush() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { body.close() } catch (_: Exception) {}
        }
    }

    /**
     * Safely moves or copies a file across Android filesystems without renameTo failure
     */
    private fun safeMoveFile(src: File, dst: File) {
        if (!src.exists()) return
        if (src.absolutePath == dst.absolutePath) return
        if (dst.exists()) dst.delete()

        val renamed = src.renameTo(dst)
        if (!renamed || !dst.exists() || dst.length() == 0L) {
            src.copyTo(dst, overwrite = true)
            src.delete()
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
                .header("User-Agent", YOUTUBE_USER_AGENT)
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
     * Guaranteed non-crashing timestamp handling and non-zero fallback.
     */
    private fun mergeVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) return false
        if (!audioFile.exists() || audioFile.length() == 0L) return false

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

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

            if (videoTrackIndex == -1 || videoFormat == null || audioTrackIndex == -1 || audioFormat == null) {
                return false
            }

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (!audioMime.contains("mp4a", ignoreCase = true) && !audioMime.contains("aac", ignoreCase = true)) {
                return false
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
            var lastVideoPts = 0L
            var lastAudioPts = 0L

            while (!videoDone || !audioDone) {
                val videoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (!videoDone && (videoTime <= audioTime || audioDone)) {
                    bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        videoDone = true
                    } else {
                        val sampleTime = videoExtractor.sampleTime
                        if (sampleTime >= 0) {
                            bufferInfo.presentationTimeUs = sampleTime
                            lastVideoPts = sampleTime
                        } else {
                            bufferInfo.presentationTimeUs = lastVideoPts + 33333L
                        }
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) {
                        audioDone = true
                    } else {
                        val sampleTime = audioExtractor.sampleTime
                        if (sampleTime >= 0) {
                            bufferInfo.presentationTimeUs = sampleTime
                            lastAudioPts = sampleTime
                        } else {
                            bufferInfo.presentationTimeUs = lastAudioPts + 23219L
                        }
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            return outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }
}
