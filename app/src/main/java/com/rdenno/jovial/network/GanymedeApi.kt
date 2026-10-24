package com.rdenno.jovial.network

import android.net.Uri
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Standard Ganymede API Wrapper ---

data class GanymedeResponse<T>(
    val success: Boolean = true,
    val data: T? = null,
    val message: String? = null
)

data class VodPaginationData(
    val offset: Int = 0,
    val limit: Int = 50,
    @SerializedName("total_count") val totalCount: Int = 0,
    val pages: Int = 0,
    val data: List<GanymedeVod> = emptyList()
)

data class GanymedeVod(
    val id: String,
    @SerializedName("ext_id") val extId: String? = null,
    val title: String = "",
    val duration: Int = 0,
    val views: Int = 0,
    val resolution: String? = null,
    @SerializedName("streamed_at") val streamedAt: String? = null,
    @SerializedName("web_thumbnail_path") val webThumbnailPath: String? = null,
    @SerializedName("thumbnail_path") val thumbnailPath: String? = null,
    @SerializedName("video_path") val videoPath: String = "",
    @SerializedName("video_hls_path") val videoHlsPath: String? = null,
    @SerializedName("chat_video_path") val chatVideoPath: String? = null,
    @SerializedName("chat_path") val chatPath: String? = null,
    val edges: VodEdges? = null
) {
    fun getFullVideoUrl(baseUrl: String, useRenderedChatVideo: Boolean): String {
        val rawPath = when {
            useRenderedChatVideo && !chatVideoPath.isNullOrBlank() -> chatVideoPath
            videoPath.isNotBlank() -> videoPath
            !videoHlsPath.isNullOrBlank() -> videoHlsPath
            else -> videoPath
        }
        return buildMediaUrl(baseUrl, rawPath)
    }

    fun getFullThumbnailUrl(baseUrl: String): String {
        val rawPath = if (!webThumbnailPath.isNullOrBlank()) {
            webThumbnailPath
        } else {
            thumbnailPath ?: ""
        }
        return buildMediaUrl(baseUrl, rawPath)
    }

    private fun buildMediaUrl(baseUrl: String, rawPath: String): String {
        if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            return rawPath
        }
        val cleanBase = baseUrl.trimEnd('/')
        val encodedPath = rawPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else Uri.encode(segment, "@:+$,;=-._~!'()*")
        }
        val normalizedPath = if (encodedPath.startsWith("/")) encodedPath else "/$encodedPath"
        return "$cleanBase$normalizedPath"
    }
}

data class VodEdges(val channel: GanymedeChannel? = null)

data class GanymedeChannel(
    val id: String? = null,
    @SerializedName("ext_id") val extId: String? = null,
    val name: String? = null,
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("image_path") val imagePath: String? = null
)

// --- Chat & Emote Models ---

data class ChatComment(
    @SerializedName("_id") val id: String,
    @SerializedName("content_offset_seconds") val offsetSeconds: Double,
    val commenter: Commenter = Commenter(),
    val message: ChatMessage = ChatMessage()
)

data class Commenter(
    @SerializedName("_id") val id: String? = null,
    val name: String? = null,
    @SerializedName("display_name") val displayName: String = "Anonymous"
)

data class ChatMessage(
    val body: String = "",
    @SerializedName("user_color") val userColor: String? = null,
    val fragments: List<ChatFragment>? = null,
    val emoticons: List<ChatEmoticonElement>? = null
)

data class ChatFragment(
    val text: String = "",
    val emoticon: ChatFragmentEmoticon? = null
)

data class ChatFragmentEmoticon(
    @SerializedName("emoticon_id") val emoticonId: String = "",
    @SerializedName("emoticon_set_id") val emoticonSetId: String? = null
)

data class ChatEmoticonElement(
    @SerializedName("_id") val id: String = "",
    val begin: Int = 0,
    val end: Int = 0
)

data class GanymedeEmote(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val type: String? = null,   // "embed", "twitch", "global", "channel"
    val source: String? = null, // "7tv", "bttv", "ffz", "twitch"
    val width: Int = 28,
    val height: Int = 28
) {
    /**
     * Resolves an Android-compatible image URL or data URI for Coil.
     * Converts Ganymede's 7TV .avif URLs to .webp so animated 7TV emotes play smoothly on all Android versions.
     */
    fun getResolvedUrl(): String {
        if (type == "embed") {
            return if (url.startsWith("data:")) url else "data:image/png;base64,$url"
        }
        var u = url.trim()
        if (u.startsWith("//")) {
            u = "https:$u"
        }
        // Ganymede backend formats 7TV URLs as .../1x.avif; Android ImageDecoder supports animated WebP natively
        if (u.contains("7tv.app") && u.endsWith(".avif")) {
            u = u.removeSuffix("1x.avif").removeSuffix(".avif") + "2x.webp"
        } else if (u.contains("betterttv.net/emote/") && u.endsWith("/1x")) {
            u = u.removeSuffix("/1x") + "/2x"
        }
        return u
    }
}

// --- Retrofit Interface ---

interface GanymedeApi {
    @GET("/api/v1/vod/paginate")
    suspend fun getVodsPaginate(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): GanymedeResponse<VodPaginationData>

    @GET("/api/v1/vod")
    suspend fun getAllVods(): GanymedeResponse<List<GanymedeVod>>

    // Fetches N comments right before a seek timestamp (Ganymede uses 'start' query param)
    @GET("/api/v1/vod/{id}/chat/seek")
    suspend fun getSeekChat(
        @Path("id") vodId: String,
        @Query("start") startSeconds: Double,
        @Query("count") count: Int = 40
    ): GanymedeResponse<List<ChatComment>>

    // Fetches chat comments between [start, end] seconds
    @GET("/api/v1/vod/{id}/chat")
    suspend fun getChatRange(
        @Path("id") vodId: String,
        @Query("start") startSeconds: Double,
        @Query("end") endSeconds: Double
    ): GanymedeResponse<List<ChatComment>>

    // Fetches VOD emotes (embedded + Twitch/7TV/BTTV/FFZ) from Ganymede backend
    @GET("/api/v1/vod/{id}/chat/emotes")
    suspend fun getChatEmotes(
        @Path("id") vodId: String
    ): GanymedeResponse<List<GanymedeEmote>>

    // Fetches the streamer's numeric Twitch user ID from the VOD chat file
    @GET("/api/v1/vod/{id}/chat/userid")
    suspend fun getChatUserId(
        @Path("id") vodId: String
    ): GanymedeResponse<Long>

    companion object {
        var activeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            private set

        fun normalizeServerUrl(rawInput: String): String {
            var cleaned = rawInput.trim()
            if (cleaned.isEmpty()) return ""
            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true)
            ) {
                cleaned = "http://$cleaned"
            }
            // Strip accidental paths like /api/v1 or trailing slashes
            val uri = Uri.parse(cleaned)
            val scheme = uri.scheme ?: "http"
            val authority = uri.encodedAuthority ?: return cleaned.trimEnd('/')
            return "$scheme://$authority"
        }

        fun create(baseUrl: String, apiKey: String? = null): GanymedeApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
                .apply {
                    if (!apiKey.isNullOrBlank()) {
                        addInterceptor { chain ->
                            val req = chain.request().newBuilder()
                                .addHeader("X-API-Key", apiKey.trim())
                                .build()
                            chain.proceed(req)
                        }
                    }
                }.build()

            activeClient = client

            return Retrofit.Builder()
                .baseUrl(baseUrl.trimEnd('/') + "/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GanymedeApi::class.java)
        }
    }
}

/**
 * Direct fetcher for 7TV, BetterTTV (BTTV), and FrankerFaceZ (FFZ) Global + Channel Emotes.
 * Acts as a supplement/fallback when Ganymede's backend only returns embedded Twitch emotes
 * or when Ganymede's Twitch credentials are not configured.
 */
object ThirdPartyEmoteFetcher {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAllThirdPartyEmotes(twitchChannelId: String?): List<GanymedeEmote> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val jobs = mutableListOf(
                    async { fetch7TVGlobal() },
                    async { fetchBTTVGlobal() },
                    async { fetchFFZGlobal() }
                )
                if (!twitchChannelId.isNullOrBlank()) {
                    jobs.add(async { fetch7TVChannel(twitchChannelId) })
                    jobs.add(async { fetchBTTVChannel(twitchChannelId) })
                    jobs.add(async { fetchFFZChannel(twitchChannelId) })
                }
                jobs.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
            }
        }

    private fun getJsonString(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun fetch7TVGlobal(): List<GanymedeEmote> {
        val body = getJsonString("https://7tv.io/v3/emote-sets/global") ?: return emptyList()
        val root = JSONObject(body)
        val emotesArr = root.optJSONArray("emotes") ?: return emptyList()
        return parse7TVEmoteArray(emotesArr)
    }

    private fun fetch7TVChannel(twitchId: String): List<GanymedeEmote> {
        val body = getJsonString("https://7tv.io/v3/users/twitch/$twitchId") ?: return emptyList()
        val root = JSONObject(body)
        val emoteSet = root.optJSONObject("emote_set") ?: return emptyList()
        val emotesArr = emoteSet.optJSONArray("emotes") ?: return emptyList()
        return parse7TVEmoteArray(emotesArr)
    }

    private fun parse7TVEmoteArray(emotesArr: JSONArray): List<GanymedeEmote> {
        val result = ArrayList<GanymedeEmote>(emotesArr.length())
        for (i in 0 until emotesArr.length()) {
            val item = emotesArr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = item.optString("name")
            val data = item.optJSONObject("data")
            val host = data?.optJSONObject("host")
            val hostUrl = host?.optString("url")?.let {
                if (it.startsWith("//")) "https:$it" else it
            } ?: "https://cdn.7tv.app/emote/$id"
            val files = host?.optJSONArray("files")
            var width = 28
            var height = 28
            if (files != null && files.length() > 0) {
                val firstFile = files.optJSONObject(0)
                if (firstFile != null) {
                    width = firstFile.optInt("width", 28)
                    height = firstFile.optInt("height", 28)
                }
            }
            if (name.isNotBlank()) {
                result.add(
                    GanymedeEmote(
                        id = id,
                        name = name,
                        url = "$hostUrl/2x.webp",
                        type = "global",
                        source = "7tv",
                        width = width,
                        height = height
                    )
                )
            }
        }
        return result
    }

    private fun fetchBTTVGlobal(): List<GanymedeEmote> {
        val body = getJsonString("https://api.betterttv.net/3/cached/emotes/global") ?: return emptyList()
        val arr = JSONArray(body)
        return parseBTTVEmoteArray(arr)
    }

    private fun fetchBTTVChannel(twitchId: String): List<GanymedeEmote> {
        val body = getJsonString("https://api.betterttv.net/3/cached/users/twitch/$twitchId") ?: return emptyList()
        val root = JSONObject(body)
        val result = mutableListOf<GanymedeEmote>()
        root.optJSONArray("channelEmotes")?.let { result.addAll(parseBTTVEmoteArray(it)) }
        root.optJSONArray("sharedEmotes")?.let { result.addAll(parseBTTVEmoteArray(it)) }
        return result
    }

    private fun parseBTTVEmoteArray(arr: JSONArray): List<GanymedeEmote> {
        val result = ArrayList<GanymedeEmote>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val code = item.optString("code")
            if (id.isNotBlank() && code.isNotBlank()) {
                result.add(
                    GanymedeEmote(
                        id = id,
                        name = code,
                        url = "https://cdn.betterttv.net/emote/$id/2x",
                        type = "global",
                        source = "bttv",
                        width = 28,
                        height = 28
                    )
                )
            }
        }
        return result
    }

    private fun fetchFFZGlobal(): List<GanymedeEmote> {
        val body = getJsonString("https://api.betterttv.net/3/cached/frankerfacez/emotes/global") ?: return emptyList()
        return parseFFZEmoteArray(JSONArray(body))
    }

    private fun fetchFFZChannel(twitchId: String): List<GanymedeEmote> {
        val body = getJsonString("https://api.betterttv.net/3/cached/frankerfacez/users/twitch/$twitchId") ?: return emptyList()
        return parseFFZEmoteArray(JSONArray(body))
    }

    private fun parseFFZEmoteArray(arr: JSONArray): List<GanymedeEmote> {
        val result = ArrayList<GanymedeEmote>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optLong("id").toString()
            val code = item.optString("code")
            val images = item.optJSONObject("images")
            val url = images?.optString("2x")?.takeIf { it.isNotBlank() }
                ?: images?.optString("1x")
                ?: continue
            if (code.isNotBlank()) {
                result.add(
                    GanymedeEmote(
                        id = id,
                        name = code,
                        url = url,
                        type = "global",
                        source = "ffz",
                        width = 28,
                        height = 28
                    )
                )
            }
        }
        return result
    }
}
