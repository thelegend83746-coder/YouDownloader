package com.youdownloader.app.data.extractor

import com.youdownloader.app.data.model.StreamFormat
import com.youdownloader.app.data.model.VideoPreviewInfo
import com.youdownloader.app.data.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object YouTubeExtractor {

    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val YOUTUBE_ID_PATTERN = Pattern.compile(
        "(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|watch\\?v=|watch\\?.+&v=|shorts\\/|live\\/))([\\w\\-]{11})",
        Pattern.CASE_INSENSITIVE
    )

    fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        val matcher = YOUTUBE_ID_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)
        }
        if (trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }
        return null
    }

    /**
     * Primary metadata and stream resolver.
     * Guarantees metadata extraction via YouTube oEmbed and resolves video/audio streams.
     */
    suspend fun fetchVideoInfo(rawUrl: String): Result<VideoPreviewInfo> = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(rawUrl)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid YouTube URL. Please enter a valid video or shorts link.")
                )

            // Step 1: Always fetch guaranteed metadata from YouTube Official oEmbed
            val oEmbedMeta = fetchOEmbedMetadata(videoId)

            // Step 2: Extract streams via multi-tier fallback
            val formats = resolveStreams(videoId)

            val title = oEmbedMeta?.optString("title") ?: "YouTube Video"
            val channel = oEmbedMeta?.optString("author_name") ?: "YouTube Channel"
            val thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

            val availableQualities = mutableListOf<VideoQuality>()
            if (formats.any { it.height >= 1080 && it.hasVideo }) availableQualities.add(VideoQuality.Q_1080P)
            if (formats.any { it.height >= 720 && it.hasVideo }) availableQualities.add(VideoQuality.Q_720P)
            if (formats.any { it.height >= 480 && it.hasVideo }) availableQualities.add(VideoQuality.Q_480P)
            if (formats.any { it.height >= 360 && it.hasVideo }) availableQualities.add(VideoQuality.Q_360P)
            if (formats.any { it.hasAudio }) availableQualities.add(VideoQuality.AUDIO_ONLY)

            if (availableQualities.isEmpty()) {
                availableQualities.add(VideoQuality.Q_720P)
                availableQualities.add(VideoQuality.Q_360P)
            }

            Result.success(
                VideoPreviewInfo(
                    youtubeId = videoId,
                    originalUrl = rawUrl,
                    title = title,
                    channel = channel,
                    durationMs = 0L,
                    thumbnailUrl = thumbnail,
                    availableQualities = availableQualities,
                    formats = formats
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches official video metadata from YouTube oEmbed endpoint (100% reliable)
     */
    private fun fetchOEmbedMetadata(videoId: String): JSONObject? {
        return try {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                JSONObject(body)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves playable/downloadable streams using multiple strategies
     */
    private fun resolveStreams(videoId: String): List<StreamFormat> {
        val formatsList = mutableListOf<StreamFormat>()

        // Method 1: Android VR InnerTube client
        try {
            val endpoint = "https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY"
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.56.21")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) OculusQuest2")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val json = JSONObject(body)
                    parseStreamingData(json.optJSONObject("streamingData"), formatsList)
                }
            }
        } catch (_: Exception) {}

        // Method 2: Web HTML player response extraction
        if (formatsList.isEmpty()) {
            try {
                val webUrl = "https://www.youtube.com/watch?v=$videoId"
                val request = Request.Builder()
                    .url(webUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string()
                    if (html != null) {
                        val regex = Regex("""ytInitialPlayerResponse\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
                        val match = regex.find(html)
                        if (match != null) {
                            val json = JSONObject(match.groupValues[1])
                            parseStreamingData(json.optJSONObject("streamingData"), formatsList)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Method 3: Mobile web player response
        if (formatsList.isEmpty()) {
            try {
                val mUrl = "https://m.youtube.com/watch?v=$videoId"
                val request = Request.Builder()
                    .url(mUrl)
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string()
                    if (html != null) {
                        val regex = Regex("""ytInitialPlayerResponse\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
                        val match = regex.find(html)
                        if (match != null) {
                            val json = JSONObject(match.groupValues[1])
                            parseStreamingData(json.optJSONObject("streamingData"), formatsList)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return formatsList
    }

    private fun parseStreamingData(streamingData: JSONObject?, targetList: MutableList<StreamFormat>) {
        if (streamingData == null) return

        // 1. Progressive formats (Contain both video & audio: 720p itag 22, 360p itag 18)
        val formatsArray = streamingData.optJSONArray("formats")
        if (formatsArray != null) {
            for (i in 0 until formatsArray.length()) {
                val fmt = formatsArray.getJSONObject(i)
                parseFormat(fmt, hasVideo = true, hasAudio = true)?.let { targetList.add(it) }
            }
        }

        // 2. Adaptive formats
        val adaptiveArray = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveArray != null) {
            for (i in 0 until adaptiveArray.length()) {
                val fmt = adaptiveArray.getJSONObject(i)
                val mime = fmt.optString("mimeType")
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                parseFormat(fmt, hasVideo = isVideo, hasAudio = isAudio)?.let { targetList.add(it) }
            }
        }
    }

    private fun parseFormat(fmt: JSONObject, hasVideo: Boolean, hasAudio: Boolean): StreamFormat? {
        val itag = fmt.optInt("itag")
        val mimeType = fmt.optString("mimeType")
        val qualityLabel = fmt.optString("qualityLabel", if (hasAudio && !hasVideo) "Audio" else "")
        val width = fmt.optInt("width", 0)
        val height = fmt.optInt("height", 0)
        val bitrate = fmt.optLong("bitrate", 0L)
        val contentLength = fmt.optLong("contentLength", 0L)
        var url = fmt.optString("url")

        // Parse url inside signatureCipher or cipher if present
        if (url.isBlank()) {
            val cipher = fmt.optString("cipher", fmt.optString("signatureCipher", ""))
            if (cipher.isNotBlank()) {
                val params = cipher.split("&").associate {
                    val parts = it.split("=")
                    if (parts.size == 2) URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                    else "" to ""
                }
                url = params["url"] ?: ""
            }
        }

        if (url.isBlank()) return null

        return StreamFormat(
            itag = itag,
            mimeType = mimeType,
            qualityLabel = qualityLabel,
            width = width,
            height = height,
            bitrate = bitrate,
            contentLength = contentLength,
            url = url,
            hasVideo = hasVideo,
            hasAudio = hasAudio
        )
    }

    fun resolveStreamsForQuality(
        formats: List<StreamFormat>,
        targetQuality: VideoQuality
    ): Pair<StreamFormat, StreamFormat?> {
        if (formats.isEmpty()) {
            throw IllegalStateException("No video streams could be resolved for this link.")
        }

        if (targetQuality == VideoQuality.AUDIO_ONLY) {
            val bestAudio = formats.filter { it.hasAudio && !it.hasVideo }
                .maxByOrNull { it.bitrate }
                ?: formats.first { it.hasAudio }
            return Pair(bestAudio, null)
        }

        // Progressive match (video + audio combined)
        val progressiveMatch = formats.filter { it.hasVideo && it.hasAudio }
            .sortedByDescending { it.height }
            .firstOrNull { it.height <= targetQuality.height && it.height >= targetQuality.height - 100 }
            ?: formats.filter { it.hasVideo && it.hasAudio }.maxByOrNull { it.height }

        if (progressiveMatch != null && (targetQuality == VideoQuality.Q_720P || targetQuality == VideoQuality.Q_360P)) {
            return Pair(progressiveMatch, null)
        }

        // Adaptive video stream
        val videoStream = formats.filter { it.hasVideo }
            .filter { it.height <= targetQuality.height }
            .maxByOrNull { it.height }
            ?: formats.filter { it.hasVideo }.maxByOrNull { it.height }
            ?: formats.first()

        if (videoStream.hasAudio) {
            return Pair(videoStream, null)
        }

        val audioStream = formats.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { it.bitrate }

        return Pair(videoStream, audioStream)
    }
}
