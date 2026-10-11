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

    // Universal public InnerTube API Key used across YouTube Web & Android applications
    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Regex patterns for YouTube URLs
    private val YOUTUBE_ID_PATTERN = Pattern.compile(
        "(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|watch\\?v=|watch\\?.+&v=|shorts\\/|live\\/))([\\w\\-]{11})",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extracts YouTube Video ID from any standard URL
     * Supports: watch?v=..., youtu.be/..., shorts/..., embed/..., m.youtube.com/...
     */
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
     * Fetches video metadata and direct downloadable stream formats.
     * Uses multi-tier extraction strategy for 100% resilience:
     * 1. ANDROID_VR InnerTube endpoint (provides direct streams with audio & video)
     * 2. Web HTML extraction (ytInitialPlayerResponse)
     * 3. Public distributed streaming fallback
     */
    suspend fun fetchVideoInfo(rawUrl: String): Result<VideoPreviewInfo> = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(rawUrl)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid YouTube link. Please paste a standard video or shorts URL.")
                )

            // Strategy 1: ANDROID_VR InnerTube client (returns unthrottled direct playback URLs)
            val vrResult = fetchWithAndroidVr(videoId, rawUrl)
            if (vrResult.isSuccess) {
                return@withContext vrResult
            }

            // Strategy 2: Web HTML parser (ytInitialPlayerResponse)
            val htmlResult = fetchFromWebHtml(videoId, rawUrl)
            if (htmlResult.isSuccess) {
                return@withContext htmlResult
            }

            // Strategy 3: Piped stream fallback
            val pipedResult = fetchFromPipedFallback(videoId, rawUrl)
            if (pipedResult.isSuccess) {
                return@withContext pipedResult
            }

            Result.failure(Exception("Could not extract streams. Please check your internet connection or video visibility."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Strategy 1: Queries YouTube InnerTube with ANDROID_VR client
     * Returns direct, signature-free google video playback URLs
     */
    private fun fetchWithAndroidVr(videoId: String, originalUrl: String): Result<VideoPreviewInfo> {
        return try {
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
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(Exception("HTTP ${response.code}"))

            val body = response.body?.string() ?: return Result.failure(Exception("Empty body"))
            val json = JSONObject(body)

            val playabilityStatus = json.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status") ?: ""
            if (status != "OK") {
                val reason = playabilityStatus?.optString("reason") ?: "Video unplayable in current region"
                return Result.failure(Exception(reason))
            }

            parseInnerTubePlayerJson(json, videoId, originalUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Strategy 2: Extracts ytInitialPlayerResponse from youtube.com/watch page
     */
    private fun fetchFromWebHtml(videoId: String, originalUrl: String): Result<VideoPreviewInfo> {
        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Result.failure(Exception("Web request failed: ${response.code}"))

            val html = response.body?.string() ?: return Result.failure(Exception("Empty HTML"))
            val regex = Regex("""ytInitialPlayerResponse\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html) ?: return Result.failure(Exception("Player response not found in page"))

            val json = JSONObject(match.groupValues[1])
            parseInnerTubePlayerJson(json, videoId, originalUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Strategy 3: Fallback using public stream resolver
     */
    private fun fetchFromPipedFallback(videoId: String, originalUrl: String): Result<VideoPreviewInfo> {
        val instances = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de"
        )

        for (host in instances) {
            try {
                val request = Request.Builder()
                    .url("$host/streams/$videoId")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)

                val title = json.optString("title", "YouTube Video")
                val channel = json.optString("uploader", "Unknown Channel")
                val duration = json.optLong("duration", 0L) * 1000L
                val thumbnail = json.optString("thumbnailUrl", "https://img.youtube.com/vi/$videoId/hqdefault.jpg")

                val formatsList = mutableListOf<StreamFormat>()

                // Video streams
                val vStreams = json.optJSONArray("videoStreams")
                if (vStreams != null) {
                    for (i in 0 until vStreams.length()) {
                        val item = vStreams.getJSONObject(i)
                        val streamUrl = item.optString("url")
                        val height = item.optInt("height", 720)
                        val quality = item.optString("quality", "${height}p")
                        val mime = item.optString("mimeType", "video/mp4")
                        if (streamUrl.isNotBlank()) {
                            formatsList.add(
                                StreamFormat(
                                    itag = item.optInt("itag", 22),
                                    mimeType = mime,
                                    qualityLabel = quality,
                                    height = height,
                                    bitrate = item.optLong("bitrate", 0L),
                                    contentLength = item.optLong("contentLength", 0L),
                                    url = streamUrl,
                                    hasVideo = true,
                                    hasAudio = item.optBoolean("videoOnly", false).not()
                                )
                            )
                        }
                    }
                }

                // Audio streams
                val aStreams = json.optJSONArray("audioStreams")
                if (aStreams != null) {
                    for (i in 0 until aStreams.length()) {
                        val item = aStreams.getJSONObject(i)
                        val streamUrl = item.optString("url")
                        if (streamUrl.isNotBlank()) {
                            formatsList.add(
                                StreamFormat(
                                    itag = item.optInt("itag", 140),
                                    mimeType = item.optString("mimeType", "audio/mp4"),
                                    qualityLabel = "Audio",
                                    height = 0,
                                    bitrate = item.optLong("bitrate", 128000L),
                                    contentLength = item.optLong("contentLength", 0L),
                                    url = streamUrl,
                                    hasVideo = false,
                                    hasAudio = true
                                )
                            )
                        }
                    }
                }

                if (formatsList.isNotEmpty()) {
                    val availableQualities = mutableListOf<VideoQuality>()
                    if (formatsList.any { it.height >= 1080 && it.hasVideo }) availableQualities.add(VideoQuality.Q_1080P)
                    if (formatsList.any { it.height >= 720 && it.hasVideo }) availableQualities.add(VideoQuality.Q_720P)
                    if (formatsList.any { it.height >= 480 && it.hasVideo }) availableQualities.add(VideoQuality.Q_480P)
                    if (formatsList.any { it.height >= 360 && it.hasVideo }) availableQualities.add(VideoQuality.Q_360P)
                    if (formatsList.any { it.hasAudio }) availableQualities.add(VideoQuality.AUDIO_ONLY)

                    return Result.success(
                        VideoPreviewInfo(
                            youtubeId = videoId,
                            originalUrl = originalUrl,
                            title = title,
                            channel = channel,
                            durationMs = duration,
                            thumbnailUrl = thumbnail,
                            availableQualities = if (availableQualities.isNotEmpty()) availableQualities else listOf(VideoQuality.Q_720P),
                            formats = formatsList
                        )
                    )
                }
            } catch (_: Exception) {
                // Try next instance
            }
        }
        return Result.failure(Exception("All fallback streams were unreachable."))
    }

    private fun parseInnerTubePlayerJson(
        json: JSONObject,
        videoId: String,
        originalUrl: String
    ): Result<VideoPreviewInfo> {
        val videoDetails = json.optJSONObject("videoDetails")
            ?: return Result.failure(Exception("Video details not available."))

        val title = videoDetails.optString("title", "YouTube Video")
        val channel = videoDetails.optString("author", "Unknown Channel")
        val lengthSeconds = videoDetails.optString("lengthSeconds", "0").toLongOrNull() ?: 0L
        val durationMs = lengthSeconds * 1000L

        var thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        val thumbnailObj = videoDetails.optJSONObject("thumbnail")
        if (thumbnailObj != null) {
            val thumbs = thumbnailObj.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                val highestThumb = thumbs.getJSONObject(thumbs.length() - 1)
                thumbnailUrl = highestThumb.optString("url", thumbnailUrl)
            }
        }

        val streamingData = json.optJSONObject("streamingData")
            ?: return Result.failure(Exception("No streaming data available for this video."))

        val formatsList = mutableListOf<StreamFormat>()

        // Progressive formats (Both video & audio included: 720p itag 22, 360p itag 18)
        val formatsArray = streamingData.optJSONArray("formats")
        if (formatsArray != null) {
            for (i in 0 until formatsArray.length()) {
                val fmt = formatsArray.getJSONObject(i)
                parseFormat(fmt, hasVideo = true, hasAudio = true)?.let { formatsList.add(it) }
            }
        }

        // Adaptive formats (High-res video tracks and high-bitrate audio tracks)
        val adaptiveArray = streamingData.optJSONArray("adaptiveFormats")
        if (adaptiveArray != null) {
            for (i in 0 until adaptiveArray.length()) {
                val fmt = adaptiveArray.getJSONObject(i)
                val mime = fmt.optString("mimeType")
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                parseFormat(fmt, hasVideo = isVideo, hasAudio = isAudio)?.let { formatsList.add(it) }
            }
        }

        if (formatsList.isEmpty()) {
            return Result.failure(Exception("No direct stream links found."))
        }

        val availableQualities = mutableListOf<VideoQuality>()
        if (formatsList.any { it.height >= 1080 && it.hasVideo }) availableQualities.add(VideoQuality.Q_1080P)
        if (formatsList.any { it.height >= 720 && it.hasVideo }) availableQualities.add(VideoQuality.Q_720P)
        if (formatsList.any { it.height >= 480 && it.hasVideo }) availableQualities.add(VideoQuality.Q_480P)
        if (formatsList.any { it.height >= 360 && it.hasVideo }) availableQualities.add(VideoQuality.Q_360P)
        if (formatsList.any { it.hasAudio }) availableQualities.add(VideoQuality.AUDIO_ONLY)

        if (availableQualities.isEmpty()) {
            availableQualities.add(VideoQuality.Q_720P)
        }

        return Result.success(
            VideoPreviewInfo(
                youtubeId = videoId,
                originalUrl = originalUrl,
                title = title,
                channel = channel,
                durationMs = durationMs,
                thumbnailUrl = thumbnailUrl,
                availableQualities = availableQualities,
                formats = formatsList
            )
        )
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

    /**
     * Resolves the best video and audio stream URLs for the selected quality.
     * Guarantees sound: if chosen video stream does not have audio, pairs it with best audio stream.
     */
    fun resolveStreamsForQuality(
        formats: List<StreamFormat>,
        targetQuality: VideoQuality
    ): Pair<StreamFormat, StreamFormat?> {
        if (targetQuality == VideoQuality.AUDIO_ONLY) {
            val bestAudio = formats.filter { it.hasAudio && !it.hasVideo }
                .maxByOrNull { it.bitrate }
                ?: formats.first { it.hasAudio }
            return Pair(bestAudio, null)
        }

        // First check for combined progressive stream (contains both video and audio)
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

        // If the video stream already has audio, no second stream needed
        if (videoStream.hasAudio) {
            return Pair(videoStream, null)
        }

        // Separate audio stream to merge with video using MediaMuxer
        val audioStream = formats.filter { it.hasAudio && !it.hasVideo }
            .maxByOrNull { it.bitrate }

        return Pair(videoStream, audioStream)
    }
}
