package com.youdownloader.app.data.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.youdownloader.app.data.model.StreamFormat
import com.youdownloader.app.data.model.VideoPreviewInfo
import com.youdownloader.app.data.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume

object YouTubeExtractor {

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
     * Primary entry point to fetch video metadata & streams.
     * Uses resilient multi-tier extraction:
     * 1. Official YouTube Android Mobile Client InnerTube Protocol (zero API key needed)
     * 2. Fallback Android Version InnerTube Client
     * 3. Fallback Headless in-app WebView stream interception
     * 4. YouTube Official oEmbed metadata enhancement
     */
    suspend fun fetchVideoInfo(context: Context, rawUrl: String): Result<VideoPreviewInfo> = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(rawUrl)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Invalid YouTube URL. Please enter a valid video or shorts link.")
                )

            // Step 1: Try direct Android Client InnerTube profiles
            var parsedInfo = resolveInnerTubeStreams(videoId, rawUrl)

            // Step 2: If InnerTube was blocked or empty, fallback to Headless WebView
            if (parsedInfo == null || parsedInfo.formats.isEmpty()) {
                val webViewStreams = withTimeoutOrNull(10000L) {
                    extractStreamsViaHeadlessWebView(context, videoId)
                }
                if (!webViewStreams.isNullOrEmpty()) {
                    val oEmbed = fetchOEmbedMetadata(videoId)
                    val title = oEmbed?.optString("title") ?: "YouTube Video"
                    val channel = oEmbed?.optString("author_name") ?: "YouTube Channel"
                    val thumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    parsedInfo = createPreviewInfo(videoId, rawUrl, title, channel, 0L, thumb, webViewStreams)
                }
            }

            if (parsedInfo == null || parsedInfo.formats.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not extract streams. Please check your internet connection or video visibility.")
                )
            }

            Result.success(parsedInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Attempts extraction using native InnerTube Android profiles.
     * Zero API key required - mimics official YouTube Android app player requests.
     */
    private fun resolveInnerTubeStreams(videoId: String, originalUrl: String): VideoPreviewInfo? {
        val clientProfiles = listOf(
            InnerTubeProfile(
                clientName = "ANDROID",
                clientVersion = "20.10.35",
                androidSdkVersion = 34,
                userAgent = "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US; Pixel 8 Pro Build/UQ1A.240205.004) gzip"
            ),
            InnerTubeProfile(
                clientName = "ANDROID",
                clientVersion = "21.26.364",
                androidSdkVersion = 34,
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 14; en_US; Pixel 8 Pro Build/UQ1A.240205.004) gzip"
            ),
            InnerTubeProfile(
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                androidSdkVersion = 32,
                userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3)"
            )
        )

        for (profile in clientProfiles) {
            try {
                val endpoint = "https://www.youtube.com/youtubei/v1/player"
                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", profile.clientName)
                            put("clientVersion", profile.clientVersion)
                            if (profile.androidSdkVersion > 0) {
                                put("androidSdkVersion", profile.androidSdkVersion)
                            }
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val request = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", profile.userAgent)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val status = json.optJSONObject("playabilityStatus")?.optString("status")
                    if (status == "OK") {
                        val preview = parseInnerTubeResponse(json, videoId, originalUrl)
                        if (preview != null && preview.formats.isNotEmpty()) {
                            return preview
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next profile
            }
        }
        return null
    }

    private data class InnerTubeProfile(
        val clientName: String,
        val clientVersion: String,
        val androidSdkVersion: Int,
        val userAgent: String
    )

    private fun parseInnerTubeResponse(
        json: JSONObject,
        videoId: String,
        originalUrl: String
    ): VideoPreviewInfo? {
        val videoDetails = json.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        val channel = videoDetails?.optString("author")?.takeIf { it.isNotBlank() } ?: "YouTube Channel"
        val lengthSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val durationMs = lengthSeconds * 1000L

        var thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        val thumbnails = videoDetails?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        if (thumbnails != null && thumbnails.length() > 0) {
            val bestThumb = thumbnails.getJSONObject(thumbnails.length() - 1)
            thumbnailUrl = bestThumb.optString("url", thumbnailUrl)
        }

        val streamingData = json.optJSONObject("streamingData") ?: return null
        val formatsList = mutableListOf<StreamFormat>()
        parseStreamingData(streamingData, formatsList)

        if (formatsList.isEmpty()) return null

        return createPreviewInfo(videoId, originalUrl, title, channel, durationMs, thumbnailUrl, formatsList)
    }

    private fun createPreviewInfo(
        videoId: String,
        originalUrl: String,
        title: String,
        channel: String,
        durationMs: Long,
        thumbnailUrl: String,
        formatsList: List<StreamFormat>
    ): VideoPreviewInfo {
        val availableQualities = mutableListOf<VideoQuality>()
        if (formatsList.any { it.height >= 1080 && it.hasVideo }) availableQualities.add(VideoQuality.Q_1080P)
        if (formatsList.any { it.height >= 720 && it.hasVideo }) availableQualities.add(VideoQuality.Q_720P)
        if (formatsList.any { it.height >= 480 && it.hasVideo }) availableQualities.add(VideoQuality.Q_480P)
        if (formatsList.any { it.height >= 360 && it.hasVideo }) availableQualities.add(VideoQuality.Q_360P)
        if (formatsList.any { it.hasAudio }) availableQualities.add(VideoQuality.AUDIO_ONLY)

        if (availableQualities.isEmpty()) {
            availableQualities.add(VideoQuality.Q_720P)
            availableQualities.add(VideoQuality.Q_360P)
        }

        return VideoPreviewInfo(
            youtubeId = videoId,
            originalUrl = originalUrl,
            title = title,
            channel = channel,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            availableQualities = availableQualities,
            formats = formatsList
        )
    }

    /**
     * Official YouTube oEmbed Metadata API
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
     * Headless in-app WebView stream interception fallback.
     * Loads the embed player and intercepts direct googlevideo playback URLs.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractStreamsViaHeadlessWebView(
        context: Context,
        videoId: String
    ): List<StreamFormat> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var resumed = false

            fun finishWith(results: List<StreamFormat>) {
                if (!resumed) {
                    resumed = true
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (_: Exception) {}
                    continuation.resume(results)
                }
            }

            try {
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

                    webViewClient = object : WebViewClient() {
                        private val interceptedFormats = mutableListOf<StreamFormat>()

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""

                            if (reqUrl.contains("googlevideo.com/videoplayback")) {
                                val mime = if (reqUrl.contains("mime=audio")) "audio/mp4" else "video/mp4"
                                val isAudio = mime.startsWith("audio")
                                interceptedFormats.add(
                                    StreamFormat(
                                        itag = if (isAudio) 140 else 18,
                                        mimeType = mime,
                                        qualityLabel = if (isAudio) "Audio Only" else "360p",
                                        height = if (isAudio) 0 else 360,
                                        bitrate = 128000L,
                                        contentLength = 0L,
                                        url = reqUrl,
                                        hasVideo = !isAudio,
                                        hasAudio = true
                                    )
                                )

                                if (interceptedFormats.isNotEmpty()) {
                                    post { finishWith(interceptedFormats) }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            evaluateJavascript(
                                "(function() { return (window.ytInitialPlayerResponse) ? JSON.stringify(window.ytInitialPlayerResponse.streamingData) : ''; })()"
                            ) { result ->
                                if (!result.isNullOrBlank() && result != "null" && result != "\"\"") {
                                    try {
                                        val unquoted = if (result.startsWith("\"") && result.endsWith("\"")) {
                                            val inner = result.substring(1, result.length - 1)
                                            inner.replace("\\\"", "\"").replace("\\\\", "\\")
                                        } else result
                                        val streamingJson = JSONObject(unquoted)
                                        val parsed = mutableListOf<StreamFormat>()
                                        parseStreamingData(streamingJson, parsed)
                                        if (parsed.isNotEmpty()) {
                                            finishWith(parsed)
                                            return@evaluateJavascript
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1")
                }

                continuation.invokeOnCancellation {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                finishWith(emptyList())
            }
        }
    }

    private fun parseStreamingData(streamingData: JSONObject?, targetList: MutableList<StreamFormat>) {
        if (streamingData == null) return

        // 1. Progressive formats (video + audio combined: itag 18, 22)
        val formatsArray = streamingData.optJSONArray("formats")
        if (formatsArray != null) {
            for (i in 0 until formatsArray.length()) {
                val fmt = formatsArray.getJSONObject(i)
                parseFormat(fmt, hasVideo = true, hasAudio = true)?.let { targetList.add(it) }
            }
        }

        // 2. Adaptive formats (Separate high quality video and audio streams)
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

    /**
     * Resolves the best video and audio stream URLs for the selected quality.
     * Guarantees sound: if chosen video stream does not have audio, pairs it with best audio stream.
     */
    fun resolveStreamsForQuality(
        formats: List<StreamFormat>,
        targetQuality: VideoQuality
    ): Pair<StreamFormat, StreamFormat?> {
        if (formats.isEmpty()) {
            throw IllegalStateException("No video streams could be resolved for this link.")
        }

        // 1. Audio-only target: strictly select AAC / MP4 audio for universal compatibility
        if (targetQuality == VideoQuality.AUDIO_ONLY) {
            val aacAudio = formats.filter { it.hasAudio && !it.hasVideo && it.mimeType.contains("mp4") }
                .maxByOrNull { it.bitrate }
                ?: formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.bitrate }
                ?: formats.first { it.hasAudio }
            return Pair(aacAudio, null)
        }

        // 2. Progressive match (Both video and audio included in a single stream by YouTube)
        // Progressive streams need 0 muxing and play on 100% of devices
        val progressiveStreams = formats.filter { it.hasVideo && it.hasAudio }
        val exactProgressive = progressiveStreams.firstOrNull { it.height == targetQuality.height }
        if (exactProgressive != null) {
            return Pair(exactProgressive, null)
        }

        // If target is 360p or 480p and we have progressive stream (usually 360p itag 18), use it directly
        if (targetQuality.height <= 480 && progressiveStreams.isNotEmpty()) {
            val bestProg = progressiveStreams.maxByOrNull { it.height }
            if (bestProg != null) {
                return Pair(bestProg, null)
            }
        }

        // 3. Adaptive Video stream: must strictly prefer H.264 (avc1) for Android MediaMuxer MP4 compatibility
        val avcVideos = formats.filter { it.hasVideo && it.mimeType.contains("mp4") && it.mimeType.contains("avc1") }
        val allMp4Videos = formats.filter { it.hasVideo && it.mimeType.contains("mp4") }
        val candidateVideos = if (avcVideos.isNotEmpty()) avcVideos else if (allMp4Videos.isNotEmpty()) allMp4Videos else formats.filter { it.hasVideo }

        val videoStream = candidateVideos
            .filter { it.height <= targetQuality.height }
            .maxByOrNull { it.height }
            ?: candidateVideos.maxByOrNull { it.height }
            ?: formats.first()

        // If the selected video already contains audio, no second stream needed
        if (videoStream.hasAudio) {
            return Pair(videoStream, null)
        }

        // 4. Paired Audio stream for MediaMuxer: MUST strictly be AAC (mp4a / audio/mp4)
        // Android MediaMuxer with MPEG-4 OUTPUT throws IllegalArgumentException on Opus/WebM audio!
        val aacAudio = formats.filter { it.hasAudio && !it.hasVideo && it.mimeType.contains("mp4") }
            .maxByOrNull { it.bitrate }
            ?: formats.filter { it.hasAudio && !it.hasVideo }.firstOrNull()

        return Pair(videoStream, aacAudio)
    }
}
