package com.youdownloader.app.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import java.io.File

object StorageUtils {

    /**
     * Gets or creates the directory for storing downloaded videos.
     * Path: /Android/data/<package>/files/videos/
     */
    fun getVideosDirectory(context: Context): File {
        val dir = context.getExternalFilesDir("videos") ?: File(context.filesDir, "videos")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Gets or creates the directory for storing video thumbnails.
     * Path: /Android/data/<package>/files/thumbs/
     */
    fun getThumbnailsDirectory(context: Context): File {
        val dir = context.getExternalFilesDir("thumbs") ?: File(context.filesDir, "thumbs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns available storage in bytes on the volume hosting the video folder.
     */
    fun getAvailableStorageBytes(context: Context): Long {
        return try {
            val dir = getVideosDirectory(context)
            val stat = StatFs(dir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Returns total storage in bytes on the volume hosting the video folder.
     */
    fun getTotalStorageBytes(context: Context): Long {
        return try {
            val dir = getVideosDirectory(context)
            val stat = StatFs(dir.path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Checks if physical file exists at the given path.
     */
    fun fileExists(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val file = File(filePath)
        return file.exists() && file.isFile && file.length() > 0
    }

    /**
     * Deletes a local file safely.
     */
    fun deleteFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val file = File(filePath)
        return if (file.exists()) file.delete() else false
    }

    /**
     * Generates a share intent for a downloaded video using FileProvider.
     */
    fun createShareVideoIntent(context: Context, filePath: String, videoTitle: String): Intent? {
        val file = File(filePath)
        if (!file.exists()) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, videoTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
