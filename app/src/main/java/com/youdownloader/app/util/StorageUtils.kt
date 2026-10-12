package com.youdownloader.app.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
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
     * Checks if physical file exists at the given path and is non-empty.
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
     * Notifies Android media scanner so downloaded media is visible in system media store
     */
    fun scanMediaFile(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Opens the video file in an external media player (VLC, MX Player, Photos, etc.)
     */
    fun openVideoWithExternalPlayer(context: Context, file: File) {
        try {
            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(context, "File does not exist or is empty.", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = if (file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".mp3", ignoreCase = true)) {
                "audio/*"
            } else {
                "video/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Play Media With").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not open external player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

        val mimeType = if (file.name.endsWith(".m4a", ignoreCase = true)) "audio/*" else "video/*"

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, videoTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
